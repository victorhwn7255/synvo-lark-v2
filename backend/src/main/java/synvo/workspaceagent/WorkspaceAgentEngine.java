package synvo.workspaceagent;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Synvo-owned port for the complete Phase 3 coding-task engine. Implementations
 * hide process, transport, provider, and protocol details behind these product
 * concepts.
 */
public interface WorkspaceAgentEngine {

	EngineStatus status();

	Capabilities capabilities();

	AccountStatus account();

	TaskHandle createTask(WorkspaceTarget workspace, RunMode mode);

	TaskHandle forkTask(String taskReference, WorkspaceTarget workspace);

	void resumeTask(String taskReference, WorkspaceTarget workspace);

	void renameTask(String taskReference, String title);

	void archiveTask(String taskReference);

	void unarchiveTask(String taskReference);

	void deleteTask(String taskReference);

	OperationHandle startTurn(
			String taskReference,
			WorkspaceTarget workspace,
			RunMode mode,
			TurnInput input);

	OperationHandle startReview(
			String taskReference,
			WorkspaceTarget workspace,
			ReviewTarget target);

	ActivityBatch waitForActivity(String operationReference, long afterSequence);

	List<InteractionRequest> pendingInteractions(String operationReference);

	void decideInteraction(
			String operationReference,
			String interactionReference,
			InteractionDecision decision,
			Map<String, String> formValues);

	void steer(String operationReference, String text);

	void stop(String operationReference);

	Inventory inventory(String taskReference, WorkspaceTarget workspace);

	Optional<Goal> goal(String taskReference);

	void setGoal(String taskReference, String objective, GoalCommand command);

	void clearGoal(String taskReference);

	enum EngineStatus {
		READY,
		DISABLED,
		AUTHENTICATION_REQUIRED,
		UNAVAILABLE
	}

	enum RunMode {
		READ_ONLY,
		WORKSPACE_WRITE
	}

	/** Product-level goal actions; provider status strings stay inside the adapter. */
	enum GoalCommand {
		SAVE,
		RESUME,
		PAUSE
	}

	enum ActivityKind {
		TURN_STARTED,
		MESSAGE_DELTA,
		MESSAGE_COMPLETED,
		PLAN_STARTED,
		PLAN_DELTA,
		PLAN_COMPLETED,
		PLAN_UPDATED,
		REASONING_STARTED,
		REASONING_DELTA,
		REASONING_COMPLETED,
		COMMAND_STARTED,
		COMMAND_OUTPUT,
		COMMAND_COMPLETED,
		FILE_CHANGE_STARTED,
		FILE_OUTPUT,
		DIFF,
		FILE_CHANGE_COMPLETED,
		MCP_STARTED,
		MCP_PROGRESS,
		MCP_COMPLETED,
		NESTED_ACTIVITY_STARTED,
		NESTED_ACTIVITY_COMPLETED,
		REVIEW_ENTERED,
		REVIEW_EXITED,
		COMPACTED,
		USAGE_UPDATED,
		INTERACTION_RESOLVED,
		WAIT_STARTED,
		WAIT_COMPLETED,
		TURN_COMPLETED
	}

	enum InteractionKind {
		COMMAND_APPROVAL,
		FILE_CHANGE_APPROVAL,
		MCP_TOOL_APPROVAL,
		MCP_ELICITATION
	}

	enum InteractionDecision {
		APPROVE_ONCE,
		DECLINE,
		CANCEL
	}

	enum TerminalStatus {
		COMPLETED,
		FAILED,
		STOPPED,
		TIMEOUT,
		USAGE_LIMITED,
		AUTHENTICATION_REQUIRED,
		PROTOCOL_INCOMPATIBLE,
		ENGINE_UNAVAILABLE
	}

	enum ReviewKind {
		UNCOMMITTED_CHANGES,
		BASE_BRANCH,
		COMMIT,
		CUSTOM
	}

	record WorkspaceTarget(String id, Path canonicalRoot) {
		public WorkspaceTarget {
			Objects.requireNonNull(id, "id");
			Objects.requireNonNull(canonicalRoot, "canonicalRoot");
			if (id.isBlank() || !canonicalRoot.isAbsolute()) {
				throw new IllegalArgumentException("Workspace target is invalid");
			}
		}
	}

	record TaskHandle(String reference, String model) {
		public TaskHandle {
			if (reference == null || reference.isBlank() || model == null || model.isBlank()) {
				throw new IllegalArgumentException("Task handle is invalid");
			}
		}
	}

	record OperationHandle(String reference) {
		public OperationHandle {
			if (reference == null || reference.isBlank()) {
				throw new IllegalArgumentException("Operation handle is invalid");
			}
		}
	}

