package synvo.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import synvo.workspaceagent.WorkspaceAgentEngine.ActivityKind;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDecision;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionKind;
import synvo.workspaceagent.WorkspaceAgentEngine.RunMode;
import synvo.workspaceagent.WorkspaceAgentEngine.TerminalStatus;
import synvo.workspaceagent.WorkspaceAgentRepository;

@Repository
public class JdbcWorkspaceAgentRepository implements WorkspaceAgentRepository {

	private static final String TASK_ID = "taskId";
	private static final String OWNER = "ownerOpenId";
	private static final String OPERATION_ID = "operationId";

	private final JdbcClient jdbcClient;

	public JdbcWorkspaceAgentRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	@Transactional
	public TaskRecord createTask(
			String ownerOpenId,
			String workspaceId,
			RunMode mode,
			String title,
			String taskReference) {
		UUID conversationId = UUID.randomUUID();
		Instant now = Instant.now();
		jdbcClient.sql("""
				INSERT INTO conversation (
				    conversation_id, owner_open_id, title, created_at, updated_at
				)
				VALUES (:conversationId, :ownerOpenId, :title, :now, :now)
				""")
				.param("conversationId", conversationId)
				.param(OWNER, ownerOpenId)
				.param("title", title)
				.param("now", atUtc(now))
				.update();
		return insertTask(
				UUID.randomUUID(), conversationId, ownerOpenId, workspaceId,
				mode, title, taskReference, now);
	}

	@Override
	@Transactional
	public TaskRecord attachTask(
			String ownerOpenId,
			UUID conversationId,
			String workspaceId,
			RunMode mode,
			String taskReference) {
		Optional<TaskRecord> existing = findByConversation(ownerOpenId, conversationId);
		if (existing.isPresent()) {
			return existing.get();
		}
		String title = jdbcClient.sql("""
				SELECT title
				FROM conversation
				WHERE conversation_id = :conversationId
				  AND owner_open_id = :ownerOpenId
				""")
				.param("conversationId", conversationId)
				.param(OWNER, ownerOpenId)
				.query(String.class)
				.optional()
				.orElseThrow(() -> new IllegalArgumentException("Conversation is unavailable"));
		return insertTask(
				UUID.randomUUID(), conversationId, ownerOpenId, workspaceId,
				mode, title, taskReference, Instant.now());
	}

	@Override
	@Transactional
	public TaskRecord forkTask(TaskRecord source, String title, String taskReference) {
		TaskRecord fork = createTask(
				source.ownerOpenId(), source.workspaceId(), source.mode(), title, taskReference);
		List<VisibleTurn> turns = jdbcClient.sql("""
				SELECT role, content, status, created_at, updated_at
				FROM conversation_turn
				WHERE conversation_id = :conversationId
				  AND superseded = FALSE
				ORDER BY ordinal
				""")
				.param("conversationId", source.conversationId())
				.query((resultSet, rowNumber) -> new VisibleTurn(
						resultSet.getString("role"),
						resultSet.getString("content"),
						resultSet.getString("status"),
						instant(resultSet, "created_at"),
						instant(resultSet, "updated_at")))
				.list();
		for (VisibleTurn turn : turns) {
			jdbcClient.sql("""
					INSERT INTO conversation_turn (
					    turn_id, conversation_id, role, content, status,
					    created_at, updated_at
					)
					VALUES (
					    :turnId, :conversationId, :role, :content, :status,
					    :createdAt, :updatedAt
					)
					""")
					.param("turnId", UUID.randomUUID())
					.param("conversationId", fork.conversationId())
					.param("role", turn.role())
					.param("content", turn.content())
					.param("status", turn.status())
					.param("createdAt", atUtc(turn.createdAt()))
					.param("updatedAt", atUtc(turn.updatedAt()))
					.update();
		}
		return fork;
	}

