package synvo.persistence;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import synvo.TestcontainersConfiguration;
import synvo.agent.AgentIntent;
import synvo.agent.AgentLifecycleEvent;
import synvo.agent.AgentLifecycleEvent.ActionHandoff;
import synvo.agent.ConversationRequest;
import synvo.agent.ConversationStore;
import synvo.workspaceagent.WorkspaceAgentEngine.ActivityKind;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDecision;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionKind;
import synvo.workspaceagent.WorkspaceAgentEngine.RunMode;
import synvo.workspaceagent.WorkspaceAgentEngine.TerminalStatus;
import synvo.workspaceagent.WorkspaceAgentRepository;
import synvo.workspaceagent.WorkspaceAgentRepository.DecisionDisposition;
import synvo.workspaceagent.WorkspaceAgentRepository.GoalSnapshot;
import synvo.workspaceagent.WorkspaceAgentRepository.OperationStatus;
import synvo.workspaceagent.WorkspaceAgentRepository.OperationType;
import synvo.workspaceagent.WorkspaceAgentRepository.SafeActivity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@ActiveProfiles("test")
class WorkspaceAgentPersistenceTests {

	@Autowired
	private WorkspaceAgentRepository repository;

	@Autowired
	private ConversationStore conversationStore;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	@Test
	void workspaceAgentSchemaContainsOnlyBoundedSynvoOwnedState() {
		for (String table : List.of(
				"workspace_agent_task",
				"workspace_agent_operation",
				"workspace_agent_activity",
				"workspace_agent_interaction")) {
			assertTrue(Boolean.TRUE.equals(jdbcTemplate.queryForObject(
					"select to_regclass('public." + table + "') is not null", Boolean.class)));
		}

		List<String> columns = jdbcTemplate.queryForList("""
				SELECT column_name
				FROM information_schema.columns
				WHERE table_schema = 'public'
				  AND table_name LIKE 'workspace_agent_%'
				""", String.class);
		String names = String.join(" ", columns).toLowerCase(Locale.ROOT);
		for (String forbidden : List.of(
				"workspace_path", "credential", "access_token", "refresh_token", "prompt", "command",
				"output", "diff", "file_content", "reasoning", "protocol")) {
			assertFalse(names.contains(forbidden), () -> "Schema persists forbidden field: " + forbidden);
		}
	}

	@Test
	void lastGoalPresentationIsDurableAndExplicitlyClearable() {
		var task = repository.createTask(
				"ou-goal-owner", "sales", RunMode.WORKSPACE_WRITE,
				"Sales report", "task-ref-goal");
		var snapshot = new GoalSnapshot(
				"Maintain a verified Sales report", "complete", 321, 9, Instant.now());

		repository.saveGoalSnapshot(task.taskId(), snapshot);

		var stored = repository.findGoalSnapshot(task.taskId()).orElseThrow();
		assertEquals(snapshot.objective(), stored.objective());
		assertEquals("complete", stored.status());
		assertEquals(321, stored.tokensUsed());
		assertEquals(9, stored.timeUsedSeconds());

		repository.clearGoalSnapshot(task.taskId());
		assertTrue(repository.findGoalSnapshot(task.taskId()).isEmpty());
	}

