package synvo.agent;

import java.util.Objects;
import java.util.UUID;
import org.springframework.util.StringUtils;

public record AgentLifecycleEvent(
		int sequence,
		State state,
		String safeMessage,
		String contentDelta,
		ActionHandoff actionHandoff
) {

	public AgentLifecycleEvent(int sequence, State state, String safeMessage) {
		this(sequence, state, safeMessage, null, null);
	}

	public AgentLifecycleEvent(
			int sequence,
			State state,
			String safeMessage,
			String contentDelta) {
		this(sequence, state, safeMessage, contentDelta, null);
	}

	public AgentLifecycleEvent {
		if (sequence < 1) {
			throw new IllegalArgumentException("Lifecycle sequence must be positive");
		}
		Objects.requireNonNull(state, "state");
		if (safeMessage != null && !StringUtils.hasText(safeMessage)) {
			safeMessage = null;
		}
		if (state == State.CONTENT_DELTA) {
			if (!StringUtils.hasLength(contentDelta)) {
				throw new IllegalArgumentException("A content delta is required");
			}
			safeMessage = null;
		}
		else if (contentDelta != null) {
			throw new IllegalArgumentException("Only content events may contain a delta");
		}
		if (state == State.ACTION_REQUIRED && actionHandoff == null) {
			throw new IllegalArgumentException("An action handoff is required");
		}
		if (state != State.ACTION_REQUIRED && actionHandoff != null) {
			throw new IllegalArgumentException("Only action events may contain a handoff");
		}
	}

	public static AgentLifecycleEvent contentDelta(int sequence, String contentDelta) {
		return new AgentLifecycleEvent(sequence, State.CONTENT_DELTA, null, contentDelta);
	}

	public static AgentLifecycleEvent contentReset(int sequence) {
		return new AgentLifecycleEvent(sequence, State.CONTENT_RESET, null, null);
	}

	public static AgentLifecycleEvent actionRequired(
			int sequence,
			ActionHandoff handoff) {
		return new AgentLifecycleEvent(
				sequence,
				State.ACTION_REQUIRED,
				"Open in H5 to review and approve.",
				null,
				handoff);
	}

	public record ActionHandoff(
			UUID taskId,
			UUID interactionId,
			String category,
			String workspaceName,
			String reason,
			String permissionScope
	) {
		public ActionHandoff {
			Objects.requireNonNull(taskId, "taskId");
			Objects.requireNonNull(interactionId, "interactionId");
			requireText(category, "category");
			requireText(workspaceName, "workspaceName");
			requireText(reason, "reason");
			requireText(permissionScope, "permissionScope");
		}

		private static void requireText(String value, String field) {
			if (!StringUtils.hasText(value)) {
				throw new IllegalArgumentException(field + " is required");
			}
		}
	}

	public enum State {
		ACCEPTED,
		THINKING,
		STREAMING,
		CONTENT_DELTA,
		CONTENT_RESET,
		TOOL_RUNNING,
		ACTION_REQUIRED,
		COMPLETED,
		FAILED
	}
}