	private TaskRecord insertTask(
			UUID taskId,
			UUID conversationId,
			String ownerOpenId,
			String workspaceId,
			RunMode mode,
			String title,
			String taskReference,
			Instant now) {
		jdbcClient.sql("""
				INSERT INTO workspace_agent_task (
				    task_id, conversation_id, owner_open_id, workspace_id,
				    run_mode, title, task_reference, created_at, updated_at
				)
				VALUES (
				    :taskId, :conversationId, :ownerOpenId, :workspaceId,
				    :mode, :title, :taskReference, :now, :now
				)
				""")
				.param(TASK_ID, taskId)
				.param("conversationId", conversationId)
				.param(OWNER, ownerOpenId)
				.param("workspaceId", workspaceId)
				.param("mode", mode.name())
				.param("title", title)
				.param("taskReference", taskReference)
				.param("now", atUtc(now))
				.update();
		return requireOwnedTask(ownerOpenId, taskId);
	}

	@Override
	public Optional<TaskRecord> findOwnedTask(String ownerOpenId, UUID taskId) {
		return jdbcClient.sql(taskSelect() + """
				WHERE t.owner_open_id = :ownerOpenId
				  AND t.task_id = :taskId
				""")
				.param(OWNER, ownerOpenId)
				.param(TASK_ID, taskId)
				.query(JdbcWorkspaceAgentRepository::mapTask)
				.optional();
	}

	@Override
	public Optional<TaskRecord> findByConversation(String ownerOpenId, UUID conversationId) {
		return jdbcClient.sql(taskSelect() + """
				WHERE t.owner_open_id = :ownerOpenId
				  AND t.conversation_id = :conversationId
				""")
				.param(OWNER, ownerOpenId)
				.param("conversationId", conversationId)
				.query(JdbcWorkspaceAgentRepository::mapTask)
				.optional();
	}

	@Override
	public List<TaskRecord> listOwnedTasks(
			String ownerOpenId,
			boolean archived,
			String search,
			int limit) {
		String normalizedSearch = search == null ? "" : search.strip();
		return jdbcClient.sql(taskSelect() + """
				WHERE t.owner_open_id = :ownerOpenId
				  AND t.archived = :archived
				  AND (:search = '' OR LOWER(t.title) LIKE LOWER(:searchPattern))
				ORDER BY t.pinned DESC, t.updated_at DESC
				LIMIT :limit
				""")
				.param(OWNER, ownerOpenId)
				.param("archived", archived)
				.param("search", normalizedSearch)
				.param("searchPattern", "%" + normalizedSearch + "%")
				.param("limit", limit)
				.query(JdbcWorkspaceAgentRepository::mapTask)
				.list();
	}

	@Override
	@Transactional
	public TaskRecord updateTitle(String ownerOpenId, UUID taskId, String title) {
		TaskRecord task = requireOwnedTask(ownerOpenId, taskId);
		OffsetDateTime now = atUtc(Instant.now());
		assertOne(jdbcClient.sql("""
				UPDATE workspace_agent_task
				SET title = :title, updated_at = :now
				WHERE task_id = :taskId AND owner_open_id = :ownerOpenId
				""")
				.param("title", title)
				.param("now", now)
				.param(TASK_ID, taskId)
				.param(OWNER, ownerOpenId)
				.update());
		jdbcClient.sql("""
				UPDATE conversation
				SET title = :title, updated_at = :now
				WHERE conversation_id = :conversationId
				""")
				.param("title", title)
				.param("now", now)
				.param("conversationId", task.conversationId())
				.update();
		return requireOwnedTask(ownerOpenId, taskId);
	}

	@Override
	public TaskRecord updatePinned(String ownerOpenId, UUID taskId, boolean pinned) {
		return updateBoolean(ownerOpenId, taskId, "pinned", pinned);
	}

	@Override
	public TaskRecord updateArchived(String ownerOpenId, UUID taskId, boolean archived) {
		return updateBoolean(ownerOpenId, taskId, "archived", archived);
	}

