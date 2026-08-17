package synvo.lark.channel;

import java.time.Instant;

public record InboundLarkMessage(
		String messageId,
		String chatId,
		String chatType,
		String senderOpenId,
		String content,
		String contentType,
		String rootMessageId,
		String threadId,
		String replyToMessageId,
		Instant receivedAt) {
}
