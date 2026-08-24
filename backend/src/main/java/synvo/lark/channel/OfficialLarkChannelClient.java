package synvo.lark.channel;

import com.lark.oapi.channel.LarkChannel;
import com.lark.oapi.channel.LarkChannelFactory;
import com.lark.oapi.channel.config.LarkChannelOptions;
import com.lark.oapi.channel.model.BotIdentity;
import com.lark.oapi.channel.model.NormalizedMessage;
import com.lark.oapi.channel.model.SendInput;
import com.lark.oapi.channel.model.SendOptions;
import com.lark.oapi.channel.model.SendResult;
import com.lark.oapi.channel.model.StreamInput;
import com.lark.oapi.core.enums.BaseUrlEnum;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import synvo.configuration.LarkProperties;

@Component
@ConditionalOnProperty(prefix = "synvo.lark", name = "enabled", havingValue = "true")
final class OfficialLarkChannelClient implements LarkChannelClient {

	private static final Logger log = LoggerFactory.getLogger(OfficialLarkChannelClient.class);

	private final Object channelMonitor = new Object();
	private final Supplier<LarkChannel> channelFactory;
	private volatile LarkChannel channel;
	private volatile Consumer<InboundLarkMessage> messageHandler;
	private volatile Consumer<Signal> signalHandler;
	private CompletableFuture<BotProfile> restartPromise;
	private volatile boolean closed;

	@Autowired
	OfficialLarkChannelClient(LarkProperties properties) {
		this(channelFactory(properties));
	}

	OfficialLarkChannelClient(Supplier<LarkChannel> channelFactory) {
		this.channelFactory = channelFactory;
		this.channel = channelFactory.get();
	}

	private static Supplier<LarkChannel> channelFactory(LarkProperties properties) {
		LarkChannelOptions.PolicyConfig policy = new LarkChannelOptions.PolicyConfig();
		policy.setDmMode("allowlist");
		policy.setDmAllowlist(properties.pilotOpenId());
		policy.setGroupAllowlist();
		policy.setRequireMention(true);

		LarkChannelOptions.OutboundConfig outbound = new LarkChannelOptions.OutboundConfig();
		outbound.setStreamThrottleMs(150);
		outbound.setStreamThrottleChars(Integer.MAX_VALUE);

		LarkChannelOptions options = LarkChannelOptions
				.newBuilder(properties.appId(), properties.appSecret())
				.transport(properties.transport())
				.domain(BaseUrlEnum.LarkSuite.getUrl())
				.policy(policy)
				.outbound(outbound)
				.source("synvo-assistant")
				.includeRawEvent(false)
				.build();
		return () -> LarkChannelFactory.createLarkChannel(options);
	}

	@Override
	public void onMessage(Consumer<InboundLarkMessage> handler) {
		synchronized (channelMonitor) {
			messageHandler = handler;
			wireMessageHandler(channel);
		}
	}

	@Override
	public void onSignal(Consumer<Signal> handler) {
		synchronized (channelMonitor) {
			signalHandler = handler;
			wireSignalHandler(channel);
		}
	}

	@Override
	public CompletableFuture<BotProfile> connect() {
		return channel.connect().thenApply(OfficialLarkChannelClient::mapBotProfile);
	}

	@Override
	public CompletableFuture<BotProfile> restart() {
		synchronized (channelMonitor) {
			if (closed) {
				return CompletableFuture.failedFuture(new IllegalStateException("Lark channel client is closed"));
			}
			if (restartPromise != null) {
				return restartPromise;
			}

			LarkChannel abandoned = channel;
			LarkChannel replacement = channelFactory.get();
			wireMessageHandler(replacement);
			wireSignalHandler(replacement);
			channel = replacement;

			CompletableFuture<BotProfile> recovery = abandoned.disconnect()
					.handle((ignored, failure) -> {
						if (failure != null) {
							log.warn("The abandoned Lark channel did not close cleanly ({})", failureType(failure));
						}
						return null;
					})
					.thenCompose(ignored -> connectReplacement(replacement))
					.thenApply(OfficialLarkChannelClient::mapBotProfile);
			restartPromise = recovery;
			recovery.whenComplete((ignored, failure) -> clearRestartPromise(recovery));
			return recovery;
		}
	}

