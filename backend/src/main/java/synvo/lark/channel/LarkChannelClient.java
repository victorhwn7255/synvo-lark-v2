package synvo.lark.channel;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface LarkChannelClient {

	void onMessage(Consumer<InboundLarkMessage> handler);

	void onSignal(Consumer<Signal> handler);

	CompletableFuture<BotProfile> connect();

	CompletableFuture<Void> disconnect();

	CompletableFuture<String> respond(InboundLarkMessage message, String text);

	CompletableFuture<String> stream(InboundLarkMessage message, StreamProducer producer);

	@FunctionalInterface
	interface StreamProducer {

		void produce(StreamWriter writer);
	}

	interface StreamWriter {

		void append(String delta);

		void setContent(String content);
	}

	record BotProfile(String openId, String displayName) {
	}

	enum Signal {
		RECONNECTING,
		RECONNECTED,
		ERROR
	}
}