	@Test
	void tasksAreOwnerScopedWorkspaceBoundAndCascadeThroughConversationDeletion() {
		var task = repository.createTask(
				"ou-workspace-owner", "pilot", RunMode.READ_ONLY,
				"Inspect the pilot", "task-ref-owner");

		assertEquals(task, repository.findOwnedTask("ou-workspace-owner", task.taskId()).orElseThrow());
		assertTrue(repository.findOwnedTask("ou-other", task.taskId()).isEmpty());
		assertEquals("pilot", repository.findByConversation(
				"ou-workspace-owner", task.conversationId()).orElseThrow().workspaceId());
		assertEquals(RunMode.WORKSPACE_WRITE, repository.updateMode(
				"ou-workspace-owner", task.taskId(), RunMode.WORKSPACE_WRITE).mode());
		assertTrue(repository.updatePinned("ou-workspace-owner", task.taskId(), true).pinned());
		assertTrue(repository.updateArchived("ou-workspace-owner", task.taskId(), true).archived());
		assertEquals(1, repository.listOwnedTasks(
				"ou-workspace-owner", true, "pilot", 50).size());
		assertEquals("Renamed", repository.updateTitle(
				"ou-workspace-owner", task.taskId(), "Renamed").title());

		assertTrue(repository.deleteOwnedTask("ou-workspace-owner", task.taskId()));
		assertTrue(repository.findOwnedTask("ou-workspace-owner", task.taskId()).isEmpty());
		assertEquals(0, jdbcTemplate.queryForObject(
				"select count(*) from conversation where conversation_id = ?",
				Integer.class,
				task.conversationId()));
	}

	@Test
	void oneGlobalOperationSafeReplayAndInteractionDecisionAreDurableAndIdempotent() {
		var first = repository.createTask(
				"ou-global-owner", "pilot", RunMode.READ_ONLY, "First", "task-ref-first");
		var second = repository.createTask(
				"ou-global-owner", "pilot", RunMode.READ_ONLY, "Second", "task-ref-second");
		var operation = repository.startOperation(
				first, null, "review-request-first", OperationType.REVIEW);
		assertThrows(DataIntegrityViolationException.class, () -> repository.startOperation(
				second, null, "review-request-second", OperationType.REVIEW));

		repository.bindOperationReference(operation.operationId(), "normalized-op-ref");
		repository.appendActivity(operation.operationId(), new SafeActivity(
				0, ActivityKind.COMMAND_STARTED, "Running a workspace command", false, null, Instant.now()));
		repository.appendActivity(operation.operationId(), new SafeActivity(
				0, ActivityKind.COMMAND_STARTED, "Duplicate replay", false, null, Instant.now()));
		repository.appendActivity(operation.operationId(), new SafeActivity(
				1, ActivityKind.TURN_COMPLETED, "Codex task finished", true,
				TerminalStatus.COMPLETED, Instant.now()));
		assertEquals(2, repository.loadActivity(operation.operationId(), -1).size());

		var pending = repository.recordInteraction(
				repository.findActiveOperation(operation.operationId()).orElseThrow(),
				"normalized-interaction-ref",
				InteractionKind.COMMAND_APPROVAL,
				"shell command",
				"Run focused tests",
				"workspace command",
				List.of(InteractionDecision.APPROVE_ONCE, InteractionDecision.DECLINE),
				Instant.now().plusSeconds(60));
		assertEquals(OperationStatus.WAITING_FOR_INTERACTION,
				repository.findActiveOperation(operation.operationId()).orElseThrow().status());
		assertEquals(1, repository.listPendingInteractions(
				"ou-global-owner", first.taskId()).size());
		assertEquals(DecisionDisposition.APPLIED, repository.decideInteraction(
				"ou-global-owner", pending.interactionId(),
				InteractionDecision.APPROVE_ONCE, "once", Instant.now()).disposition());
		assertEquals(OperationStatus.RUNNING,
				repository.findActiveOperation(operation.operationId()).orElseThrow().status());
		assertEquals(DecisionDisposition.ALREADY_APPLIED, repository.decideInteraction(
				"ou-global-owner", pending.interactionId(),
				InteractionDecision.APPROVE_ONCE, "once", Instant.now()).disposition());
		assertEquals(DecisionDisposition.CONFLICT, repository.decideInteraction(
				"ou-global-owner", pending.interactionId(),
				InteractionDecision.DECLINE, "once", Instant.now()).disposition());

		repository.finishOperation(operation.operationId(), TerminalStatus.COMPLETED, "Review completed");
		assertTrue(repository.findActiveSystemOperation().isEmpty());
		assertEquals(operation.operationId(), repository.findOperation(
				operation.operationId()).orElseThrow().operationId());
		assertEquals(TerminalStatus.COMPLETED, repository.loadActivity(
				operation.operationId(), 0).getFirst().terminalStatus());
		var released = repository.startOperation(
				second, null, "review-request-after-release", OperationType.REVIEW);
		assertNotNull(released);
		repository.finishOperation(
				released.operationId(), TerminalStatus.COMPLETED, "Review completed");
	}