	private TaskRecord updateBoolean(
			String ownerOpenId,
			UUID taskId,
			String column,
			boolean value) {
		if (!"pinned".equals(column) && !"archived".equals(column)) {
			throw new IllegalArgumentException("Unsupported task field");
		}
		String update = """
				UPDATE workspace_agent_task
				SET %s = :value, updated_at = :now
				WHERE task_id = :taskId AND owner_open_id = :ownerOpenId
				""".formatted(column);
		int updated = jdbcClient.sql(update)
				.param("value", value)
				.param("now", atUtc(Instant.now()))
				.param(TASK_ID, taskId)
				.param(OWNER, ownerOpenId)
				.update();
		assertOne(updated);
		return requireOwnedTask(ownerOpenId, taskId);
	}

	@Override
	public TaskRecord updateMode(String ownerOpenId, UUID taskId, RunMode mode) {
		int updated = jdbcClient.sql("""
				UPDATE workspace_agent_task
				SET run_mode = :mode, updated_at = :now
				WHERE task_id = :taskId AND owner_open_id = :ownerOpenId
				""")
				.param("mode", mode.name())
				.param("now", atUtc(Instant.now()))
				.param(TASK_ID, taskId)
				.param(OWNER, ownerOpenId)
				.update();
		assertOne(updated);
		return requireOwnedTask(ownerOpenId, taskId);
	}

	@Override
	public TaskRecord replaceTaskReference(
			String ownerOpenId,
			UUID taskId,
			String expectedReference,
			String replacementReference) {
		int updated = jdbcClient.sql("""
				UPDATE workspace_agent_task
				SET task_reference = :replacementReference, updated_at = :now
				WHERE task_id = :taskId
				  AND owner_open_id = :ownerOpenId
				  AND task_reference = :expectedReference
				""")
				.param("replacementReference", replacementReference)
				.param("now", atUtc(Instant.now()))
				.param(TASK_ID, taskId)
				.param(OWNER, ownerOpenId)
				.param("expectedReference", expectedReference)
				.update();
		assertOne(updated);
		return requireOwnedTask(ownerOpenId, taskId);
	}

	@Override
	public Optional<GoalSnapshot> findGoalSnapshot(UUID taskId) {
		return jdbcClient.sql("""
				SELECT goal_objective, goal_status, goal_tokens_used,
				       goal_time_used_seconds, goal_updated_at
				FROM workspace_agent_task
				WHERE task_id = :taskId
				  AND goal_objective IS NOT NULL
				""")
				.param(TASK_ID, taskId)
				.query((resultSet, rowNumber) -> new GoalSnapshot(
						resultSet.getString("goal_objective"),
						resultSet.getString("goal_status"),
						resultSet.getLong("goal_tokens_used"),
						resultSet.getLong("goal_time_used_seconds"),
						instant(resultSet, "goal_updated_at")))
				.optional();
	}

	@Override
	public void saveGoalSnapshot(UUID taskId, GoalSnapshot goal) {
		assertOne(jdbcClient.sql("""
				UPDATE workspace_agent_task
				SET goal_objective = :objective,
				    goal_status = :status,
				    goal_tokens_used = :tokensUsed,
				    goal_time_used_seconds = :timeUsedSeconds,
				    goal_updated_at = :updatedAt
				WHERE task_id = :taskId
				""")
				.param("objective", goal.objective())
				.param("status", goal.status())
				.param("tokensUsed", goal.tokensUsed())
				.param("timeUsedSeconds", goal.timeUsedSeconds())
				.param("updatedAt", atUtc(goal.updatedAt()))
				.param(TASK_ID, taskId)
				.update());
	}

	@Override
	public void clearGoalSnapshot(UUID taskId) {
		assertOne(jdbcClient.sql("""
				UPDATE workspace_agent_task
				SET goal_objective = NULL,
				    goal_status = NULL,
				    goal_tokens_used = NULL,
				    goal_time_used_seconds = NULL,
				    goal_updated_at = NULL
				WHERE task_id = :taskId
				""")
				.param(TASK_ID, taskId)
				.update());
	}

