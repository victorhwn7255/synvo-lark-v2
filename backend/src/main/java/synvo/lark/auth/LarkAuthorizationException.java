package synvo.lark.auth;

public class LarkAuthorizationException extends RuntimeException {

	private final String errorCode;
	private final boolean reauthorizationRequired;

	public LarkAuthorizationException(String errorCode, boolean reauthorizationRequired) {
		super("Lark authorization failed");
		this.errorCode = errorCode;
		this.reauthorizationRequired = reauthorizationRequired;
	}

	public LarkAuthorizationException(
			String errorCode, boolean reauthorizationRequired, Throwable cause) {
		super("Lark authorization failed", cause);
		this.errorCode = errorCode;
		this.reauthorizationRequired = reauthorizationRequired;
	}

	public String errorCode() {
		return errorCode;
	}

	public boolean reauthorizationRequired() {
		return reauthorizationRequired;
	}
}
