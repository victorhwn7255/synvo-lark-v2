package synvo.lark.channel;

import com.lark.oapi.channel.LarkChannel;
import com.lark.oapi.channel.LarkChannelFactory;
import com.lark.oapi.channel.config.LarkChannelOptions;
import com.lark.oapi.channel.model.BotIdentity;
import com.lark.oapi.channel.model.NormalizedMessage;
import com.lark.oapi.channel.model.SendInput;
import com.lark.oapi.channel.model.SendOptions;
import com.lark.oapi.channel.model.SendResult;
import com.lark.oapi.core.enums.BaseUrlEnum;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import synvo.configuration.LarkProperties;

@Component
@ConditionalOnProperty(prefix = "synvo.lark", name = "enabled", havingValue = "true")
final class OfficialLarkChannelClient implements LarkChannelClient {

	private final LarkChannel channel;

	OfficialLarkChannelClient(LarkProperties properties) {
		LarkChannelOptions.PolicyConfig policy = new LarkChannelOptions.PolicyConfig();
		policy.setDmMode("allowlist");
		policy.setDmAllowlist(properties.pilotOpenId());
		policy.setGroupAllowlist();
		policy.setRequireMention(true);

		LarkChannelOptions options = LarkChannelOptions
				.newBuilder(properties.appId(), properties.appSecret())
				.transport(properties.transport())
				.domain(BaseUrlEnum.LarkSuite.getUrl())
				.policy(policy)
				.source("synvo-assistant")
				.includeRawEvent(false)
				.build();
		this.channel = LarkChannelFactory.createLarkChannel(options);
	}

	@Override
	public void onMessage(Consumer<InboundLarkMessage> handler) {
		channel.<NormalizedMessage>on("message", message -> handler.accept(mapMessage(message)));
	}

	@Override
	public void onSignal(Consumer<Signal> handler) {
		channel.<Object>on("reconnecting", ignored -> handler.accept(Signal.RECONNECTING));
		channel.<Object>on("reconnected", ignored -> handler.accept(Signal.RECONNECTED));
		channel.<Object>on("error", ignored -> handler.accept(Signal.ERROR));
	}

	@Override
	public CompletableFuture<BotProfile> connect() {
		return channel.connect().thenApply(OfficialLarkChannelClient::mapBotProfile);
	}

	@Override
	public CompletableFuture<Void> disconnect() {
		return channel.disconnect();
	}

	@Override
	public CompletableFuture<String> respond(InboundLarkMessage message, String text) {
		SendOptions options = responseOptions(message);
		CompletableFuture<SendResult> response = options == null
				? channel.send(message.chatId(), SendInput.text(text))
				: channel.send(message.chatId(), SendInput.text(text), options);
		return response
				.thenApply(result -> result.getMessageId());
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
}