	@Override
	@Transactional
	public boolean deleteOwnedTask(String ownerOpenId, UUID taskId) {
		Optional<TaskRecord> task = findOwnedTask(ownerOpenId, taskId);
		if (task.isEmpty()) {
			return false;
		}
		if (jdbcClient.sql("""
				SELECT COUNT(*)
				FROM workspace_agent_operation
				WHERE task_id = :taskId
				  AND status IN ('RUNNING', 'WAITING_FOR_INTERACTION')
				""")
				.param(TASK_ID, taskId)
				.query(Integer.class)
				.single() > 0) {
			throw new IllegalStateException("Task has an active operation");
		}
		return jdbcClient.sql("""
				DELETE FROM conversation
				WHERE conversation_id = :conversationId
				  AND owner_open_id = :ownerOpenId
				""")
				.param("conversationId", task.get().conversationId())
				.param(OWNER, ownerOpenId)
				.update() == 1;
	}

	@Override
	public OperationRecord startOperation(
			TaskRecord task,
			UUID conversationRunId,
			String requestKey,
			OperationType type) {
		UUID operationId = UUID.randomUUID();
		Instant now = Instant.now();
		jdbcClient.sql("""
				INSERT INTO workspace_agent_operation (
				    operation_id, task_id, conversation_run_id, request_key,
				    operation_type, status, created_at, updated_at
				)
				VALUES (
				    :operationId, :taskId, :conversationRunId, :requestKey,
				    :operationType, 'RUNNING', :now, :now
				)
				""")
				.param(OPERATION_ID, operationId)
				.param(TASK_ID, task.taskId())
				.param("conversationRunId", conversationRunId)
				.param("requestKey", requestKey)
				.param("operationType", type.name())
				.param("now", atUtc(now))
				.update();
		return requireOperation(operationId);
	}

	@Override
	public void bindOperationReference(UUID operationId, String operationReference) {
		assertOne(jdbcClient.sql("""
				UPDATE workspace_agent_operation
				SET operation_reference = :operationReference, updated_at = :now
				WHERE operation_id = :operationId
				  AND status = 'RUNNING'
				  AND operation_reference IS NULL
				""")
				.param("operationReference", operationReference)
				.param("now", atUtc(Instant.now()))
				.param(OPERATION_ID, operationId)
				.update());
	}

	@Override
	public Optional<OperationRecord> findActiveOperation(UUID operationId) {
		return operationQuery("o.operation_id = :operationId", OPERATION_ID, operationId);
	}

	@Override
	public Optional<OperationRecord> findOperation(UUID operationId) {
		return jdbcClient.sql(operationSelect() + " WHERE o.operation_id = :operationId")
				.param(OPERATION_ID, operationId)
				.query(JdbcWorkspaceAgentRepository::mapOperation)
				.optional();
	}

	@Override
	public Optional<OperationRecord> findActiveByConversationRun(UUID conversationRunId) {
		return operationQuery(
				"o.conversation_run_id = :conversationRunId", "conversationRunId", conversationRunId);
	}

	@Override
	public Optional<OperationRecord> findActiveSystemOperation() {
		return jdbcClient.sql(operationSelect() + """
				WHERE o.status IN ('RUNNING', 'WAITING_FOR_INTERACTION')
				ORDER BY o.created_at
				LIMIT 1
				""")
				.query(JdbcWorkspaceAgentRepository::mapOperation)
				.optional();
	}

	@Override
	public Optional<OperationRecord> findLatestOperation(UUID taskId) {
		return jdbcClient.sql(operationSelect() + """
				WHERE o.task_id = :taskId
				ORDER BY o.created_at DESC
				LIMIT 1
				""")
				.param(TASK_ID, taskId)
				.query(JdbcWorkspaceAgentRepository::mapOperation)
				.optional();
	}

	@Override
	public boolean hasTerminalTurn(UUID taskId) {
		return jdbcClient.sql("""
				SELECT COUNT(*)
				FROM workspace_agent_operation
				WHERE task_id = :taskId
				  AND operation_type = 'TURN'
				  AND status IN ('COMPLETED', 'FAILED', 'STOPPED')
				""")
				.param(TASK_ID, taskId)
				.query(Integer.class)
				.single() > 0;
	}

