package synvo.workspaceagent;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;
import synvo.workspaceagent.WorkspaceAgentEngine.Activity;
import synvo.workspaceagent.WorkspaceAgentEngine.ActivityBatch;
import synvo.workspaceagent.WorkspaceAgentEngine.ActivityKind;
import synvo.workspaceagent.WorkspaceAgentEngine.EngineStatus;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDecision;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDetail;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionKind;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionRequest;
import synvo.workspaceagent.WorkspaceAgentEngine.Goal;
import synvo.workspaceagent.WorkspaceAgentEngine.GoalCommand;
import synvo.workspaceagent.WorkspaceAgentEngine.RunMode;
import synvo.workspaceagent.WorkspaceAgentEngine.TerminalStatus;
import synvo.workspaceagent.WorkspaceAgentFacade.ActivityView;
import synvo.workspaceagent.WorkspaceAgentFacade.ConversationCommand;
import synvo.workspaceagent.WorkspaceAgentFacade.ConversationObserver;
import synvo.workspaceagent.WorkspaceAgentFacade.InteractionView;
import synvo.workspaceagent.WorkspaceAgentFacade.VisibleMessage;
import synvo.workspaceagent.WorkspaceAgentRepository.DecisionDisposition;
import synvo.workspaceagent.WorkspaceAgentRepository.DecisionResult;
import synvo.workspaceagent.WorkspaceAgentRepository.GoalSnapshot;
import synvo.workspaceagent.WorkspaceAgentRepository.InteractionRecord;
import synvo.workspaceagent.WorkspaceAgentRepository.InteractionStatus;
import synvo.workspaceagent.WorkspaceAgentRepository.OperationRecord;
import synvo.workspaceagent.WorkspaceAgentRepository.OperationStatus;
import synvo.workspaceagent.WorkspaceAgentRepository.OperationType;
import synvo.workspaceagent.WorkspaceAgentRepository.SafeActivity;
import synvo.workspaceagent.WorkspaceAgentRepository.TaskRecord;
import synvo.workspaceagent.WorkspaceRegistry.WorkspaceDefinition;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceAgentFacadeTests {

	private final UUID taskId = UUID.randomUUID();
	private final UUID conversationId = UUID.randomUUID();
	private final UUID runId = UUID.randomUUID();
	private final UUID operationId = UUID.randomUUID();
	private final TaskRecord task = new TaskRecord(
			taskId,
			conversationId,
			"ou-victor",
			"pilot",
			RunMode.READ_ONLY,
			"Pilot task",
			"task-reference",
			false,
			false,
			Instant.now(),
			Instant.now());
	private final OperationRecord operation = new OperationRecord(
			operationId,
			taskId,
			runId,
			"ou-victor",
			"pilot",
			"request-1",
			OperationType.TURN,
			OperationStatus.RUNNING,
			null,
			Instant.now(),
			Instant.now());

	private WorkspaceAgentRepository repository;
	private FakeEngine engine;
	private CapturingPublisher publisher;
	private WorkspaceAgentFacade facade;

	@BeforeEach
	void setUp() {
		repository = mock(WorkspaceAgentRepository.class);
		engine = new FakeEngine();
		publisher = new CapturingPublisher();
		WorkspaceRegistry registry = new WorkspaceRegistry(List.of(new WorkspaceDefinition(
				"pilot",
				"Pilot workspace",
				Path.of("/workspaces/pilot"),
				true,
				true,
				"pilot-repository")));
		facade = new WorkspaceAgentFacade(
				engine,
				repository,
				registry,
				new WorkspaceAgentPolicy("ou-victor", List.of("safe_fixture")),
				publisher,
				"high",
				Duration.ofMinutes(5));
		when(repository.findByConversation("ou-victor", conversationId))
				.thenReturn(Optional.of(task));
		when(repository.findOwnedTask("ou-victor", taskId)).thenReturn(Optional.of(task));
		when(repository.findActiveSystemOperation()).thenReturn(Optional.empty());
		when(repository.hasTerminalTurn(taskId)).thenReturn(true);
		when(repository.startOperation(task, runId, "request-1", OperationType.TURN))
				.thenReturn(operation);
	}

	@Test
	void conversationTaskHandoffExposesOnlyOwnedTaskIdentityWorkspaceNameAndMode() {
		var handoff = facade.conversationTask("ou-victor", conversationId).orElseThrow();

		assertEquals(taskId, handoff.taskId());
		assertEquals("Pilot workspace", handoff.workspaceName());
		assertEquals(RunMode.READ_ONLY, handoff.mode());
		assertFalse(handoff.toString().contains("/workspaces/pilot"));
	}

	@Test
	void conversationStreamsArbitraryFragmentsAndFinishesOneApplicationOperation() {
		engine.batches.add(new ActivityBatch(List.of(
				new Activity(0, ActivityKind.TURN_STARTED, null, false, null, null),
				new Activity(1, ActivityKind.MESSAGE_DELTA, " \n", false, "message", null),
				new Activity(2, ActivityKind.MESSAGE_DELTA, "done", false, "message", null),
				new Activity(3, ActivityKind.TURN_COMPLETED, null, false, null, TerminalStatus.COMPLETED)), true));
		List<String> deltas = new ArrayList<>();

		var outcome = facade.runConversation(
				command(List.of(new VisibleMessage("USER", "Inspect"))),
				observer(deltas),
				() -> false);

		assertEquals(" \ndone", outcome.response());
		assertEquals(TerminalStatus.COMPLETED, outcome.status());
		assertEquals(List.of(" \n", "done"), deltas);
		assertEquals("high", engine.turnInput.reasoningEffort());
		verify(repository).finishOperation(
				operationId, TerminalStatus.COMPLETED, "Codex completed the task.");
		assertEquals(4, publisher.activities.size());
	}

	@Test
	void conversationClearsPreToolNarrationBeforeStreamingTheFinalResult() {
		engine.batches.add(new ActivityBatch(List.of(
				new Activity(0, ActivityKind.TURN_STARTED, null, false, null, null),
				new Activity(1, ActivityKind.MESSAGE_DELTA, "I will inspect the workspace.", false,
						"message", null),
				new Activity(2, ActivityKind.MESSAGE_COMPLETED, "I will inspect the workspace.", false,
						"message", null),
				new Activity(3, ActivityKind.COMMAND_STARTED, null, false, "command", null),
				new Activity(4, ActivityKind.COMMAND_COMPLETED, null, false, "command", null),
				new Activity(5, ActivityKind.MESSAGE_DELTA, "AGENTS.md\nbackend\nfrontend", false,
						"message", null),
				new Activity(6, ActivityKind.MESSAGE_COMPLETED, "AGENTS.md\nbackend\nfrontend", false,
						"message", null),
				new Activity(7, ActivityKind.TURN_COMPLETED, null, false, null,
						TerminalStatus.COMPLETED)), true));
		List<String> presentationEvents = new ArrayList<>();

		var outcome = facade.runConversation(
				command(List.of(new VisibleMessage("USER", "Inspect"))),
				new ConversationObserver() {
					@Override
					public void onActivity(ActivityView activity) {
					}

					@Override
					public void onMessageDelta(String delta) {
						presentationEvents.add("delta:" + delta);
					}

					@Override
					public void onMessageReset() {
						presentationEvents.add("reset");
					}
				},
				() -> false);

		assertEquals("AGENTS.md\nbackend\nfrontend", outcome.response());
		assertEquals(List.of(
				"delta:I will inspect the workspace.",
				"reset",
				"delta:AGENTS.md\nbackend\nfrontend"), presentationEvents);
	}

	@Test
	void conversationPreservesDistinctMessagesWhenNoToolWorkFollows() {
		engine.batches.add(new ActivityBatch(List.of(
				new Activity(0, ActivityKind.TURN_STARTED, null, false, null, null),
				new Activity(1, ActivityKind.MESSAGE_DELTA, "First paragraph.", false,
						"message-1", null),
				new Activity(2, ActivityKind.MESSAGE_COMPLETED, "First paragraph.", false,
						"message-1", null),
				new Activity(3, ActivityKind.MESSAGE_DELTA, "Second paragraph.", false,
						"message-2", null),
				new Activity(4, ActivityKind.MESSAGE_COMPLETED, "Second paragraph.", false,
						"message-2", null),
				new Activity(5, ActivityKind.TURN_COMPLETED, null, false, null,
						TerminalStatus.COMPLETED)), true));
		List<String> deltas = new ArrayList<>();

		var outcome = facade.runConversation(
				command(List.of(new VisibleMessage("USER", "Explain"))),
				observer(deltas),
				() -> false);

		assertEquals("First paragraph.\n\nSecond paragraph.", outcome.response());
		assertEquals(List.of("First paragraph.", "\n\nSecond paragraph."), deltas);
	}

	@Test
	void runnerBackedTaskReadsResumeThePersistedThreadAfterRunnerRestart() {
		facade.inventory("ou-victor", taskId);
		facade.goal("ou-victor", taskId);

		assertEquals(List.of("resume", "inventory", "resume", "goal"),
				engine.taskReadLifecycle);
	}

	@Test
	void goalCommandsRemainOwnerBoundProductActions() {
		engine.currentGoal = Optional.of(new Goal(
				"Provider-rewritten objective", "active", 12, 3));
		ArgumentCaptor<GoalSnapshot> snapshot = ArgumentCaptor.forClass(GoalSnapshot.class);

		facade.setGoal("ou-victor", taskId, "Maintain verified reports", GoalCommand.RESUME);

		assertEquals("task-reference", engine.goalTaskReference);
		assertEquals("Maintain verified reports", engine.goalObjective);
		assertEquals(GoalCommand.RESUME, engine.goalCommand);
		verify(repository).saveGoalSnapshot(eq(taskId), snapshot.capture());
		assertEquals("Maintain verified reports", snapshot.getValue().objective());
		assertEquals("active", snapshot.getValue().status());
		assertEquals(12, snapshot.getValue().tokensUsed());
		assertEquals(3, snapshot.getValue().timeUsedSeconds());
	}

	@Test
	void runtimeGoalUpdatesCannotRewriteTheEmployeeSavedObjective() {
		var saved = new GoalSnapshot(
				"Maintain verified reports", "active", 0, 0, Instant.now());
		when(repository.findGoalSnapshot(taskId)).thenReturn(Optional.of(saved));
		engine.currentGoal = Optional.of(new Goal(
				"Provider-rewritten objective", "complete", 321, 9));
		ArgumentCaptor<GoalSnapshot> snapshot = ArgumentCaptor.forClass(GoalSnapshot.class);

		Goal goal = facade.goal("ou-victor", taskId).orElseThrow();

		assertEquals("Maintain verified reports", goal.objective());
		assertEquals("complete", goal.status());
		assertEquals(321, goal.tokensUsed());
		assertEquals(9, goal.timeUsedSeconds());
		verify(repository).saveGoalSnapshot(eq(taskId), snapshot.capture());
		assertEquals("Maintain verified reports", snapshot.getValue().objective());
		assertEquals("complete", snapshot.getValue().status());
		assertEquals(321, snapshot.getValue().tokensUsed());
		assertEquals(9, snapshot.getValue().timeUsedSeconds());
	}

	@Test
	void completedGoalPresentationSurvivesAnEmptyRuntimeGoal() {
		var snapshot = new GoalSnapshot(
				"Maintain verified reports", "complete", 321, 9, Instant.now());
		when(repository.findGoalSnapshot(taskId)).thenReturn(Optional.of(snapshot));

		Goal goal = facade.goal("ou-victor", taskId).orElseThrow();

		assertEquals("Maintain verified reports", goal.objective());
		assertEquals("complete", goal.status());
		assertEquals(321, goal.tokensUsed());
		assertEquals(9, goal.timeUsedSeconds());
	}

	@Test
	void clearingGoalRemovesProviderAndPresentationState() {
		facade.clearGoal("ou-victor", taskId);

		assertEquals("task-reference", engine.clearedGoalTaskReference);
		verify(repository).clearGoalSnapshot(taskId);
	}

	@Test
	void accountRequirementPromotesHealthyRunnerToAuthenticationRequired() {
		engine.authenticationRequired = true;

		var status = facade.status("ou-victor");

		assertEquals("AUTHENTICATION_REQUIRED", status.state());
		assertEquals("gpt-5.6-sol", status.model());
		assertTrue(status.account().authenticationRequired());
	}

	@Test
	void recoveryAndProtocolStatesRemainDistinctAtTheH5Boundary() {
		engine.engineStatus = EngineStatus.RECOVERING;
		assertEquals("RECOVERING", facade.status("ou-victor").state());

		engine.engineStatus = EngineStatus.PROTOCOL_INCOMPATIBLE;
		assertEquals("PROTOCOL_INCOMPATIBLE", facade.status("ou-victor").state());
	}

	@Test
	void taskListOmitsPersistedTasksWhoseWorkspaceIsTemporarilyUnconfigured() {
		TaskRecord unavailable = new TaskRecord(
				UUID.randomUUID(),
				UUID.randomUUID(),
				"ou-victor",
				"unconfigured",
				RunMode.READ_ONLY,
				"Unavailable workspace task",
				"unavailable-task-reference",
				false,
				false,
				Instant.now(),
				Instant.now());
		when(repository.listOwnedTasks("ou-victor", false, null, 100))
				.thenReturn(List.of(unavailable, task));

		var tasks = facade.listTasks("ou-victor", false, null);

		assertEquals(1, tasks.size());
		assertEquals(taskId, tasks.getFirst().taskId());
	}

	@Test
	void firstEngineTurnRehydratesBoundedVisibleContextExactlyOnce() {
		when(repository.hasTerminalTurn(taskId)).thenReturn(false);
		engine.batches.add(completed(0, "answer"));

		facade.runConversation(
				command(List.of(
						new VisibleMessage("USER", "Earlier question"),
						new VisibleMessage("ASSISTANT", "Earlier answer"),
						new VisibleMessage("USER", "Inspect"))),
				observer(new ArrayList<>()),
				() -> false);

		assertTrue(engine.turnInput.text().contains("Earlier question"));
		assertTrue(engine.turnInput.text().contains("Earlier answer"));
		assertEquals(1, engine.startedTurns);
	}

	@Test
	void missingEngineThreadIsReconstructedOnceBeforeTheNewTurn() {
		TaskRecord replacement = new TaskRecord(
				taskId,
				conversationId,
				"ou-victor",
				"pilot",
				RunMode.READ_ONLY,
				"Pilot task",
				"new-task-reference",
				false,
				false,
				Instant.now(),
				Instant.now());
		engine.resumeUnavailable = true;
		when(repository.replaceTaskReference(
				"ou-victor", taskId, "task-reference", "new-task-reference"))
				.thenReturn(replacement);
		when(repository.startOperation(replacement, runId, "request-1", OperationType.TURN))
				.thenReturn(operation);
		engine.batches.add(completed(0, "reconstructed"));

		facade.runConversation(
				command(List.of(
						new VisibleMessage("USER", "Earlier question"),
						new VisibleMessage("ASSISTANT", "Earlier answer"),
						new VisibleMessage("USER", "Inspect"))),
				observer(new ArrayList<>()),
				() -> false);

		assertEquals(List.of("new-task-reference"), engine.createdTaskReferences);
		assertEquals("new-task-reference", engine.startedTaskReference);
		assertTrue(engine.turnInput.text().contains("Earlier answer"));
		verify(repository).replaceTaskReference(
				"ou-victor", taskId, "task-reference", "new-task-reference");
	}

	@Test
	void failedReconstructionMappingDeletesTheReplacementEngineThread() {
		engine.resumeUnavailable = true;
		when(repository.replaceTaskReference(
				"ou-victor", taskId, "task-reference", "new-task-reference"))
				.thenThrow(new DuplicateKeyException("mapping changed"));

		assertThrows(DuplicateKeyException.class, () -> facade.runConversation(
				command(List.of(new VisibleMessage("USER", "Inspect"))),
				observer(new ArrayList<>()),
				() -> false));

		assertEquals(List.of("new-task-reference"), engine.deletedTaskReferences);
		assertEquals(0, engine.startedTurns);
	}

	@Test
	void h5DecisionIsOwnerBoundPolicyBoundAndForwardedOnce() throws Exception {
		UUID interactionId = UUID.randomUUID();
		InteractionRequest request = mcpRequest(Instant.now().plusSeconds(60));
		InteractionRecord pending = interaction(
				interactionId, InteractionStatus.PENDING, null, request.availableDecisions());
		AtomicReference<InteractionRecord> stored = new AtomicReference<>(pending);
		when(repository.recordInteraction(
				any(), anyString(), any(), anyString(), anyString(), anyString(), any(), any()))
				.thenReturn(pending);
		when(repository.findOwnedInteraction("ou-victor", interactionId))
				.thenAnswer(ignored -> Optional.of(stored.get()));
		when(repository.decideInteraction(
				eq("ou-victor"), eq(interactionId), any(), anyString(), any()))
				.thenAnswer(invocation -> {
					InteractionDecision decision = invocation.getArgument(2);
					InteractionRecord decided = interaction(
							interactionId, InteractionStatus.DECIDED, decision,
							request.availableDecisions());
					stored.set(decided);
					return new DecisionResult(DecisionDisposition.APPLIED, decided);
				});
		engine.pending = List.of(request);
		engine.blockTerminalUntilDecision = true;
		engine.batches.add(new ActivityBatch(List.of(
				new Activity(0, ActivityKind.MCP_STARTED, null, false, "mcp", null)), false));
		engine.batches.add(completed(1, "approved result"));

		CompletableFuture<?> run = CompletableFuture.runAsync(() -> facade.runConversation(
				command(List.of(new VisibleMessage("USER", "Run tests"))),
				observer(new ArrayList<>()),
				() -> false));
		assertTrue(publisher.interactionLatch.await(2, TimeUnit.SECONDS));
		verify(repository).recordInteraction(
				eq(operation),
				eq("source-interaction"),
				eq(InteractionKind.MCP_ELICITATION),
				eq("MCP request"),
				eq("Codex requests your input for an allowlisted MCP request."),
				eq("allowlisted MCP response"),
				any(),
				any());
		InteractionView detail = facade.interactionDetail("ou-victor", interactionId);
		assertEquals("safe_fixture", detail.detail().mcpServer());
		assertThrows(WorkspaceAgentException.class, () -> facade.interactionDetail(
				"ou-other", interactionId));

		InteractionView decided = facade.decideInteraction(
				"ou-victor",
				interactionId,
				InteractionDecision.APPROVE_ONCE,
				Map.of());
		assertEquals(InteractionDecision.APPROVE_ONCE, decided.decision());
		run.get(2, TimeUnit.SECONDS);
		assertEquals(1, engine.decisions.size());
		assertEquals(InteractionDecision.APPROVE_ONCE, engine.decisions.getFirst());
	}

	@Test
	void expiredH5DecisionNeverReachesTheEngine() throws Exception {
		UUID interactionId = UUID.randomUUID();
		Instant requestExpiresAt = Instant.now().plusSeconds(60);
		Instant expiredAt = Instant.now().minusSeconds(1);
		InteractionRequest request = mcpRequest(requestExpiresAt);
		InteractionRecord pending = interaction(
				interactionId, InteractionStatus.PENDING, null,
				request.availableDecisions(), requestExpiresAt);
		InteractionRecord expiredPending = interaction(
				interactionId, InteractionStatus.PENDING, null,
				request.availableDecisions(), expiredAt);
		InteractionRecord expired = interaction(
				interactionId, InteractionStatus.EXPIRED, null,
				request.availableDecisions(), expiredAt);
		AtomicReference<InteractionRecord> stored = new AtomicReference<>(pending);
		when(repository.recordInteraction(
				any(), anyString(), any(), anyString(), anyString(), anyString(), any(), any()))
				.thenReturn(pending);
		when(repository.findOwnedInteraction("ou-victor", interactionId))
				.thenAnswer(ignored -> Optional.of(stored.get()));
		when(repository.decideInteraction(
				eq("ou-victor"), eq(interactionId), any(), anyString(), any()))
				.thenReturn(new DecisionResult(DecisionDisposition.EXPIRED, expired));
		when(repository.findOperation(operationId)).thenReturn(Optional.of(new OperationRecord(
				operationId,
				taskId,
				runId,
				"ou-victor",
				"pilot",
				"request-1",
				OperationType.TURN,
				OperationStatus.WAITING_FOR_INTERACTION,
				"operation-reference",
				Instant.now(),
				Instant.now())));
		engine.pending = List.of(request);
		engine.blockTerminalUntilStop = true;
		engine.batches.add(new ActivityBatch(List.of(
				new Activity(0, ActivityKind.MCP_STARTED, null, false, "mcp", null)), false));
		engine.batches.add(new ActivityBatch(List.of(
				new Activity(1, ActivityKind.TURN_COMPLETED, null, false, null,
						TerminalStatus.STOPPED)), true));

		CompletableFuture<?> run = CompletableFuture.runAsync(() -> facade.runConversation(
				command(List.of(new VisibleMessage("USER", "Run tests"))),
				observer(new ArrayList<>()),
				() -> false));
		assertTrue(publisher.interactionLatch.await(2, TimeUnit.SECONDS));
		stored.set(expiredPending);

		WorkspaceAgentException failure = assertThrows(
				WorkspaceAgentException.class,
				() -> facade.decideInteraction(
						"ou-victor", interactionId,
						InteractionDecision.APPROVE_ONCE, Map.of()));

		assertEquals(WorkspaceAgentException.Code.INTERACTION_EXPIRED, failure.code());
		assertTrue(engine.decisions.isEmpty());
		assertTrue(facade.stop("ou-victor", operationId));
		run.get(2, TimeUnit.SECONDS);
	}

	@Test
	void commandElevationIsDeclinedWithoutPublishingAnH5Interaction() throws Exception {
		UUID interactionId = UUID.randomUUID();
		InteractionRequest request = new InteractionRequest(
				"source-command",
				"pilot",
				InteractionKind.COMMAND_APPROVAL,
				"shell command",
				"Request broader command authority",
				List.of(
						InteractionDecision.APPROVE_ONCE,
						InteractionDecision.DECLINE),
				new InteractionDetail(
						"curl https://example.invalid", ".", List.of(),
						null, null, null, null),
				Instant.now().plusSeconds(60));
		InteractionRecord declined = new InteractionRecord(
				interactionId,
				operationId,
				taskId,
				"ou-victor",
				"pilot",
				"source-command",
				InteractionKind.COMMAND_APPROVAL,
				"shell command",
				"Codex requests permission to run a workspace command.",
				"workspace command",
				List.of(InteractionDecision.DECLINE),
				InteractionStatus.PENDING,
				null,
				null,
				request.expiresAt(),
				Instant.now(),
				null);
		when(repository.recordInteraction(
				any(), anyString(), any(), anyString(), anyString(), anyString(), any(), any()))
				.thenReturn(declined);
		when(repository.decideInteraction(
				eq("ou-victor"), eq(interactionId), eq(InteractionDecision.DECLINE),
				eq("policy"), any()))
				.thenReturn(new DecisionResult(DecisionDisposition.APPLIED, declined));
		engine.pending = List.of(request);
		engine.batches.add(new ActivityBatch(List.of(
				new Activity(0, ActivityKind.COMMAND_STARTED, null, false, "command", null)), false));
		engine.batches.add(completed(1, "authority remained blocked"));

		var outcome = facade.runConversation(
				command(List.of(new VisibleMessage("USER", "Use the network"))),
				observer(new ArrayList<>()),
				() -> false);

		assertEquals(TerminalStatus.COMPLETED, outcome.status());
		assertEquals(List.of(InteractionDecision.DECLINE), engine.decisions);
		assertFalse(publisher.interactionLatch.await(50, TimeUnit.MILLISECONDS));
		verify(repository).recordInteraction(
				eq(operation), eq("source-command"), eq(InteractionKind.COMMAND_APPROVAL),
				anyString(), anyString(), anyString(),
				eq(List.of(InteractionDecision.DECLINE)), any());
	}

	@Test
	void databaseBusyStopsBeforeTheEngineAndReturnsDeterministicBusy() {
		when(repository.startOperation(task, runId, "request-1", OperationType.TURN))
				.thenThrow(new DuplicateKeyException("global lease"));

		WorkspaceAgentException failure = assertThrows(
				WorkspaceAgentException.class,
				() -> facade.runConversation(
						command(List.of(new VisibleMessage("USER", "Inspect"))),
						observer(new ArrayList<>()),
						() -> false));

		assertEquals(WorkspaceAgentException.Code.BUSY, failure.code());
		assertEquals(0, engine.startedTurns);
		verify(repository, never()).bindOperationReference(any(), anyString());
	}

	@Test
	void deletingTaskStopsPendingInteractionBeforeDeletingSynvoAndEngineState() throws Exception {
		UUID interactionId = UUID.randomUUID();
		InteractionRequest request = mcpRequest(Instant.now().plusSeconds(60));
		when(repository.recordInteraction(
				any(), anyString(), any(), anyString(), anyString(), anyString(), any(), any()))
				.thenReturn(interaction(
						interactionId, InteractionStatus.PENDING, null,
						request.availableDecisions()));
		when(repository.findActiveSystemOperation()).thenReturn(Optional.of(operation));
		when(repository.deleteOwnedTask("ou-victor", taskId)).thenReturn(true);
		engine.pending = List.of(request);
		engine.blockTerminalUntilStop = true;
		engine.batches.add(new ActivityBatch(List.of(
				new Activity(0, ActivityKind.MCP_STARTED, null, false, "mcp", null)), false));
		engine.batches.add(new ActivityBatch(List.of(
				new Activity(1, ActivityKind.TURN_COMPLETED, null, false, null,
						TerminalStatus.STOPPED)), true));

		CompletableFuture<?> run = CompletableFuture.runAsync(() -> facade.runConversation(
				command(List.of(new VisibleMessage("USER", "Run tests"))),
				observer(new ArrayList<>()),
				() -> false));
		assertTrue(publisher.interactionLatch.await(2, TimeUnit.SECONDS));

		facade.deleteTask("ou-victor", taskId);
		run.get(2, TimeUnit.SECONDS);

		assertTrue(engine.stopped);
		assertEquals(List.of("task-reference"), engine.deletedTaskReferences);
		verify(repository).finishOperation(
				operationId, TerminalStatus.STOPPED, "Codex task stopped.");
		verify(repository).deleteOwnedTask("ou-victor", taskId);
	}

	@Test
	void completedActivityReplaysFromPersistenceAfterBackendRestart() {
		OperationRecord completedOperation = new OperationRecord(
				operationId,
				taskId,
				runId,
				"ou-victor",
				"pilot",
				"request-1",
				OperationType.TURN,
				OperationStatus.COMPLETED,
				"operation-reference",
				Instant.now(),
				Instant.now());
		when(repository.findOperation(operationId)).thenReturn(Optional.of(completedOperation));
		when(repository.loadActivity(operationId, 2)).thenReturn(List.of(new SafeActivity(
				3,
				ActivityKind.TURN_COMPLETED,
				"Codex completed the task.",
				true,
				TerminalStatus.COMPLETED,
				Instant.now())));

		List<ActivityView> replay = facade.activity("ou-victor", operationId, 2);

		assertEquals(1, replay.size());
		assertEquals(3, replay.getFirst().sequence());
		assertEquals(TerminalStatus.COMPLETED, replay.getFirst().terminalStatus());
	}

	private ConversationCommand command(List<VisibleMessage> context) {
		return new ConversationCommand(
				"ou-victor", conversationId, runId, "request-1", "Inspect",
				context, null, null);
	}

	private static ConversationObserver observer(List<String> deltas) {
		return new ConversationObserver() {
			@Override
			public void onActivity(ActivityView activity) {
			}

			@Override
			public void onMessageDelta(String delta) {
				deltas.add(delta);
			}

			@Override
			public void onMessageReset() {
			}
		};
	}

	private ActivityBatch completed(long firstSequence, String answer) {
		return new ActivityBatch(List.of(
				new Activity(firstSequence, ActivityKind.MESSAGE_DELTA, answer, false, "message", null),
				new Activity(firstSequence + 1, ActivityKind.TURN_COMPLETED, null, false, null,
						TerminalStatus.COMPLETED)), true);
	}

	private InteractionRecord interaction(
			UUID interactionId,
			InteractionStatus status,
			InteractionDecision decision,
			List<InteractionDecision> available) {
		return interaction(
				interactionId, status, decision, available,
				Instant.now().plusSeconds(60));
	}

	private InteractionRecord interaction(
			UUID interactionId,
			InteractionStatus status,
			InteractionDecision decision,
			List<InteractionDecision> available,
			Instant expiresAt) {
		return new InteractionRecord(
				interactionId,
				operationId,
				taskId,
				"ou-victor",
				"pilot",
				"source-interaction",
				InteractionKind.MCP_ELICITATION,
				"MCP request",
				"Codex requests your input for an allowlisted MCP request.",
				"allowlisted MCP response",
				available,
				status,
				decision,
				decision == null ? null : "once",
				expiresAt,
				Instant.now(),
				decision == null ? null : Instant.now());
	}

	private static InteractionRequest mcpRequest(Instant expiresAt) {
		return new InteractionRequest(
				"source-interaction",
				"pilot",
				InteractionKind.MCP_ELICITATION,
				"MCP request",
				"Confirm the allowlisted fixture request",
				List.of(InteractionDecision.APPROVE_ONCE, InteractionDecision.DECLINE),
				new InteractionDetail(
						null, null, List.of(), "safe_fixture", "write", "Continue?", "form"),
				expiresAt);
	}

	private static final class CapturingPublisher implements WorkspaceAgentEventPublisher {

		private final List<ActivityView> activities = new ArrayList<>();
		private final CountDownLatch interactionLatch = new CountDownLatch(1);

		@Override
		public synchronized void publish(UUID operationId, ActivityView activity) {
			activities.add(activity);
		}

		@Override
		public void interactionRequired(UUID operationId, InteractionView interaction) {
			interactionLatch.countDown();
		}
	}

	private static final class FakeEngine implements WorkspaceAgentEngine {

		private final List<ActivityBatch> batches = new ArrayList<>();
		private final List<InteractionDecision> decisions = new ArrayList<>();
		private final List<String> deletedTaskReferences = new ArrayList<>();
		private final List<String> taskReadLifecycle = new ArrayList<>();
		private final List<String> createdTaskReferences = new ArrayList<>();
		private volatile List<InteractionRequest> pending = List.of();
		private volatile boolean blockTerminalUntilDecision;
		private volatile boolean blockTerminalUntilStop;
		private volatile boolean stopped;
		private volatile boolean authenticationRequired;
		private volatile EngineStatus engineStatus = EngineStatus.READY;
		private volatile boolean resumeUnavailable;
		private volatile int startedTurns;
		private volatile String startedTaskReference;
		private volatile TurnInput turnInput;
		private volatile String goalTaskReference;
		private volatile String goalObjective;
		private volatile GoalCommand goalCommand;
		private volatile String clearedGoalTaskReference;
		private volatile Optional<Goal> currentGoal = Optional.empty();
		private int nextBatch;

		@Override
		public EngineStatus status() {
			return engineStatus;
		}

		@Override
		public Capabilities capabilities() {
			return new Capabilities(
					"0.148.0", "gpt-5.6-sol", List.of("low", "high"), List.of("shell_tool"));
		}

		@Override
		public AccountStatus account() {
			return new AccountStatus(
					"chatgpt", authenticationRequired, "pro", 1.0, Instant.now());
		}

		@Override
		public TaskHandle createTask(WorkspaceTarget workspace, RunMode mode) {
			createdTaskReferences.add("new-task-reference");
			return new TaskHandle("new-task-reference", "gpt-5.6-sol");
		}

		@Override
		public TaskHandle forkTask(String taskReference, WorkspaceTarget workspace) {
			return new TaskHandle("fork-reference", "gpt-5.6-sol");
		}

		@Override
		public void resumeTask(String taskReference, WorkspaceTarget workspace) {
			taskReadLifecycle.add("resume");
			if (resumeUnavailable) {
				throw new WorkspaceAgentException(WorkspaceAgentException.Code.NOT_FOUND);
			}
		}

		@Override
		public void renameTask(String taskReference, String title) {
		}

		@Override
		public void archiveTask(String taskReference) {
		}

		@Override
		public void unarchiveTask(String taskReference) {
		}

		@Override
		public void deleteTask(String taskReference) {
			deletedTaskReferences.add(taskReference);
		}

		@Override
		public OperationHandle startTurn(
				String taskReference,
				WorkspaceTarget workspace,
				RunMode mode,
				TurnInput input) {
			startedTurns++;
			startedTaskReference = taskReference;
			turnInput = input;
			return new OperationHandle("operation-reference");
		}

		@Override
		public OperationHandle startReview(
				String taskReference,
				WorkspaceTarget workspace,
				ReviewTarget target) {
			return new OperationHandle("review-reference");
		}

		@Override
		public ActivityBatch waitForActivity(String operationReference, long afterSequence) {
			if (blockTerminalUntilDecision && nextBatch > 0) {
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
				while (decisions.isEmpty() && System.nanoTime() < deadline) {
					Thread.onSpinWait();
				}
			}
			if (blockTerminalUntilStop && nextBatch > 0) {
				long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
				while (!stopped && System.nanoTime() < deadline) {
					Thread.onSpinWait();
				}
			}
			return batches.get(nextBatch++);
		}

		@Override
		public List<InteractionRequest> pendingInteractions(String operationReference) {
			return decisions.isEmpty() ? pending : List.of();
		}

		@Override
		public void decideInteraction(
				String operationReference,
				String interactionReference,
				InteractionDecision decision,
				Map<String, String> formValues) {
			decisions.add(decision);
		}

		@Override
		public void steer(String operationReference, String text) {
		}

		@Override
		public void stop(String operationReference) {
			stopped = true;
		}

		@Override
		public Inventory inventory(String taskReference, WorkspaceTarget workspace) {
			taskReadLifecycle.add("inventory");
			return new Inventory(List.of(), List.of());
		}

		@Override
		public Optional<Goal> goal(String taskReference) {
			taskReadLifecycle.add("goal");
			return currentGoal;
		}

		@Override
		public void setGoal(String taskReference, String objective, GoalCommand command) {
			goalTaskReference = taskReference;
			goalObjective = objective;
			goalCommand = command;
		}

		@Override
		public void clearGoal(String taskReference) {
			clearedGoalTaskReference = taskReference;
		}
	}
}
