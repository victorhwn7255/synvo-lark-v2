package synvo.agent;

public record ConversationContextMessage(Role role, String content) {

	public enum Role {
		USER,
		ASSISTANT
	}
}