	private Optional<OperationRecord> operationQuery(String predicate, String parameter, Object value) {
		return jdbcClient.sql(operationSelect() + " WHERE " + predicate + """
				 AND o.status IN ('RUNNING', 'WAITING_FOR_INTERACTION')
				""")
				.param(parameter, value)
				.query(JdbcWorkspaceAgentRepository::mapOperation)
				.optional();
	}

	@Override
	public void appendActivity(UUID operationId, SafeActivity activity) {
		jdbcClient.sql("""
				INSERT INTO workspace_agent_activity (
				    operation_id, sequence_number, activity_kind,
				    safe_summary, terminal, created_at
				)
				VALUES (
				    :operationId, :sequence, :kind,
				    :summary, :terminal, :createdAt
				)
				ON CONFLICT (operation_id, sequence_number) DO NOTHING
				""")
				.param(OPERATION_ID, operationId)
				.param("sequence", activity.sequence())
				.param("kind", activity.kind().name())
				.param("summary", activity.safeSummary())
				.param("terminal", activity.terminal())
				.param("createdAt", atUtc(activity.createdAt()))
				.update();
	}

	@Override
	public List<SafeActivity> loadActivity(UUID operationId, long afterSequence) {
		return jdbcClient.sql("""
				SELECT a.sequence_number, a.activity_kind, a.safe_summary,
				       a.terminal, o.terminal_status, a.created_at
				FROM workspace_agent_activity a
				JOIN workspace_agent_operation o ON o.operation_id = a.operation_id
				WHERE a.operation_id = :operationId
				  AND a.sequence_number > :afterSequence
				ORDER BY a.sequence_number
				""")
				.param(OPERATION_ID, operationId)
				.param("afterSequence", afterSequence)
				.query((resultSet, rowNumber) -> new SafeActivity(
						resultSet.getLong("sequence_number"),
						ActivityKind.valueOf(resultSet.getString("activity_kind")),
						resultSet.getString("safe_summary"),
						resultSet.getBoolean("terminal"),
						terminalStatus(resultSet.getString("terminal_status")),
						instant(resultSet, "created_at")))
				.list();
	}

	@Override
	@Transactional
	public void finishOperation(UUID operationId, TerminalStatus status, String safeMessage) {
		String operationStatus = switch (status) {
			case COMPLETED -> OperationStatus.COMPLETED.name();
			case STOPPED -> OperationStatus.STOPPED.name();
			default -> OperationStatus.FAILED.name();
		};
		jdbcClient.sql("""
				UPDATE workspace_agent_operation
				SET status = :status, terminal_status = :terminalStatus,
				    safe_terminal_message = :safeMessage, updated_at = :now
				WHERE operation_id = :operationId
				  AND status IN ('RUNNING', 'WAITING_FOR_INTERACTION')
				""")
				.param("status", operationStatus)
				.param("terminalStatus", status.name())
				.param("safeMessage", safeMessage)
				.param("now", atUtc(Instant.now()))
				.param(OPERATION_ID, operationId)
				.update();
		expirePendingInteractions(operationId, "OPERATION_TERMINAL");
	}

