package synvo.workspaceagent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import synvo.workspaceagent.WorkspaceAgentEngine.ActivityKind;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDecision;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionKind;
import synvo.workspaceagent.WorkspaceAgentEngine.RunMode;
import synvo.workspaceagent.WorkspaceAgentEngine.TerminalStatus;

/** Persistence port for Synvo-owned task, activity, and interaction state. */
public interface WorkspaceAgentRepository {

	TaskRecord createTask(
			String ownerOpenId,
			String workspaceId,
			RunMode mode,
			String title,
			String taskReference);

	TaskRecord attachTask(
			String ownerOpenId,
			UUID conversationId,
			String workspaceId,
			RunMode mode,
			String taskReference);

	TaskRecord forkTask(TaskRecord source, String title, String taskReference);

	Optional<TaskRecord> findOwnedTask(String ownerOpenId, UUID taskId);

	Optional<TaskRecord> findByConversation(String ownerOpenId, UUID conversationId);

	List<TaskRecord> listOwnedTasks(String ownerOpenId, boolean archived, String search, int limit);

	TaskRecord updateTitle(String ownerOpenId, UUID taskId, String title);

	TaskRecord updatePinned(String ownerOpenId, UUID taskId, boolean pinned);

	TaskRecord updateArchived(String ownerOpenId, UUID taskId, boolean archived);

	TaskRecord updateMode(String ownerOpenId, UUID taskId, RunMode mode);

	TaskRecord replaceTaskReference(
			String ownerOpenId,
			UUID taskId,
			String expectedReference,
			String replacementReference);

	Optional<GoalSnapshot> findGoalSnapshot(UUID taskId);

	void saveGoalSnapshot(UUID taskId, GoalSnapshot goal);

	void clearGoalSnapshot(UUID taskId);

	boolean deleteOwnedTask(String ownerOpenId, UUID taskId);

	OperationRecord startOperation(
			TaskRecord task,
			UUID conversationRunId,
			String requestKey,
			OperationType type);

	void bindOperationReference(UUID operationId, String operationReference);

	Optional<OperationRecord> findActiveOperation(UUID operationId);

	Optional<OperationRecord> findOperation(UUID operationId);

	Optional<OperationRecord> findActiveByConversationRun(UUID conversationRunId);

	Optional<OperationRecord> findActiveSystemOperation();

	Optional<OperationRecord> findLatestOperation(UUID taskId);

	boolean hasTerminalTurn(UUID taskId);

	void appendActivity(UUID operationId, SafeActivity activity);

	List<SafeActivity> loadActivity(UUID operationId, long afterSequence);

	void finishOperation(UUID operationId, TerminalStatus status, String safeMessage);

	InteractionRecord recordInteraction(
			OperationRecord operation,
			String sourceReference,
			InteractionKind kind,
			String category,
			String reason,
			String permissionScope,
			List<InteractionDecision> availableDecisions,
			Instant expiresAt);

	Optional<InteractionRecord> findOwnedInteraction(String ownerOpenId, UUID interactionId);

	List<InteractionRecord> listPendingInteractions(String ownerOpenId, UUID taskId);

	DecisionResult decideInteraction(
			String ownerOpenId,
			UUID interactionId,
			InteractionDecision decision,
			String decisionScope,
			Instant decidedAt);

	void expirePendingInteractions(UUID operationId, String terminalReason);

	int recoverInterruptedOperations();

	record TaskRecord(
			UUID taskId,
			UUID conversationId,
			String ownerOpenId,
			String workspaceId,
			RunMode mode,
			String title,
			String taskReference,
			boolean pinned,
			boolean archived,
			Instant createdAt,
			Instant updatedAt
	) {
	}

	record OperationRecord(
			UUID operationId,
			UUID taskId,
			UUID conversationRunId,
			String ownerOpenId,
			String workspaceId,
			String requestKey,
			OperationType type,
			OperationStatus status,
			String operationReference,
			Instant createdAt,
			Instant updatedAt
	) {
	}

	record GoalSnapshot(
			String objective,
			String status,
			long tokensUsed,
			long timeUsedSeconds,
			Instant updatedAt
	) {
	}

	record SafeActivity(
			long sequence,
			ActivityKind kind,
			String safeSummary,
			boolean terminal,
			TerminalStatus terminalStatus,
			Instant createdAt
	) {
	}

	record InteractionRecord(
			UUID interactionId,
			UUID operationId,
			UUID taskId,
			String ownerOpenId,
			String workspaceId,
			String sourceReference,
			InteractionKind kind,
			String category,
			String reason,
			String permissionScope,
			List<InteractionDecision> availableDecisions,
			InteractionStatus status,
			InteractionDecision decision,
			String decisionScope,
			Instant expiresAt,
			Instant createdAt,
			Instant decidedAt
	) {
		public InteractionRecord {
			availableDecisions = List.copyOf(availableDecisions);
		}
	}

	record DecisionResult(DecisionDisposition disposition, InteractionRecord interaction) {
	}

	enum OperationType {
		TURN,
		REVIEW
	}

	enum OperationStatus {
		RUNNING,
		WAITING_FOR_INTERACTION,
		COMPLETED,
		FAILED,
		STOPPED
	}

	enum InteractionStatus {
		PENDING,
		DECIDED,
		EXPIRED,
		CANCELLED
	}

	enum DecisionDisposition {
		APPLIED,
		ALREADY_APPLIED,
		CONFLICT,
		EXPIRED,
		NOT_FOUND
	}
}
