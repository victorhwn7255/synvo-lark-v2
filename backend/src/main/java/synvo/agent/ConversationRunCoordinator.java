package synvo.agent;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import synvo.agent.SynvoAgentCore.PreparedConversation;
import synvo.configuration.AgentRuntimeProperties;

@Component
public final class ConversationRunCoordinator {

	private static final Logger log = LoggerFactory.getLogger(ConversationRunCoordinator.class);

	private final SynvoAgentCore agentCore;
	private final AgentEventPublisher eventPublisher;
	private final Duration responseTimeout;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform().daemon().name("synvo-agent-timeout").factory());
	private final Map<UUID, ActiveRun> activeByRun = new HashMap<>();
	private final Map<String, ActiveRun> activeByRequest = new HashMap<>();

	public ConversationRunCoordinator(
			SynvoAgentCore agentCore,
			AgentEventPublisher eventPublisher,
			AgentRuntimeProperties properties) {
		this.agentCore = agentCore;
		this.eventPublisher = eventPublisher;
		this.responseTimeout = properties.responseTimeout();
	}

	public synchronized Submission submit(ConversationRequest request) {
		ActiveRun existing = activeByRequest.get(request.requestId());
		if (existing != null) {
			return existing.submission();
		}

		PreparedConversation prepared = agentCore.prepare(request, ignored -> { });
		if (prepared.replayed()) {
			ConversationResult replay = prepared.replay();
			return new Submission(
					request.requestId(), replay.conversationId(), replay.runId(), null, null,
					replay.intent(), replay.status().name(), true);
		}

		ActiveRun active = activate(request, prepared);
		executor.submit(() -> execute(active));
		return active.submission();
	}

	/**
	 * Runs a conversation on the caller's thread while this boundary owns timeout,
	 * cancellation, terminal failure handling, and active-run cleanup. Surface adapters
	 * provide only the lifecycle observer used to render their response.
	 */
	public ConversationResult run(
			ConversationRequest request,
			Consumer<AgentLifecycleEvent> observer) {
		Objects.requireNonNull(request, "request");
		Objects.requireNonNull(observer, "observer");
		ActiveRun active;
		synchronized (this) {
			if (activeByRequest.containsKey(request.requestId())) {
				throw new ConversationAlreadyRunningException();
			}
			PreparedConversation prepared = agentCore.prepare(request, observer);
			if (prepared.replayed()) {
				return prepared.replay();
			}
			active = activate(request, prepared);
		}
		return execute(active, observer);
	}

	public synchronized boolean stop(UUID runId) {
		ActiveRun active = activeByRun.get(runId);
		return active != null && active.cancellation.cancel();
	}

	private void execute(ActiveRun active) {
		execute(active, event -> eventPublisher.publish(active.submission.runId(), event));
	}

	private ConversationResult execute(
			ActiveRun active,
			Consumer<AgentLifecycleEvent> observer) {
		Consumer<AgentLifecycleEvent> guardedObserver = event -> {
			try {
				observer.accept(event);
			}
			catch (RuntimeException deliveryFailure) {
				throw new LifecycleDeliveryException(deliveryFailure);
			}
		};
		try {
			return agentCore.execute(
					active.prepared,
					guardedObserver,
					active.cancellation);
		}
		catch (LifecycleDeliveryException deliveryFailure) {
			try {
				agentCore.failUnexpected(active.prepared, ignored -> { });
			}
			catch (RuntimeException terminalFailure) {
				log.error("Agent run could not reach a terminal state after delivery failed ({})",
						terminalFailure.getClass().getSimpleName());
			}
			throw deliveryFailure.deliveryFailure();
		}
		catch (RuntimeException failure) {
			log.warn("Agent run failed unexpectedly ({})", failure.getClass().getSimpleName());
			try {
				return agentCore.failUnexpected(
						active.prepared,
						guardedObserver);
			}
			catch (RuntimeException terminalFailure) {
				log.error("Agent run could not reach a terminal state ({})",
						terminalFailure.getClass().getSimpleName());
				throw terminalFailure;
			}
		}
		finally {
			remove(active);
		}
	}

	private ActiveRun activate(ConversationRequest request, PreparedConversation prepared) {
		ConversationStore.RunHandle run = prepared.run();
		Submission submission = new Submission(
				request.requestId(), run.conversationId(), run.runId(), run.userTurnId(),
				run.assistantTurnId(), run.intent(), "RUNNING", false);
		ActiveRun active = new ActiveRun(submission, prepared, new AgentCancellation());
		activeByRun.put(run.runId(), active);
		activeByRequest.put(request.requestId(), active);
		active.timeoutFuture = scheduler.schedule(
				active.cancellation::timeout,
				responseTimeout.toMillis(),
				TimeUnit.MILLISECONDS);
		return active;
	}

	private synchronized void remove(ActiveRun active) {
		activeByRun.remove(active.submission.runId(), active);
		activeByRequest.remove(active.submission.requestId(), active);
		if (active.timeoutFuture != null) {
			active.timeoutFuture.cancel(false);
		}
	}

	@PreDestroy
	void shutdown() {
		synchronized (this) {
			for (ActiveRun active : activeByRun.values()) {
				active.cancellation.cancel();
			}
		}
		executor.shutdown();
		scheduler.shutdown();
	}

	public record Submission(
			String requestId,
			UUID conversationId,
			UUID runId,
			UUID userTurnId,
			UUID assistantTurnId,
			AgentIntent intent,
			String status,
			boolean replayed
	) {
	}

	private static final class ActiveRun {

		private final Submission submission;
		private final PreparedConversation prepared;
		private final AgentCancellation cancellation;
		private ScheduledFuture<?> timeoutFuture;

		private ActiveRun(
				Submission submission,
				PreparedConversation prepared,
				AgentCancellation cancellation) {
			this.submission = submission;
			this.prepared = prepared;
			this.cancellation = cancellation;
		}

		private Submission submission() {
			return submission;
		}
	}

	private static final class LifecycleDeliveryException extends RuntimeException {

		private final RuntimeException deliveryFailure;

		private LifecycleDeliveryException(RuntimeException deliveryFailure) {
			super(deliveryFailure);
			this.deliveryFailure = deliveryFailure;
		}

		private RuntimeException deliveryFailure() {
			return deliveryFailure;
		}
	}
}
