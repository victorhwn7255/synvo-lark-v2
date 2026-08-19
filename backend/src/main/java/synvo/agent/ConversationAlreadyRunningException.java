package synvo.agent;

public final class ConversationAlreadyRunningException extends RuntimeException {

	public ConversationAlreadyRunningException() {
		super("Conversation request is already running");
	}
}
