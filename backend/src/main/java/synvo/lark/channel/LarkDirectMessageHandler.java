package synvo.lark.channel;

import jakarta.annotation.PreDestroy;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import synvo.agent.AgentCancellation;
import synvo.configuration.LarkProperties;
import synvo.configuration.AgentRuntimeProperties;
import synvo.agent.AgentLifecycleEvent;
import synvo.agent.ConversationRequest;
import synvo.agent.ConversationResult;
import synvo.agent.SynvoAgentCore;
import synvo.persistence.LarkConversationBindingRepository;
import synvo.persistence.LarkMessageProcessingRepository;

@Component
@ConditionalOnProperty(prefix = "synvo.lark", name = "enabled", havingValue = "true")
final class LarkDirectMessageHandler {

	static final String UNSUPPORTED_REPLY = "Synvo currently supports text messages only.";
	static final String DELIVERY_FAILURE_REPLY =
			"I couldn’t start a response in Lark. Please try again.";

	private static final Logger log = LoggerFactory.getLogger(LarkDirectMessageHandler.class);

	private final LarkProperties properties;
	private final LarkMessageProcessingRepository messageRepository;
	private final LarkConversationBindingRepository conversationBindingRepository;
	private final LarkChannelClient channelClient;
	private final SynvoAgentCore agentCore;
	private final long responseTimeoutMillis;
	private final ScheduledExecutorService timeoutScheduler = Executors.newSingleThreadScheduledExecutor(
			Thread.ofPlatform().daemon().name("synvo-lark-agent-timeout").factory());
	private volatile String botOpenId;

	LarkDirectMessageHandler(
			LarkProperties properties,
			LarkMessageProcessingRepository messageRepository,
			LarkConversationBindingRepository conversationBindingRepository,
			LarkChannelClient channelClient,
			SynvoAgentCore agentCore,
			AgentRuntimeProperties agentRuntimeProperties) {
		this.properties = properties;
		this.messageRepository = messageRepository;
		this.conversationBindingRepository = conversationBindingRepository;
		this.channelClient = channelClient;
		this.agentCore = agentCore;
		this.responseTimeoutMillis = agentRuntimeProperties.responseTimeout().toMillis();
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

		channelClient.stream(message, writer -> {
			AgentCancellation cancellation = new AgentCancellation();
			ScheduledFuture<?> timeout = timeoutScheduler.schedule(
					cancellation::timeout, responseTimeoutMillis, TimeUnit.MILLISECONDS);
			try {
				ConversationResult result = agentCore.converseStreaming(
						new ConversationRequest(
								message.messageId(),
								conversationBindingRepository.findConversationId(
										message.chatId(), message.senderOpenId()).orElse(null),
								message.senderOpenId(),
								message.content()),
						event -> applyToLarkStream(writer, event),
						cancellation);
				conversationBindingRepository.bind(
						message.chatId(), message.senderOpenId(), result.conversationId());
			}
			finally {
				timeout.cancel(false);
			}
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

	@PreDestroy
	void shutdownTimeoutScheduler() {
		timeoutScheduler.shutdown();
	}

	private void respondUnsupported(InboundLarkMessage message) {
		channelClient.respond(message, UNSUPPORTED_REPLY)
				.whenComplete((replyMessageId, failure) -> {
					if (failure == null) {
						messageRepository.markReplied(message.messageId(), replyMessageId);
						return;
					}
					messageRepository.markFailed(message.messageId(), "LARK_REPLY_FAILED");
					log.warn("Lark reply failed for a claimed message ({})", failureType(failure));
				});
	}

	private static void applyToLarkStream(
			LarkChannelClient.StreamWriter writer,
			AgentLifecycleEvent event) {
		if (event.state() == AgentLifecycleEvent.State.CONTENT_DELTA) {
			writer.append(event.contentDelta());
		}
		else if (event.state() == AgentLifecycleEvent.State.CONTENT_RESET) {
			writer.setContent("");
		}
		else if (event.state() == AgentLifecycleEvent.State.FAILED) {
			writer.setContent(event.safeMessage());
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

	private static String failureType(Throwable failure) {
		Throwable cause = failure instanceof CompletionException && failure.getCause() != null
				? failure.getCause()
				: failure;
		return cause.getClass().getSimpleName();
	}
}