	@Override
	public CompletableFuture<Void> disconnect() {
		LarkChannel current;
		synchronized (channelMonitor) {
			closed = true;
			current = channel;
		}
		return current.disconnect();
	}

	@Override
	public CompletableFuture<String> respond(InboundLarkMessage message, String text) {
		SendOptions options = responseOptions(message);
		CompletableFuture<SendResult> response = options == null
				? channel.send(message.chatId(), SendInput.text(text))
				: channel.send(message.chatId(), SendInput.text(text), options);
		return response.thenApply(SendResult::getMessageId);
	}

	@Override
	public CompletableFuture<String> stream(InboundLarkMessage message, StreamProducer producer) {
		SendOptions options = responseOptions(message);
		StreamInput input = StreamInput.card(
				SynvoLarkStreamCard.initialCard(),
				controller -> producer.produce(new SynvoLarkStreamCard(controller)));
		CompletableFuture<SendResult> response = options == null
				? channel.stream(message.chatId(), input)
				: channel.stream(message.chatId(), input, options);
		return response.thenApply(SendResult::getMessageId);
	}

	static SendOptions responseOptions(InboundLarkMessage message) {
		boolean explicitReplyContext = StringUtils.hasText(message.replyToMessageId())
				|| StringUtils.hasText(message.rootMessageId())
				|| StringUtils.hasText(message.threadId());
		if ("p2p".equals(message.chatType()) && !explicitReplyContext) {
			return null;
		}

		SendOptions.Builder options = SendOptions.newBuilder().replyTo(message.messageId());
		if (!"p2p".equals(message.chatType())
				&& (StringUtils.hasText(message.rootMessageId()) || StringUtils.hasText(message.threadId()))) {
			options.replyInThread(true);
		}
		return options.build();
	}

	private static InboundLarkMessage mapMessage(NormalizedMessage message) {
		long createTime = message.getCreateTime();
		Instant receivedAt = createTime > 0 ? Instant.ofEpochMilli(createTime) : Instant.now();
		return new InboundLarkMessage(
				message.getMessageId(),
				message.getChatId(),
				message.getChatType(),
				message.getSenderId(),
				message.getContent(),
				message.getRawContentType(),
				message.getRootId(),
				message.getThreadId(),
				message.getReplyToMessageId(),
				receivedAt);
	}

	private static BotProfile mapBotProfile(BotIdentity identity) {
		return new BotProfile(identity.getOpenId(), identity.getName());
	}

	private CompletableFuture<BotIdentity> connectReplacement(LarkChannel replacement) {
		synchronized (channelMonitor) {
			if (closed || channel != replacement) {
				return CompletableFuture.failedFuture(new IllegalStateException("Lark channel recovery was superseded"));
			}
			return replacement.connect();
		}
	}

	private void clearRestartPromise(CompletableFuture<BotProfile> completed) {
		synchronized (channelMonitor) {
			if (restartPromise == completed) {
				restartPromise = null;
			}
		}
	}

	private void wireMessageHandler(LarkChannel source) {
		if (messageHandler == null) {
			return;
		}
		source.<NormalizedMessage>on("message", message -> {
			Consumer<InboundLarkMessage> handler = messageHandler;
			if (isCurrent(source) && handler != null) {
				handler.accept(mapMessage(message));
			}
		});
	}

	private void wireSignalHandler(LarkChannel source) {
		if (signalHandler == null) {
			return;
		}
		source.<Object>on("reconnecting", ignored -> emitSignal(source, Signal.RECONNECTING));
		source.<Object>on("reconnected", ignored -> emitSignal(source, Signal.RECONNECTED));
		source.<Object>on("error", ignored -> emitSignal(source, Signal.ERROR));
	}

	private void emitSignal(LarkChannel source, Signal signal) {
		Consumer<Signal> handler = signalHandler;
		if (isCurrent(source) && handler != null) {
			handler.accept(signal);
		}
	}

	private boolean isCurrent(LarkChannel source) {
		return !closed && channel == source;
	}

	private static String failureType(Throwable failure) {
		Throwable cause = failure instanceof java.util.concurrent.CompletionException && failure.getCause() != null
				? failure.getCause()
				: failure;
		return cause.getClass().getSimpleName();
	}
}
