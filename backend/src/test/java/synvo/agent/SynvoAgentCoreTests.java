package synvo.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import synvo.agent.AgentLifecycleEvent.State;
import synvo.agent.ConversationResult.Outcome;
import synvo.agent.ConversationResult.Status;
import synvo.agent.model.ModelGateway;
import synvo.agent.model.ModelGatewayException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SynvoAgentCoreTests {

	@Test
	void directAnswerUsesBoundedContextAndReachesCompleted() {
		FakeConversationStore store = new FakeConversationStore();
		FakeModelGateway model = FakeModelGateway.responding("A concise model answer.");
		SynvoAgentCore core = new SynvoAgentCore(new IntentRouter(), store, model);

		ConversationResult result = core.converse(request("direct-1", null, "What is an API?"));

		assertEquals(AgentIntent.DIRECT_ANSWER, result.intent());
		assertEquals(Outcome.DIRECT_ANSWER, result.outcome());
		assertEquals(Status.COMPLETED, result.status());
		assertEquals("A concise model answer.", result.response());
		assertEquals(List.of(
				State.ACCEPTED, State.THINKING, State.STREAMING,
				State.CONTENT_DELTA, State.COMPLETED), states(result));
		assertEquals(1, model.requests().size());
		assertEquals(ModelGateway.Role.SYSTEM, model.requests().getFirst().messages().getFirst().role());
		assertTrue(model.requests().getFirst().messages().getFirst().content()
				.contains("Use clean CommonMark Markdown"));
		assertTrue(model.requests().getFirst().messages().getFirst().content()
				.contains("Do not imply that Synvo currently supports"));
		assertEquals(ModelGateway.Role.USER, model.requests().getFirst().messages().getLast().role());
		assertEquals(SynvoAgentCore.CONTEXT_MAX_MESSAGES, store.requestedMaxMessages);
		assertEquals(SynvoAgentCore.CONTEXT_MAX_CHARACTERS, store.requestedMaxCharacters);
	}

	@Test
	void orderedModelDeltasRemainOneContiguousLifecycleAndOneResponse() {
		FakeConversationStore store = new FakeConversationStore();
		FakeModelGateway model = FakeModelGateway.streaming(
				"First paragraph.", "\n\n", "Second paragraph.");
		SynvoAgentCore core = new SynvoAgentCore(new IntentRouter(), store, model);
		List<AgentLifecycleEvent> observed = new ArrayList<>();

		ConversationResult result = core.converseStreaming(
				request("streaming-1", null, "Explain streaming"),
				observed::add,
				ModelGateway.ModelCancellation.none());

		assertEquals("First paragraph.\n\nSecond paragraph.", result.response());
		assertEquals(List.of(
				State.ACCEPTED, State.THINKING, State.STREAMING,
				State.CONTENT_DELTA, State.CONTENT_DELTA, State.CONTENT_DELTA,
				State.COMPLETED), states(result));
		assertEquals(List.of("First paragraph.", "\n\n", "Second paragraph."), result.events().stream()
				.filter(event -> event.state() == State.CONTENT_DELTA)
				.map(AgentLifecycleEvent::contentDelta)
				.toList());
		assertEquals(result.events(), observed);
		assertEquals(List.of(1, 2, 3, 4, 5, 6, 7), result.events().stream()
				.map(AgentLifecycleEvent::sequence)
				.toList());
	}

	@Test
	void transientProviderFailureResetsPartialContentAndRegeneratesOnce() {
		FakeConversationStore store = new FakeConversationStore();
		AtomicInteger streamAttempts = new AtomicInteger();
		AtomicInteger fallbackAttempts = new AtomicInteger();
		ModelGateway model = new ModelGateway() {
			@Override
			public ModelGateway.ModelResponse generate(ModelGateway.ModelRequest request) {
				fallbackAttempts.incrementAndGet();
				return new ModelGateway.ModelResponse("Recovered response.");
			}

			@Override
			public ModelGateway.ModelResponse stream(
					ModelGateway.ModelRequest request,
					java.util.function.Consumer<String> onDelta,
					ModelGateway.ModelCancellation cancellation) {
				streamAttempts.incrementAndGet();
				onDelta.accept("Discarded partial response");
				throw new ModelGatewayException(ModelGatewayException.Code.PROVIDER_FAILURE);
			}
		};
		SynvoAgentCore core = new SynvoAgentCore(new IntentRouter(), store, model);

		ConversationResult result = core.converse(request("provider-retry-1", null, "Explain OAuth"));

		assertEquals(Status.COMPLETED, result.status());
		assertEquals("Recovered response.", result.response());
		assertEquals(1, streamAttempts.get());
		assertEquals(1, fallbackAttempts.get());
		assertEquals(1, store.resetCount);
		assertEquals(List.of(
				State.ACCEPTED, State.THINKING,
				State.STREAMING, State.CONTENT_DELTA, State.CONTENT_RESET,
				State.STREAMING, State.CONTENT_DELTA,
				State.COMPLETED), states(result));
	}

	@Test
	void userCancellationAndTimeoutBecomeDistinctSafeTerminalFailures() {
		SynvoAgentCore core = new SynvoAgentCore(
				new IntentRouter(), new FakeConversationStore(), FakeModelGateway.responding("unused"));
		AgentCancellation cancelled = new AgentCancellation();
		cancelled.cancel();
		AgentCancellation timedOut = new AgentCancellation();
		timedOut.timeout();

		ConversationResult stopped = core.converseStreaming(
				request("cancelled-1", null, "Explain OAuth"), ignored -> { }, cancelled);
		ConversationResult timeout = core.converseStreaming(
				request("timeout-1", null, "Explain OAuth"), ignored -> { }, timedOut);

		assertEquals(Status.FAILED, stopped.status());
		assertEquals("Response stopped.", stopped.response());
		assertEquals(List.of(State.ACCEPTED, State.FAILED), states(stopped));
		assertEquals(Status.FAILED, timeout.status());
		assertEquals("The response timed out. Please try again.", timeout.response());
		assertEquals(List.of(State.ACCEPTED, State.FAILED), states(timeout));
	}

	@Test
	void clarificationIsExplicitAndDoesNotCallTheModel() {
		FakeConversationStore store = new FakeConversationStore();
		FakeModelGateway model = FakeModelGateway.responding("should not be used");
		SynvoAgentCore core = new SynvoAgentCore(new IntentRouter(), store, model);

		ConversationResult result = core.converse(request("clarify-1", null, "help"));

		assertEquals(AgentIntent.CLARIFICATION, result.intent());
		assertEquals(Outcome.CLARIFICATION, result.outcome());
		assertEquals(List.of(
				State.ACCEPTED, State.STREAMING, State.CONTENT_DELTA, State.COMPLETED),
				states(result));
		assertTrue(result.response().startsWith("Could you clarify"));
		assertTrue(model.requests().isEmpty());
	}

	@Test
	void futureWorkflowIntentsReturnHonestUnavailableOutcomes() {
		FakeConversationStore store = new FakeConversationStore();
		FakeModelGateway model = FakeModelGateway.responding("should not be used");
		SynvoAgentCore core = new SynvoAgentCore(new IntentRouter(), store, model);

		ConversationResult research = core.converse(request(
				"research-1", null, "Research the launch plan in my Drive folder"));
		ConversationResult meeting = core.converse(request(
				"meeting-1", null, "Turn the meeting transcript into action items"));

		assertEquals(AgentIntent.RESEARCH, research.intent());
		assertEquals(Outcome.WORKFLOW_UNAVAILABLE, research.outcome());
		assertTrue(research.response().contains("won’t pretend"));
		assertEquals(AgentIntent.MEETING, meeting.intent());
		assertEquals(Outcome.WORKFLOW_UNAVAILABLE, meeting.outcome());
		assertTrue(meeting.response().contains("won’t pretend"));
		assertTrue(model.requests().isEmpty());
	}

	@Test
	void providerFailureBecomesSafeTerminalFailure() {
		FakeConversationStore store = new FakeConversationStore();
		FakeModelGateway model = FakeModelGateway.failing(ModelGatewayException.Code.PROVIDER_FAILURE);
		SynvoAgentCore core = new SynvoAgentCore(new IntentRouter(), store, model);

		ConversationResult result = core.converse(request("failure-1", null, "Explain OAuth"));

		assertEquals(Status.FAILED, result.status());
		assertEquals(Outcome.FAILED, result.outcome());
		assertEquals(List.of(State.ACCEPTED, State.THINKING, State.FAILED), states(result));
		assertEquals("I couldn’t complete that response. Please try again.", result.response());
		assertFalse(result.response().contains("PROVIDER"));
	}

	@Test
	void completedRequestIsReplayedWithoutAnotherModelInvocation() {
		FakeConversationStore store = new FakeConversationStore();
		FakeModelGateway model = FakeModelGateway.responding("Stable response");
		SynvoAgentCore core = new SynvoAgentCore(new IntentRouter(), store, model);
		ConversationRequest request = request("idempotent-1", null, "Explain TLS");

		ConversationResult first = core.converse(request);
		ConversationResult replay = core.converse(request);

		assertFalse(first.replayed());
		assertTrue(replay.replayed());
		assertEquals(first.runId(), replay.runId());
		assertEquals(1, model.requests().size());
	}

	private static ConversationRequest request(String requestId, UUID conversationId, String content) {
		return new ConversationRequest(requestId, conversationId, "ou-victor", content);
	}

	private static List<State> states(ConversationResult result) {
		return result.events().stream().map(AgentLifecycleEvent::state).toList();
	}

	private static final class FakeConversationStore implements ConversationStore {

		private final Map<String, ConversationResult> terminalByRequest = new HashMap<>();
		private final Map<UUID, String> requestByRun = new HashMap<>();
		private final Map<UUID, List<AgentLifecycleEvent>> eventsByRun = new HashMap<>();
		private final Map<UUID, List<ConversationContextMessage>> contextByConversation = new HashMap<>();
		private int requestedMaxMessages;
		private int requestedMaxCharacters;
		private int resetCount;

		@Override
		public Optional<ConversationResult> findTerminalResult(String requestId) {
			return Optional.ofNullable(terminalByRequest.get(requestId))
					.map(result -> new ConversationResult(
							result.conversationId(), result.runId(), result.intent(), result.outcome(),
							result.status(), result.response(), result.events(), true));
		}

		@Override
		public RunHandle start(ConversationRequest request, AgentIntent intent) {
			UUID conversationId = request.conversationId() == null
					? UUID.randomUUID()
					: request.conversationId();
			UUID runId = UUID.randomUUID();
			RunHandle run = new RunHandle(
					conversationId, runId, UUID.randomUUID(), UUID.randomUUID(), intent);
			requestByRun.put(runId, request.requestId());
			eventsByRun.put(runId, new ArrayList<>());
			contextByConversation.computeIfAbsent(conversationId, ignored -> new ArrayList<>())
					.add(new ConversationContextMessage(
							ConversationContextMessage.Role.USER, request.content()));
			return run;
		}

		@Override
		public List<ConversationContextMessage> loadContext(
				UUID conversationId,
				int maxMessages,
				int maxCharacters) {
			requestedMaxMessages = maxMessages;
			requestedMaxCharacters = maxCharacters;
			return List.copyOf(contextByConversation.getOrDefault(conversationId, List.of()));
		}

		@Override
		public void appendEvent(UUID runId, AgentLifecycleEvent event) {
			eventsByRun.get(runId).add(event);
		}

		@Override
		public void appendContentDelta(RunHandle run, AgentLifecycleEvent event) {
			eventsByRun.get(run.runId()).add(event);
		}

		@Override
		public void resetAssistantContent(RunHandle run, AgentLifecycleEvent event) {
			resetCount++;
			eventsByRun.get(run.runId()).add(event);
		}

		@Override
		public List<AgentLifecycleEvent> loadEvents(UUID runId, int afterSequence) {
			return eventsByRun.getOrDefault(runId, List.of()).stream()
					.filter(event -> event.sequence() > afterSequence)
					.toList();
		}

		@Override
		public void complete(
				RunHandle run,
				Outcome outcome,
				String assistantResponse,
				AgentLifecycleEvent terminalEvent) {
			eventsByRun.get(run.runId()).add(terminalEvent);
			contextByConversation.get(run.conversationId()).add(new ConversationContextMessage(
					ConversationContextMessage.Role.ASSISTANT, assistantResponse));
			storeTerminal(run, outcome, Status.COMPLETED, assistantResponse);
		}

		@Override
		public void fail(
				RunHandle run,
				String errorCode,
				String safeAssistantResponse,
				AgentLifecycleEvent terminalEvent) {
			eventsByRun.get(run.runId()).add(terminalEvent);
			storeTerminal(run, Outcome.FAILED, Status.FAILED, safeAssistantResponse);
		}

		@Override
		public int recoverInterruptedRuns(String safeAssistantResponse) {
			return 0;
		}

		private void storeTerminal(
				RunHandle run,
				Outcome outcome,
				Status status,
				String response) {
			terminalByRequest.put(requestByRun.get(run.runId()), new ConversationResult(
					run.conversationId(), run.runId(), run.intent(), outcome, status, response,
					eventsByRun.get(run.runId()), false));
		}
	}
}
