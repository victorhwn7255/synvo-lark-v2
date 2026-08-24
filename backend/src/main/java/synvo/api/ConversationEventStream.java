package synvo.api;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import synvo.agent.AgentEventPublisher;
import synvo.agent.AgentLifecycleEvent;
import synvo.agent.ConversationStore;

@Component
final class ConversationEventStream implements AgentEventPublisher {

	private static final long EMITTER_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

	private final ConversationStore conversationStore;
	private final Map<UUID, Object> runLocks = new HashMap<>();
	private final Map<UUID, List<Subscriber>> subscribers = new HashMap<>();

	ConversationEventStream(ConversationStore conversationStore) {
		this.conversationStore = conversationStore;
	}

	SseEmitter subscribe(UUID runId, int afterSequence) {
		SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
		Subscriber subscriber = new Subscriber(emitter, afterSequence);
		Object lock = lockFor(runId);
		synchronized (lock) {
			List<AgentLifecycleEvent> replay = conversationStore.loadEvents(runId, afterSequence);
			for (AgentLifecycleEvent event : replay) {
				if (!send(subscriber, event)) {
					return emitter;
				}
			}
			if (replay.stream().anyMatch(ConversationEventStream::isTerminal)) {
				emitter.complete();
				return emitter;
			}
			subscribers.computeIfAbsent(runId, ignored -> new ArrayList<>()).add(subscriber);
		}
		emitter.onCompletion(() -> remove(runId, subscriber));
		emitter.onTimeout(() -> remove(runId, subscriber));
		emitter.onError(ignored -> remove(runId, subscriber));
		return emitter;
	}

	@Override
	public void publish(UUID runId, AgentLifecycleEvent event) {
		Object lock = lockFor(runId);
		synchronized (lock) {
			List<Subscriber> current = subscribers.get(runId);
			if (current == null) {
				return;
			}
			current.removeIf(subscriber -> !send(subscriber, event));
			if (isTerminal(event)) {
				for (Subscriber subscriber : current) {
					subscriber.emitter.complete();
				}
				current.clear();
			}
			if (current.isEmpty()) {
				subscribers.remove(runId);
			}
		}
	}

	private boolean send(Subscriber subscriber, AgentLifecycleEvent event) {
		if (event.sequence() <= subscriber.lastSequence) {
			return true;
		}
		try {
			subscriber.emitter.send(SseEmitter.event()
					.id(Integer.toString(event.sequence()))
					.name(event.state().name().toLowerCase(Locale.ROOT))
					.data(new StreamEvent(
						event.sequence(),
						event.state().name().toLowerCase(Locale.ROOT),
						 event.safeMessage(),
						 event.contentDelta(),
						 event.actionHandoff())));
			subscriber.lastSequence = event.sequence();
			return true;
		}
		catch (IOException | IllegalStateException failure) {
			subscriber.emitter.complete();
			return false;
		}
	}

	private synchronized Object lockFor(UUID runId) {
		return runLocks.computeIfAbsent(runId, ignored -> new Object());
	}

	private void remove(UUID runId, Subscriber subscriber) {
		Object lock = lockFor(runId);
		synchronized (lock) {
			List<Subscriber> current = subscribers.get(runId);
			if (current != null) {
				current.remove(subscriber);
				if (current.isEmpty()) {
					subscribers.remove(runId);
				}
			}
		}
	}

	private static boolean isTerminal(AgentLifecycleEvent event) {
		return event.state() == AgentLifecycleEvent.State.COMPLETED
				|| event.state() == AgentLifecycleEvent.State.FAILED;
	}

	record StreamEvent(
			int sequence,
			String type,
			String message,
			String delta,
			AgentLifecycleEvent.ActionHandoff action
	) {
	}

	private static final class Subscriber {

		private final SseEmitter emitter;
		private int lastSequence;

		private Subscriber(SseEmitter emitter, int lastSequence) {
			this.emitter = emitter;
			this.lastSequence = lastSequence;
		}
	}
}
