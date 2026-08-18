package synvo.agent;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import synvo.agent.SynvoAgentCore.PreparedConversation;
import synvo.configuration.AgentRuntimeProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationRunCoordinatorTests {

	private SynvoAgentCore core;
	private PreparedConversation prepared;
	private ConversationStore.RunHandle run;
	private CountDownLatch cancellationObserved;
	private AtomicBoolean timedOut;
	private ConversationRunCoordinator coordinator;

	@BeforeEach
	void setUp() {
		core = mock(SynvoAgentCore.class);
		prepared = mock(PreparedConversation.class);
		run = new ConversationStore.RunHandle(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				AgentIntent.DIRECT_ANSWER);
		cancellationObserved = new CountDownLatch(1);
		timedOut = new AtomicBoolean();
		when(core.prepare(any(), any())).thenReturn(prepared);
		when(prepared.replayed()).thenReturn(false);
		when(prepared.run()).thenReturn(run);
		doAnswer(invocation -> {
			var cancellation = (AgentCancellation) invocation.getArgument(2);
			cancellation.onCancel(() -> {
				timedOut.set(cancellation.timedOut());
				cancellationObserved.countDown();
			});
			cancellationObserved.await(2, TimeUnit.SECONDS);
			return null;
		}).when(core).execute(any(), any(), any());
	}

	@AfterEach
	void tearDown() {
		if (coordinator != null) {
			coordinator.shutdown();
		}
	}

	@Test
	void configuredTimeoutCancelsAnAcceptedRunAsTimeout() throws Exception {
		coordinator = new ConversationRunCoordinator(
				core, mock(AgentEventPublisher.class),
				new AgentRuntimeProperties(Duration.ofMillis(40)));

		ConversationRunCoordinator.Submission submission = coordinator.submit(request("timeout-run"));

		assertEquals(run.runId(), submission.runId());
		assertTrue(cancellationObserved.await(2, TimeUnit.SECONDS));
		assertTrue(timedOut.get());
	}

	@Test
	void duplicateActiveRequestReusesOneRunAndExplicitStopIsIdempotent() throws Exception {
		coordinator = new ConversationRunCoordinator(
				core, mock(AgentEventPublisher.class),
				new AgentRuntimeProperties(Duration.ofMinutes(2)));
		ConversationRequest request = request("duplicate-active-run");

		ConversationRunCoordinator.Submission first = coordinator.submit(request);
		ConversationRunCoordinator.Submission duplicate = coordinator.submit(request);

		assertEquals(first, duplicate);
		verify(core).prepare(any(), any());
		assertTrue(coordinator.stop(first.runId()));
		assertFalse(coordinator.stop(first.runId()));
		assertTrue(cancellationObserved.await(2, TimeUnit.SECONDS));
		assertFalse(timedOut.get());
	}

	private static ConversationRequest request(String requestId) {
		return new ConversationRequest(requestId, null, "ou-victor", "Explain SSE");
	}
}
