package synvo.lark.channel;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import synvo.configuration.LarkProperties;
import synvo.persistence.LarkMessageProcessingRepository;

@Component
@ConditionalOnProperty(prefix = "synvo.lark", name = "enabled", havingValue = "true")
final class LarkDirectMessageHandler {

	static final String READY_REPLY = "Synvo is connected and ready. AI conversations and workflows will be enabled in the next phase.";
	static final String UNSUPPORTED_REPLY = "Synvo is connected, but Phase 1 currently supports text messages only.";

	private static final Logger log = LoggerFactory.getLogger(LarkDirectMessageHandler.class);

	private final LarkProperties properties;
	private final LarkMessageProcessingRepository messageRepository;
	private final LarkChannelClient channelClient;
	private volatile String botOpenId;

	LarkDirectMessageHandler(
			LarkProperties properties,
			LarkMessageProcessingRepository messageRepository,
			LarkChannelClient channelClient) {
		this.properties = properties;
		this.messageRepository = messageRepository;
		this.channelClient = channelClient;
	}

	void setBotOpenId(String botOpenId) {
		this.botOpenId = botOpenId;
	}

	void handle(InboundLarkMessage message) {
		if (!isEligible(message)) {
			return;
		}
		if (!messageRepository.tryClaim(
				message.messageId(), message.senderOpenId(), message.chatType(), message.receivedAt())) {
			log.debug("Ignored duplicate Lark message event");
			return;
		}

		String reply = isText(message) ? READY_REPLY : UNSUPPORTED_REPLY;
		channelClient.respond(message, reply)
				.whenComplete((replyMessageId, failure) -> {
					if (failure == null) {
						messageRepository.markReplied(message.messageId(), replyMessageId);
						return;
					}
					messageRepository.markFailed(message.messageId(), "LARK_REPLY_FAILED");
					log.warn("Lark reply failed for a claimed message ({})", failureType(failure));
				});
	}

	private boolean isEligible(InboundLarkMessage message) {
		return message != null
				&& StringUtils.hasText(message.messageId())
				&& StringUtils.hasText(message.chatId())
				&& "p2p".equals(message.chatType())
				&& properties.pilotOpenId().equals(message.senderOpenId())
				&& !Objects.equals(botOpenId, message.senderOpenId());
	}

	private static boolean isText(InboundLarkMessage message) {
		return "text".equals(message.contentType()) && StringUtils.hasText(message.content());
	}

	private static String failureType(Throwable failure) {
		Throwable cause = failure instanceof CompletionException && failure.getCause() != null
				? failure.getCause()
				: failure;
		return cause.getClass().getSimpleName();
	}
}
