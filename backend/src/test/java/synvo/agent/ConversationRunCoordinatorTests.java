package synvo.agent;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import synvo.agent.SynvoAgentCore.PreparedConversation;
import synvo.configuration.AgentRuntimeProperties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

	@Test
	void synchronousSurfaceRunUsesTheSameTimeoutLifecycleAndCleanupPolicy() throws Exception {
		coordinator = new ConversationRunCoordinator(
				core, mock(AgentEventPublisher.class),
				new AgentRuntimeProperties(Duration.ofMillis(40)));
		List<AgentLifecycleEvent> observed = new CopyOnWriteArrayList<>();
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			var listener = (java.util.function.Consumer<AgentLifecycleEvent>) invocation.getArgument(1);
			var cancellation = (AgentCancellation) invocation.getArgument(2);
			listener.accept(AgentLifecycleEvent.contentDelta(2, "Hello"));
			cancellation.onCancel(() -> {
				timedOut.set(cancellation.timedOut());
				cancellationObserved.countDown();
			});
			cancellationObserved.await(2, TimeUnit.SECONDS);
			return null;
		}).when(core).execute(any(), any(), any());

		CompletableFuture<ConversationResult> result = CompletableFuture.supplyAsync(
				() -> coordinator.run(request("lark-timeout-run"), observed::add));

		assertTrue(cancellationObserved.await(2, TimeUnit.SECONDS));
		assertTrue(timedOut.get());
		assertEquals(List.of(AgentLifecycleEvent.State.CONTENT_DELTA),
				observed.stream().map(AgentLifecycleEvent::state).toList());
		result.get(2, TimeUnit.SECONDS);
		assertFalse(coordinator.stop(run.runId()));
	}

	@Test
	void synchronousSurfaceReceivesRunIdentityBeforeExecutionAndCanCancelIt() throws Exception {
		coordinator = new ConversationRunCoordinator(
				core, mock(AgentEventPublisher.class),
				new AgentRuntimeProperties(Duration.ofMinutes(2)));
		CountDownLatch identified = new CountDownLatch(1);
		AtomicReference<ConversationRunCoordinator.Submission> submission =
				new AtomicReference<>();

		CompletableFuture<ConversationResult> result = CompletableFuture.supplyAsync(
				() -> coordinator.run(
						request("lark-cancellable-run"),
						accepted -> {
							submission.set(accepted);
							identified.countDown();
						},
						ignored -> { }));

		assertTrue(identified.await(2, TimeUnit.SECONDS));
		assertEquals(run.runId(), submission.get().runId());
		assertTrue(coordinator.stop(submission.get().runId()));
		assertTrue(cancellationObserved.await(2, TimeUnit.SECONDS));
		result.get(2, TimeUnit.SECONDS);
	}

	@Test
	void surfaceDeliveryFailureStillTerminatesTheRunAndPropagatesToTheAdapter() {
		coordinator = new ConversationRunCoordinator(
				core, mock(AgentEventPublisher.class),
				new AgentRuntimeProperties(Duration.ofMinutes(2)));
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked")
			var listener = (java.util.function.Consumer<AgentLifecycleEvent>) invocation.getArgument(1);
			listener.accept(AgentLifecycleEvent.contentDelta(2, "Partial"));
			return null;
		}).when(core).execute(any(), any(), any());

		IllegalStateException failure = assertThrows(IllegalStateException.class,
				() -> coordinator.run(request("delivery-failure"), ignored -> {
					throw new IllegalStateException("surface unavailable");
				}));

		assertEquals("surface unavailable", failure.getMessage());
		verify(core).failUnexpected(any(), any());
		assertFalse(coordinator.stop(run.runId()));
	}

	private static ConversationRequest request(String requestId) {
		return new ConversationRequest(requestId, null, "ou-victor", "Explain SSE");
	}
}
