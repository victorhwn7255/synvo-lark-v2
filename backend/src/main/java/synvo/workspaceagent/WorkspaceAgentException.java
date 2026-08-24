package synvo.workspaceagent;

public final class WorkspaceAgentException extends RuntimeException {

	private final Code code;

	public WorkspaceAgentException(Code code) {
		super(code.name());
		this.code = code;
	}

	public WorkspaceAgentException(Code code, Throwable cause) {
		super(code.name(), cause);
		this.code = code;
	}

	public Code code() {
		return code;
	}

	public enum Code {
		DISABLED,
		AUTHENTICATION_REQUIRED,
		UNAVAILABLE,
		BUSY,
		NOT_FOUND,
		FORBIDDEN,
		INVALID_REQUEST,
		POLICY_DENIED,
		INTERACTION_EXPIRED,
		INTERACTION_CONFLICT,
		PROTOCOL_INCOMPATIBLE
	}
}
