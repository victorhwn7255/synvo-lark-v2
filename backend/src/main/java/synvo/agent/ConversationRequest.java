package synvo.agent;

import java.util.UUID;
import org.springframework.util.StringUtils;

public record ConversationRequest(
		String requestId,
		UUID conversationId,
		String userOpenId,
		String content,
		UUID replaceFailedAssistantTurnId
) {

	private static final int MAX_REQUEST_ID_LENGTH = 128;
	private static final int MAX_CONTENT_LENGTH = 20_000;

	public ConversationRequest {
		if (!StringUtils.hasText(requestId) || requestId.length() > MAX_REQUEST_ID_LENGTH) {
			throw new IllegalArgumentException("A valid request ID is required");
		}
		if (!StringUtils.hasText(userOpenId) || userOpenId.length() > 128) {
			throw new IllegalArgumentException("A valid user identity is required");
		}
		if (!StringUtils.hasText(content) || content.length() > MAX_CONTENT_LENGTH) {
			throw new IllegalArgumentException("Message content must contain between 1 and 20000 characters");
		}
		if (replaceFailedAssistantTurnId != null && conversationId == null) {
			throw new IllegalArgumentException("A retry requires an existing conversation");
		}
		content = content.strip();
	}

	public ConversationRequest(
			String requestId,
			UUID conversationId,
			String userOpenId,
			String content) {
		this(requestId, conversationId, userOpenId, content, null);
	}
}
