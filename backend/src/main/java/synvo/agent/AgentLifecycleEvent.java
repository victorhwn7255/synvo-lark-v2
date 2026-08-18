package synvo.agent;

import java.util.Objects;
import org.springframework.util.StringUtils;

public record AgentLifecycleEvent(
		int sequence,
		State state,
		String safeMessage,
		String contentDelta
) {

	public AgentLifecycleEvent(int sequence, State state, String safeMessage) {
		this(sequence, state, safeMessage, null);
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
	}

	public static AgentLifecycleEvent contentDelta(int sequence, String contentDelta) {
		return new AgentLifecycleEvent(sequence, State.CONTENT_DELTA, null, contentDelta);
	}

	public static AgentLifecycleEvent contentReset(int sequence) {
		return new AgentLifecycleEvent(sequence, State.CONTENT_RESET, null, null);
	}

	public enum State {
		ACCEPTED,
		THINKING,
		STREAMING,
		CONTENT_DELTA,
		CONTENT_RESET,
		TOOL_RUNNING,
		COMPLETED,
		FAILED
	}
}