	record TurnInput(String text, String reasoningEffort, String skillName) {
		public TurnInput {
			if (text == null || text.isBlank() || reasoningEffort == null
					|| reasoningEffort.isBlank()) {
				throw new IllegalArgumentException("Turn input is invalid");
			}
		}
	}

	record ReviewTarget(ReviewKind kind, String value) {
		public ReviewTarget {
			Objects.requireNonNull(kind, "kind");
			if (kind == ReviewKind.UNCOMMITTED_CHANGES) {
				value = null;
			}
			else if (value == null || value.isBlank()) {
				throw new IllegalArgumentException("Review value is required");
			}
		}
	}

	record Activity(
			long sequence,
			ActivityKind kind,
			String text,
			boolean truncated,
			String activityReference,
			TerminalStatus terminalStatus
	) {
		public Activity {
			if (sequence < 0) {
				throw new IllegalArgumentException("Activity sequence is invalid");
			}
			Objects.requireNonNull(kind, "kind");
			if (kind == ActivityKind.TURN_COMPLETED && terminalStatus == null) {
				throw new IllegalArgumentException("Terminal activity requires a status");
			}
			if (kind != ActivityKind.TURN_COMPLETED && terminalStatus != null) {
				throw new IllegalArgumentException("Only terminal activity has a terminal status");
			}
		}
	}

	record ActivityBatch(List<Activity> activities, boolean terminal) {
		public ActivityBatch {
			activities = List.copyOf(activities);
		}
	}

	record InteractionRequest(
			String reference,
			String workspaceId,
			InteractionKind kind,
			String category,
			String reason,
			List<InteractionDecision> availableDecisions,
			InteractionDetail detail,
			Instant expiresAt
	) {
		public InteractionRequest {
			if (reference == null || reference.isBlank()
					|| workspaceId == null || workspaceId.isBlank()) {
				throw new IllegalArgumentException("Interaction reference is invalid");
			}
			Objects.requireNonNull(kind, "kind");
			availableDecisions = List.copyOf(availableDecisions);
			detail = detail == null ? InteractionDetail.empty() : detail;
			Objects.requireNonNull(expiresAt, "expiresAt");
		}
	}

	record InteractionDetail(
			String command,
			String workingDirectory,
			List<String> affectedPaths,
			String mcpServer,
			String mcpTool,
			String message,
			String inputMode,
			String elicitationUrl,
			List<InteractionField> fields
	) {
		public InteractionDetail {
			affectedPaths = affectedPaths == null ? List.of() : List.copyOf(affectedPaths);
			fields = fields == null ? List.of() : List.copyOf(fields);
		}

		public InteractionDetail(
				String command,
				String workingDirectory,
				List<String> affectedPaths,
				String mcpServer,
				String mcpTool,
				String message,
				String inputMode) {
			this(command, workingDirectory, affectedPaths, mcpServer, mcpTool,
					message, inputMode, null, List.of());
		}

		public static InteractionDetail empty() {
			return new InteractionDetail(
					null, null, List.of(), null, null, null, null, null, List.of());
		}
	}

	record InteractionField(
			String name,
			String label,
			InteractionFieldType type,
			boolean required,
			List<String> options,
			int maxLength
	) {
		public InteractionField {
			Objects.requireNonNull(name, "name");
			Objects.requireNonNull(label, "label");
			Objects.requireNonNull(type, "type");
			options = options == null ? List.of() : List.copyOf(options);
		}
	}

	enum InteractionFieldType {
		TEXT,
		BOOLEAN,
		NUMBER,
		INTEGER,
		SELECT
	}

	record Capabilities(
			String runtimeVersion,
			String model,
			List<String> reasoningEfforts,
			List<String> enabledStableFeatures
	) {
		public Capabilities {
			reasoningEfforts = List.copyOf(reasoningEfforts);
			enabledStableFeatures = List.copyOf(enabledStableFeatures);
		}
	}

	record AccountStatus(
			String authentication,
			boolean authenticationRequired,
			String plan,
			Double usedPercent,
			Instant resetsAt
	) {
	}

	record Skill(String name, String description) {
	}

	record McpServer(String name, String authenticationStatus, List<String> tools) {
		public McpServer {
			tools = List.copyOf(tools);
		}
	}

	record Inventory(List<Skill> skills, List<McpServer> mcpServers) {
		public Inventory {
			skills = List.copyOf(skills);
			mcpServers = List.copyOf(mcpServers);
		}
	}

	record Goal(
			String objective,
			String status,
			long tokensUsed,
			long timeUsedSeconds
	) {
	}
}
