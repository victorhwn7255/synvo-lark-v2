package synvo.lark.channel;

import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import synvo.configuration.LarkProperties;
import synvo.agent.ConversationRunCoordinator;
import synvo.lark.channel.LarkChannelClient.Signal;
import synvo.persistence.LarkConversationBindingRepository;
import synvo.persistence.LarkMessageProcessingRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

class LarkChannelLifecycleTests {

	@Test
	void representsConnectReconnectErrorAndShutdownStates() {
		FakeChannelClient client = new FakeChannelClient();
		LarkConnectionStatus status = new LarkConnectionStatus(properties());
		LarkDirectMessageHandler handler = new LarkDirectMessageHandler(
				properties(), mock(LarkMessageProcessingRepository.class),
				mock(LarkConversationBindingRepository.class), client,
				mock(ConversationRunCoordinator.class));
		LarkChannelLifecycle lifecycle = new LarkChannelLifecycle(client, handler, status);

		lifecycle.connect();
		assertEquals(LarkConnectionState.CONNECTED, status.snapshot().state());

		client.signal(Signal.RECONNECTING);
		assertEquals(LarkConnectionState.RECONNECTING, status.snapshot().state());
		client.signal(Signal.RECONNECTED);
		assertEquals(LarkConnectionState.CONNECTED, status.snapshot().state());
		client.signal(Signal.ERROR);
		assertEquals(LarkConnectionState.FAILED, status.snapshot().state());

		lifecycle.disconnect();
		assertEquals(LarkConnectionState.DISABLED, status.snapshot().state());
		assertEquals(1, client.disconnectCount);
	}

	@Test
	void representsInitialConnectionFailure() {
		FakeChannelClient client = new FakeChannelClient();
		client.connectResult = CompletableFuture.failedFuture(new IllegalStateException("offline"));
		LarkConnectionStatus status = new LarkConnectionStatus(properties());
		LarkDirectMessageHandler handler = new LarkDirectMessageHandler(
				properties(), mock(LarkMessageProcessingRepository.class),
				mock(LarkConversationBindingRepository.class), client,
				mock(ConversationRunCoordinator.class));

		new LarkChannelLifecycle(client, handler, status).connect();

		assertEquals(LarkConnectionState.FAILED, status.snapshot().state());
	}

	private static LarkProperties properties() {
		return new LarkProperties(
				true, "cli-test", "secret-test", "websocket", "ou-victor", null,
				Base64.getEncoder().encodeToString(new byte[32]),
				Duration.ofMinutes(5), Duration.ofDays(30));
	}

	private static final class FakeChannelClient implements LarkChannelClient {
		private CompletableFuture<BotProfile> connectResult = CompletableFuture.completedFuture(
				new BotProfile("ou-bot", "Synvo"));
		private Consumer<Signal> signalHandler;
		private int disconnectCount;

		@Override
		public void onMessage(Consumer<InboundLarkMessage> handler) {
		}

		@Override
		public void onSignal(Consumer<Signal> handler) {
			this.signalHandler = handler;
		}

		@Override
		public CompletableFuture<BotProfile> connect() {
			return connectResult;
		}

		@Override
		public CompletableFuture<Void> disconnect() {
			disconnectCount++;
			return CompletableFuture.completedFuture(null);
		}

		@Override
		public CompletableFuture<String> respond(InboundLarkMessage message, String text) {
			return CompletableFuture.completedFuture("reply-test");
		}

		@Override
		public CompletableFuture<String> stream(
				InboundLarkMessage message,
				StreamProducer producer) {
			return CompletableFuture.completedFuture("stream-test");
		}

		void signal(Signal signal) {
			signalHandler.accept(signal);
		}
	}
}
