package synvo.api;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import synvo.workspaceagent.WorkspaceAgentEventPublisher;
import synvo.workspaceagent.WorkspaceAgentFacade.ActivityView;
import synvo.workspaceagent.WorkspaceAgentFacade.InteractionView;

/** Owner-authorized, no-store delivery of transient workspace-agent activity. */
@Component
final class WorkspaceAgentEventStream implements WorkspaceAgentEventPublisher {

	private static final long EMITTER_TIMEOUT_MILLIS = Duration.ofMinutes(30).toMillis();

	// Bound lock retention; different stripes still share a concurrent subscriber index.
	private final Object[] operationLocks = IntStream.range(0, 64).mapToObj(ignored -> new Object()).toArray();
	private final Map<UUID, List<Subscriber>> subscribers = new ConcurrentHashMap<>();

	SseEmitter subscribe(
			UUID operationId,
			long afterSequence,
			Supplier<List<ActivityView>> replaySupplier) {
		SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
		Subscriber subscriber = new Subscriber(emitter, afterSequence);
		Object lock = lockFor(operationId);
		synchronized (lock) {
			List<ActivityView> replay = replaySupplier.get();
			for (ActivityView activity : replay) {
				if (!sendActivity(subscriber, activity)) {
					return emitter;
				}
			}
			if (replay.stream().anyMatch(WorkspaceAgentEventStream::isTerminal)) {
				emitter.complete();
				return emitter;
			}
			subscribers.computeIfAbsent(operationId, ignored -> new ArrayList<>())
					.add(subscriber);
		}
		emitter.onCompletion(() -> remove(operationId, subscriber));
		emitter.onTimeout(() -> remove(operationId, subscriber));
		emitter.onError(ignored -> remove(operationId, subscriber));
		return emitter;
	}

	@Override
	public void publish(UUID operationId, ActivityView activity) {
		Object lock = lockFor(operationId);
		synchronized (lock) {
			List<Subscriber> current = subscribers.get(operationId);
			if (current == null) {
				return;
			}
			current.removeIf(subscriber -> !sendActivity(subscriber, activity));
			if (isTerminal(activity)) {
				current.forEach(subscriber -> subscriber.emitter.complete());
				current.clear();
			}
			if (current.isEmpty()) {
				subscribers.remove(operationId);
			}
		}
	}

	@Override
	public void interactionRequired(UUID operationId, InteractionView interaction) {
		Object lock = lockFor(operationId);
		synchronized (lock) {
			List<Subscriber> current = subscribers.get(operationId);
			if (current == null) {
				return;
			}
			current.removeIf(subscriber -> !sendInteraction(subscriber, interaction));
			if (current.isEmpty()) {
				subscribers.remove(operationId);
			}
		}
	}

	private boolean sendActivity(Subscriber subscriber, ActivityView activity) {
		if (activity.sequence() <= subscriber.lastSequence) {
			return true;
		}
		try {
			subscriber.emitter.send(SseEmitter.event()
					.id(Long.toString(activity.sequence()))
					.name(activity.kind().name().toLowerCase(Locale.ROOT))
					.data(new ActivityEvent(
							activity.sequence(),
							activity.kind().name(),
							activity.label(),
							activity.transientText(),
							activity.truncated(),
							activity.terminalStatus() == null
									? null : activity.terminalStatus().name())));
			subscriber.lastSequence = activity.sequence();
			return true;
		}
		catch (IOException | IllegalStateException failure) {
			subscriber.emitter.complete();
			return false;
		}
	}

	private boolean sendInteraction(Subscriber subscriber, InteractionView interaction) {
		try {
			subscriber.emitter.send(SseEmitter.event()
					.name("interaction_required")
					.data(new InteractionEvent(
							interaction.interactionId(),
							interaction.taskId(),
							interaction.operationId(),
							interaction.kind().name(),
							interaction.category(),
							interaction.reason(),
							interaction.permissionScope(),
							interaction.expiresAt())));
			return true;
		}
		catch (IOException | IllegalStateException failure) {
			subscriber.emitter.complete();
			return false;
		}
	}

	private Object lockFor(UUID operationId) {
		return operationLocks[Math.floorMod(operationId.hashCode(), operationLocks.length)];
	}

	private void remove(UUID operationId, Subscriber subscriber) {
		Object lock = lockFor(operationId);
		synchronized (lock) {
			List<Subscriber> current = subscribers.get(operationId);
			if (current != null) {
				current.remove(subscriber);
				if (current.isEmpty()) {
					subscribers.remove(operationId);
				}
			}
		}
	}

	private static boolean isTerminal(ActivityView activity) {
		return activity.terminalStatus() != null;
	}

	record ActivityEvent(
			long sequence,
			String type,
			String label,
			String text,
			boolean truncated,
			String terminalStatus
	) {
	}

	record InteractionEvent(
			UUID interactionId,
			UUID taskId,
			UUID operationId,
			String kind,
			String category,
			String reason,
			String permissionScope,
			java.time.Instant expiresAt
	) {
	}

	private static final class Subscriber {

		private final SseEmitter emitter;
		private long lastSequence;

		private Subscriber(SseEmitter emitter, long lastSequence) {
			this.emitter = emitter;
			this.lastSequence = lastSequence;
		}
	}
}
