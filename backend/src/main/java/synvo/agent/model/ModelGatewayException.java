package synvo.agent.model;

public final class ModelGatewayException extends RuntimeException {

	private final Code code;

	public ModelGatewayException(Code code) {
		this(code, null);
	}

	public ModelGatewayException(Code code, Throwable cause) {
		super("Model request could not be completed", cause);
		this.code = code;
	}

	public Code code() {
		return code;
	}

	public enum Code {
		UNAVAILABLE,
		PROVIDER_FAILURE,
		INVALID_RESPONSE,
		CANCELLED,
		TIMEOUT
	}
}