	@Override
	@Transactional
	public InteractionRecord recordInteraction(
			OperationRecord operation,
			String sourceReference,
			InteractionKind kind,
			String category,
			String reason,
			String permissionScope,
			List<InteractionDecision> availableDecisions,
			Instant expiresAt) {
		UUID interactionId = UUID.randomUUID();
		Instant now = Instant.now();
		jdbcClient.sql("""
				INSERT INTO workspace_agent_interaction (
				    interaction_id, operation_id, task_id, owner_open_id, workspace_id,
				    source_reference, interaction_kind, safe_action_category,
				    safe_reason, permission_scope, available_decisions,
				    status, expires_at, created_at
				)
				VALUES (
				    :interactionId, :operationId, :taskId, :ownerOpenId, :workspaceId,
				    :sourceReference, :kind, :category,
				    :reason, :permissionScope, :availableDecisions,
				    'PENDING', :expiresAt, :now
				)
				ON CONFLICT (operation_id, source_reference) DO NOTHING
				""")
				.param("interactionId", interactionId)
				.param(OPERATION_ID, operation.operationId())
				.param(TASK_ID, operation.taskId())
				.param(OWNER, operation.ownerOpenId())
				.param("workspaceId", operation.workspaceId())
				.param("sourceReference", sourceReference)
				.param("kind", kind.name())
				.param("category", category)
				.param("reason", reason)
				.param("permissionScope", permissionScope)
				.param("availableDecisions", encodeDecisions(availableDecisions))
				.param("expiresAt", atUtc(expiresAt))
				.param("now", atUtc(now))
				.update();
		jdbcClient.sql("""
				UPDATE workspace_agent_operation o
				SET status = 'WAITING_FOR_INTERACTION', updated_at = :now
				WHERE o.operation_id = :operationId
				  AND o.status = 'RUNNING'
				  AND EXISTS (
				      SELECT 1
				      FROM workspace_agent_interaction i
				      WHERE i.operation_id = o.operation_id
				        AND i.status = 'PENDING'
				  )
				""")
				.param(OPERATION_ID, operation.operationId())
				.param("now", atUtc(now))
				.update();
		return jdbcClient.sql(interactionSelect() + """
				WHERE i.operation_id = :operationId
				  AND i.source_reference = :sourceReference
				""")
				.param(OPERATION_ID, operation.operationId())
				.param("sourceReference", sourceReference)
				.query(JdbcWorkspaceAgentRepository::mapInteraction)
				.single();
	}

	@Override
	public Optional<InteractionRecord> findOwnedInteraction(String ownerOpenId, UUID interactionId) {
		return jdbcClient.sql(interactionSelect() + """
				WHERE i.owner_open_id = :ownerOpenId
				  AND i.interaction_id = :interactionId
				""")
				.param(OWNER, ownerOpenId)
				.param("interactionId", interactionId)
				.query(JdbcWorkspaceAgentRepository::mapInteraction)
				.optional();
	}

	@Override
	public List<InteractionRecord> listPendingInteractions(String ownerOpenId, UUID taskId) {
		return jdbcClient.sql(interactionSelect() + """
				WHERE i.owner_open_id = :ownerOpenId
				  AND i.task_id = :taskId
				  AND i.status = 'PENDING'
				ORDER BY i.created_at
				""")
				.param(OWNER, ownerOpenId)
				.param(TASK_ID, taskId)
				.query(JdbcWorkspaceAgentRepository::mapInteraction)
				.list();
	}

	@Override
	@Transactional
	public DecisionResult decideInteraction(
			String ownerOpenId,
			UUID interactionId,
			InteractionDecision decision,
			String decisionScope,
			Instant decidedAt) {
		InteractionRecord current = jdbcClient.sql(interactionSelect() + """
				WHERE i.owner_open_id = :ownerOpenId
				  AND i.interaction_id = :interactionId
				FOR UPDATE
				""")
				.param(OWNER, ownerOpenId)
				.param("interactionId", interactionId)
				.query(JdbcWorkspaceAgentRepository::mapInteraction)
				.optional()
				.orElse(null);
		if (current == null) {
			return new DecisionResult(DecisionDisposition.NOT_FOUND, null);
		}
		if (current.status() != InteractionStatus.PENDING) {
			DecisionDisposition disposition = current.decision() == decision
					? DecisionDisposition.ALREADY_APPLIED : DecisionDisposition.CONFLICT;
			return new DecisionResult(disposition, current);
		}
		if (!decidedAt.isBefore(current.expiresAt())) {
			jdbcClient.sql("""
					UPDATE workspace_agent_interaction
					SET status = 'EXPIRED', terminal_reason = 'EXPIRED'
					WHERE interaction_id = :interactionId AND status = 'PENDING'
					""")
					.param("interactionId", interactionId)
					.update();
			return new DecisionResult(
					DecisionDisposition.EXPIRED,
					findOwnedInteraction(ownerOpenId, interactionId).orElseThrow());
		}
		assertOne(jdbcClient.sql("""
				UPDATE workspace_agent_interaction
				SET status = 'DECIDED', decision = :decision,
				    decision_scope = :decisionScope, decided_at = :decidedAt,
				    terminal_reason = 'USER_DECISION'
				WHERE interaction_id = :interactionId AND status = 'PENDING'
				""")
				.param("decision", decision.name())
				.param("decisionScope", decisionScope)
				.param("decidedAt", atUtc(decidedAt))
				.param("interactionId", interactionId)
				.update());
		resumeOperationWhenNoInteractionIsPending(current.operationId(), decidedAt);
		return new DecisionResult(
				DecisionDisposition.APPLIED,
				findOwnedInteraction(ownerOpenId, interactionId).orElseThrow());
	}

