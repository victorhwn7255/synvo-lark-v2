package synvo.agent;

import java.util.UUID;
import org.springframework.util.StringUtils;

public record ConversationRequest(
		String requestId,
		UUID conversationId,
		String userOpenId,
		String content,
		UUID replaceFailedAssistantTurnId,
		String reasoningEffort,
		String skillName
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
		if (reasoningEffort != null && reasoningEffort.length() > 64) {
			throw new IllegalArgumentException("Reasoning effort is invalid");
		}
		if (skillName != null && skillName.length() > 200) {
			throw new IllegalArgumentException("Skill name is invalid");
		}
		content = content.strip();
		reasoningEffort = normalizeOptional(reasoningEffort);
		skillName = normalizeOptional(skillName);
	}

	public ConversationRequest(
			String requestId,
			UUID conversationId,
			String userOpenId,
			String content,
			UUID replaceFailedAssistantTurnId) {
		this(requestId, conversationId, userOpenId, content,
				replaceFailedAssistantTurnId, null, null);
	}

	public ConversationRequest(
			String requestId,
			UUID conversationId,
			String userOpenId,
			String content) {
		this(requestId, conversationId, userOpenId, content, null, null, null);
	}

	private static String normalizeOptional(String value) {
		return StringUtils.hasText(value) ? value.strip() : null;
	}
}