	@Test
	void restartRecoveryTerminatesOperationAndPendingInteractionExactlyOnce() {
		ConversationStore.RunHandle run = conversationStore.start(
				new ConversationRequest(
						"workspace-recovery-conversation", null, "ou-recovery", "Inspect"),
				AgentIntent.DIRECT_ANSWER);
		var task = repository.attachTask(
				"ou-recovery", run.conversationId(), "pilot", RunMode.READ_ONLY,
				"task-ref-recovery");
		var operation = repository.startOperation(
				task, run.runId(), "operation-recovery", OperationType.TURN);
		var interaction = repository.recordInteraction(
				operation,
				"interaction-recovery",
				InteractionKind.FILE_CHANGE_APPROVAL,
				"file change",
				"Update one file",
				"workspace files",
				List.of(InteractionDecision.APPROVE_ONCE, InteractionDecision.DECLINE),
				Instant.now().plusSeconds(60));
		conversationStore.appendEvent(run.runId(), AgentLifecycleEvent.actionRequired(
				1,
				new ActionHandoff(
						task.taskId(),
						interaction.interactionId(),
						"file change",
						"Pilot workspace",
						"Update one file",
						"workspace files")));
		AgentLifecycleEvent replayed = conversationStore.loadEvents(run.runId(), 0).getFirst();
		assertEquals(AgentLifecycleEvent.State.ACTION_REQUIRED, replayed.state());
		assertEquals(interaction.interactionId(), replayed.actionHandoff().interactionId());

		assertEquals(1, repository.recoverInterruptedOperations());
		assertEquals(0, repository.recoverInterruptedOperations());
		assertTrue(repository.findActiveSystemOperation().isEmpty());
		assertTrue(repository.listPendingInteractions("ou-recovery", task.taskId()).isEmpty());
	}

	@Test
	void aPopulatedV4SchemaMigratesToLatestWithoutChangingExistingRows() throws Exception {
		String schema = "phase3_upgrade_" + UUID.randomUUID().toString().replace("-", "");
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("CREATE SCHEMA " + schema);
		}
		Flyway v4 = Flyway.configure()
				.dataSource(dataSource)
				.schemas(schema)
				.defaultSchema(schema)
				.locations("classpath:db/migration")
				.target("4")
				.load();
		v4.migrate();
		UUID conversationId = UUID.randomUUID();
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("SET search_path TO " + schema);
			statement.execute("""
					INSERT INTO conversation (
					    conversation_id, owner_open_id, title, created_at, updated_at
					) VALUES ('%s', 'ou-upgrade', 'Preserved', NOW(), NOW())
					""".formatted(conversationId));
		}
		Flyway latest = Flyway.configure()
				.dataSource(dataSource)
				.schemas(schema)
				.defaultSchema(schema)
				.locations("classpath:db/migration")
				.load();
		latest.migrate();
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			statement.execute("SET search_path TO " + schema);
			try (var result = statement.executeQuery(
					"SELECT title FROM conversation WHERE conversation_id = '" + conversationId + "'")) {
				assertTrue(result.next());
				assertEquals("Preserved", result.getString(1));
			}
			try (var result = statement.executeQuery(
					"SELECT to_regclass('workspace_agent_task')::text")) {
				assertTrue(result.next());
				assertEquals("workspace_agent_task", result.getString(1));
			}
		}
	}
}