	private void resumeOperationWhenNoInteractionIsPending(UUID operationId, Instant decidedAt) {
		jdbcClient.sql("""
				UPDATE workspace_agent_operation o
				SET status = 'RUNNING', updated_at = :decidedAt
				WHERE o.operation_id = :operationId
				  AND o.status = 'WAITING_FOR_INTERACTION'
				  AND NOT EXISTS (
				      SELECT 1
				      FROM workspace_agent_interaction i
				      WHERE i.operation_id = o.operation_id
				        AND i.status = 'PENDING'
				  )
				""")
				.param(OPERATION_ID, operationId)
				.param("decidedAt", atUtc(decidedAt))
				.update();
	}

	@Override
	public void expirePendingInteractions(UUID operationId, String terminalReason) {
		jdbcClient.sql("""
				UPDATE workspace_agent_interaction
				SET status = 'CANCELLED', terminal_reason = :terminalReason,
				    decided_at = :now
				WHERE operation_id = :operationId AND status = 'PENDING'
				""")
				.param("terminalReason", terminalReason)
				.param("now", atUtc(Instant.now()))
				.param(OPERATION_ID, operationId)
				.update();
	}

	@Override
	@Transactional
	public int recoverInterruptedOperations() {
		List<UUID> active = jdbcClient.sql("""
				SELECT operation_id
				FROM workspace_agent_operation
				WHERE status IN ('RUNNING', 'WAITING_FOR_INTERACTION')
				""")
				.query(UUID.class)
				.list();
		for (UUID operationId : active) {
			finishOperation(
					operationId,
					TerminalStatus.ENGINE_UNAVAILABLE,
					"Codex was interrupted. Please retry the task.");
		}
		return active.size();
	}

	private TaskRecord requireOwnedTask(String ownerOpenId, UUID taskId) {
		return findOwnedTask(ownerOpenId, taskId)
				.orElseThrow(() -> new IllegalArgumentException("Task is unavailable"));
	}

	private OperationRecord requireOperation(UUID operationId) {
		return jdbcClient.sql(operationSelect() + " WHERE o.operation_id = :operationId")
				.param(OPERATION_ID, operationId)
				.query(JdbcWorkspaceAgentRepository::mapOperation)
				.single();
	}

	private static String taskSelect() {
		return """
				SELECT t.task_id, t.conversation_id, t.owner_open_id, t.workspace_id,
				       t.run_mode, t.title, t.task_reference, t.pinned, t.archived,
				       t.created_at, t.updated_at
				FROM workspace_agent_task t
				""";
	}

	private static String operationSelect() {
		return """
				SELECT o.operation_id, o.task_id, o.conversation_run_id,
				       t.owner_open_id, t.workspace_id, o.request_key,
				       o.operation_type, o.status, o.operation_reference,
				       o.created_at, o.updated_at
				FROM workspace_agent_operation o
				JOIN workspace_agent_task t ON t.task_id = o.task_id
				""";
	}

