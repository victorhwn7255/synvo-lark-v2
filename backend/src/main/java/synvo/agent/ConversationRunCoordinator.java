package synvo.agent;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
		executor.submit(() -> execute(active));
		return submission;
	}

	public synchronized boolean stop(UUID runId) {
		ActiveRun active = activeByRun.get(runId);
		return active != null && active.cancellation.cancel();
	}

	private void execute(ActiveRun active) {
		UUID runId = active.submission.runId();
		try {
			agentCore.execute(
					active.prepared,
					event -> eventPublisher.publish(runId, event),
					active.cancellation);
		}
		catch (RuntimeException failure) {
			log.warn("Agent run failed unexpectedly ({})", failure.getClass().getSimpleName());
			try {
				agentCore.failUnexpected(
						active.prepared,
						event -> eventPublisher.publish(runId, event));
			}
			catch (RuntimeException terminalFailure) {
				log.error("Agent run could not reach a terminal state ({})",
						terminalFailure.getClass().getSimpleName());
			}
		}
		finally {
			remove(active);
		}
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
}
