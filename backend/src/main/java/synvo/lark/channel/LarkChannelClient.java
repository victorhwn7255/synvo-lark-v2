package synvo.lark.channel;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import synvo.agent.AgentLifecycleEvent.ActionHandoff;

public interface LarkChannelClient {

	void onMessage(Consumer<InboundLarkMessage> handler);

	void onSignal(Consumer<Signal> handler);

	CompletableFuture<BotProfile> connect();

	/** Replace the current transport, retain registered handlers, and connect the replacement. */
	CompletableFuture<BotProfile> restart();

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

		default void showActionRequired(ActionHandoff handoff, String h5Url) {
		}

		default void clearActionRequired() {
		}
	}

	record BotProfile(String openId, String displayName) {
	}

	enum Signal {
		RECONNECTING,
		RECONNECTED,
		ERROR
	}
}
