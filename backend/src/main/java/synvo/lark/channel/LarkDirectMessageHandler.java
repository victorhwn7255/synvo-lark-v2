package synvo.lark.channel;

import java.util.Objects;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.util.UriComponentsBuilder;
import synvo.configuration.LarkProperties;
import synvo.agent.AgentLifecycleEvent;
import synvo.agent.ConversationQueries;
import synvo.agent.ConversationRequest;
import synvo.agent.ConversationRunCoordinator;
import synvo.persistence.LarkConversationBindingRepository;
import synvo.persistence.LarkMessageProcessingRepository;

@Component
@ConditionalOnProperty(prefix = "synvo.lark", name = "enabled", havingValue = "true")
final class LarkDirectMessageHandler {

	static final String UNSUPPORTED_REPLY = "Synvo currently supports text messages only.";
	static final String DELIVERY_FAILURE_REPLY =
			"I couldn’t start a response in Lark. Please try again.";
	static final String STOP_REQUESTED_REPLY = "Stopping the active Codex task.";
	static final String NOTHING_TO_STOP_REPLY = "There is no active Codex task to stop.";

	private static final Logger log = LoggerFactory.getLogger(LarkDirectMessageHandler.class);

	private final LarkProperties properties;
	private final LarkMessageProcessingRepository messageRepository;
	private final LarkConversationBindingRepository conversationBindingRepository;
	private final LarkChannelClient channelClient;
	private final ConversationRunCoordinator conversations;
	private final ConversationQueries conversationQueries;
	private volatile String botOpenId;

	LarkDirectMessageHandler(
			LarkProperties properties,
			LarkMessageProcessingRepository messageRepository,
			LarkConversationBindingRepository conversationBindingRepository,
			LarkChannelClient channelClient,
			ConversationRunCoordinator conversations,
			ConversationQueries conversationQueries) {
		this.properties = properties;
		this.messageRepository = messageRepository;
		this.conversationBindingRepository = conversationBindingRepository;
		this.channelClient = channelClient;
		this.conversations = conversations;
		this.conversationQueries = conversationQueries;
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

		if (!isText(message)) {
			respondUnsupported(message);
			return;
		}
		if (isStopCommand(message.content())) {
			stopActiveConversation(message);
			return;
		}

		channelClient.stream(message, writer -> {
			conversations.run(
					new ConversationRequest(
							message.messageId(),
							conversationBindingRepository.findConversationId(
									message.chatId(), message.senderOpenId()).orElse(null),
							message.senderOpenId(),
							message.content()),
					submission -> conversationBindingRepository.bind(
							message.chatId(), message.senderOpenId(), submission.conversationId()),
					event -> applyToLarkStream(writer, event));
		})
				.whenComplete((replyMessageId, failure) -> {
					if (failure == null) {
						messageRepository.markReplied(message.messageId(), replyMessageId);
						return;
					}
					log.warn("Lark reply failed for a claimed message ({})", failureType(failure));
					respondAfterStreamFailure(message);
				});
	}

	private void stopActiveConversation(InboundLarkMessage message) {
		boolean stopped = conversationBindingRepository.findConversationId(
				message.chatId(), message.senderOpenId())
				.flatMap(conversationId -> conversationQueries.findConversation(
						message.senderOpenId(), conversationId))
				.map(ConversationQueries.ConversationDetail::activeRun)
				.filter(Objects::nonNull)
				.map(run -> conversations.stop(run.runId()))
				.orElse(false);
		respondText(message, stopped ? STOP_REQUESTED_REPLY : NOTHING_TO_STOP_REPLY);
	}

	private void respondAfterStreamFailure(InboundLarkMessage message) {
		channelClient.respond(message, DELIVERY_FAILURE_REPLY)
				.whenComplete((replyMessageId, fallbackFailure) -> {
					if (fallbackFailure == null) {
						messageRepository.markReplied(message.messageId(), replyMessageId);
						return;
					}
					messageRepository.markFailed(message.messageId(), "LARK_REPLY_FAILED");
					log.warn(
							"Lark fallback reply failed for a claimed message ({})",
							failureType(fallbackFailure));
				});
	}

	private void respondUnsupported(InboundLarkMessage message) {
		respondText(message, UNSUPPORTED_REPLY);
	}

	private void respondText(InboundLarkMessage message, String response) {
		channelClient.respond(message, response)
				.whenComplete((replyMessageId, failure) -> {
					if (failure == null) {
						messageRepository.markReplied(message.messageId(), replyMessageId);
						return;
					}
					messageRepository.markFailed(message.messageId(), "LARK_REPLY_FAILED");
					log.warn("Lark reply failed for a claimed message ({})", failureType(failure));
				});
	}

	private void applyToLarkStream(
			LarkChannelClient.StreamWriter writer,
			AgentLifecycleEvent event) {
		if (event.state() == AgentLifecycleEvent.State.CONTENT_DELTA) {
			writer.clearActionRequired();
			writer.append(event.contentDelta());
		}
		else if (event.state() == AgentLifecycleEvent.State.CONTENT_RESET) {
			writer.setContent("");
		}
		else if (event.state() == AgentLifecycleEvent.State.FAILED) {
			writer.clearActionRequired();
			writer.setContent(event.safeMessage());
		}
		else if (event.state() == AgentLifecycleEvent.State.ACTION_REQUIRED) {
			writer.showActionRequired(event.actionHandoff(), handoffUrl(event.actionHandoff()));
		}
		else if (event.state() == AgentLifecycleEvent.State.COMPLETED) {
			writer.clearActionRequired();
		}
	}

	private String handoffUrl(AgentLifecycleEvent.ActionHandoff handoff) {
		if (!StringUtils.hasText(properties.h5BaseUrl())) {
			return null;
		}
		try {
			return UriComponentsBuilder.fromUriString(properties.h5BaseUrl())
					.queryParam("codexTask", handoff.taskId())
					.queryParam("codexInteraction", handoff.interactionId())
					.build()
					.encode()
					.toUriString();
		}
		catch (IllegalArgumentException invalidUrl) {
			return null;
		}
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

	private static boolean isStopCommand(String content) {
		String normalized = content.strip();
		return "stop".equalsIgnoreCase(normalized) || "/stop".equalsIgnoreCase(normalized);
	}

	private static String failureType(Throwable failure) {
		Throwable cause = failure instanceof CompletionException && failure.getCause() != null
				? failure.getCause()
				: failure;
		return cause.getClass().getSimpleName();
	}
}