	private static String interactionSelect() {
		return """
				SELECT i.interaction_id, i.operation_id, i.task_id, i.owner_open_id,
				       i.workspace_id, i.source_reference, i.interaction_kind,
				       i.safe_action_category, i.safe_reason, i.permission_scope,
				       i.available_decisions, i.status, i.decision, i.decision_scope,
				       i.expires_at, i.created_at, i.decided_at
				FROM workspace_agent_interaction i
				""";
	}

	private static TaskRecord mapTask(ResultSet resultSet, int rowNumber) throws SQLException {
		return new TaskRecord(
				resultSet.getObject("task_id", UUID.class),
				resultSet.getObject("conversation_id", UUID.class),
				resultSet.getString("owner_open_id"),
				resultSet.getString("workspace_id"),
				RunMode.valueOf(resultSet.getString("run_mode")),
				resultSet.getString("title"),
				resultSet.getString("task_reference"),
				resultSet.getBoolean("pinned"),
				resultSet.getBoolean("archived"),
				instant(resultSet, "created_at"),
				instant(resultSet, "updated_at"));
	}

	private static OperationRecord mapOperation(ResultSet resultSet, int rowNumber)
			throws SQLException {
		return new OperationRecord(
				resultSet.getObject("operation_id", UUID.class),
				resultSet.getObject("task_id", UUID.class),
				resultSet.getObject("conversation_run_id", UUID.class),
				resultSet.getString("owner_open_id"),
				resultSet.getString("workspace_id"),
				resultSet.getString("request_key"),
				OperationType.valueOf(resultSet.getString("operation_type")),
				OperationStatus.valueOf(resultSet.getString("status")),
				resultSet.getString("operation_reference"),
				instant(resultSet, "created_at"),
				instant(resultSet, "updated_at"));
	}

	private static TerminalStatus terminalStatus(String value) {
		return value == null ? null : TerminalStatus.valueOf(value);
	}

	private static InteractionRecord mapInteraction(ResultSet resultSet, int rowNumber)
			throws SQLException {
		String decision = resultSet.getString("decision");
		OffsetDateTime decidedAt = resultSet.getObject("decided_at", OffsetDateTime.class);
		return new InteractionRecord(
				resultSet.getObject("interaction_id", UUID.class),
				resultSet.getObject("operation_id", UUID.class),
				resultSet.getObject("task_id", UUID.class),
				resultSet.getString("owner_open_id"),
				resultSet.getString("workspace_id"),
				resultSet.getString("source_reference"),
				InteractionKind.valueOf(resultSet.getString("interaction_kind")),
				resultSet.getString("safe_action_category"),
				resultSet.getString("safe_reason"),
				resultSet.getString("permission_scope"),
				decodeDecisions(resultSet.getString("available_decisions")),
				InteractionStatus.valueOf(resultSet.getString("status")),
				decision == null ? null : InteractionDecision.valueOf(decision),
				resultSet.getString("decision_scope"),
				instant(resultSet, "expires_at"),
				instant(resultSet, "created_at"),
				decidedAt == null ? null : decidedAt.toInstant());
	}

	private static String encodeDecisions(List<InteractionDecision> decisions) {
		return String.join(",", decisions.stream().map(Enum::name).toList());
	}

	private static List<InteractionDecision> decodeDecisions(String encoded) {
		return Arrays.stream(encoded.split(","))
				.filter(value -> !value.isBlank())
				.map(InteractionDecision::valueOf)
				.toList();
	}

	private static Instant instant(ResultSet resultSet, String column) throws SQLException {
		return resultSet.getObject(column, OffsetDateTime.class).toInstant();
	}

	private static OffsetDateTime atUtc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static void assertOne(int count) {
		if (count != 1) {
			throw new IllegalStateException("Workspace-agent state changed concurrently");
		}
	}

	private record VisibleTurn(
			String role,
			String content,
			String status,
			Instant createdAt,
			Instant updatedAt
	) {
	}
}
