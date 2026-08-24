package synvo.lark.channel;

import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import synvo.configuration.LarkProperties;
import synvo.agent.ConversationQueries;
import synvo.agent.ConversationRunCoordinator;
import synvo.lark.channel.LarkChannelClient.Signal;
import synvo.persistence.LarkConversationBindingRepository;
import synvo.persistence.LarkMessageProcessingRepository;
import synvo.workspaceagent.WorkspaceConversationAgent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LarkChannelLifecycleTests {

	@Test
	void representsConnectReconnectErrorAndShutdownStates() {
		FakeChannelClient client = new FakeChannelClient();
		LarkConnectionStatus status = new LarkConnectionStatus(properties());
		LarkDirectMessageHandler handler = new LarkDirectMessageHandler(
				properties(), mock(LarkMessageProcessingRepository.class),
				mock(LarkConversationBindingRepository.class), client,
				mock(ConversationRunCoordinator.class), mock(ConversationQueries.class),
				mock(WorkspaceConversationAgent.class));
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
				mock(ConversationRunCoordinator.class), mock(ConversationQueries.class),
				mock(WorkspaceConversationAgent.class));

		new LarkChannelLifecycle(client, handler, status).connect();

		assertEquals(LarkConnectionState.FAILED, status.snapshot().state());
	}

	@Test
	void replacesChannelWhenReconnectRemainsStalled() {
		FakeChannelClient client = new FakeChannelClient();
		LarkConnectionStatus status = new LarkConnectionStatus(properties());
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
		when(scheduler.schedule(any(Runnable.class), eq(30_000L), eq(TimeUnit.MILLISECONDS)))
				.thenAnswer(ignored -> scheduled);
		LarkChannelLifecycle lifecycle = new LarkChannelLifecycle(
				client, handler(client), status, scheduler, Duration.ofSeconds(30));

		lifecycle.connect();
		client.signal(Signal.RECONNECTING);
		client.signal(Signal.RECONNECTING);
		lifecycle.recoverIfStalled();

		assertEquals(1, client.restartCount);
		assertEquals(LarkConnectionState.CONNECTED, status.snapshot().state());
		verify(scheduler, times(1)).schedule(any(Runnable.class), eq(30_000L), eq(TimeUnit.MILLISECONDS));

		lifecycle.disconnect();
		verify(scheduler).shutdownNow();
	}

	@Test
	void cancelsRecoveryWhenVendorReconnectsBeforeTimeout() {
		FakeChannelClient client = new FakeChannelClient();
		LarkConnectionStatus status = new LarkConnectionStatus(properties());
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		ScheduledFuture<?> scheduled = mock(ScheduledFuture.class);
		when(scheduler.schedule(any(Runnable.class), eq(30_000L), eq(TimeUnit.MILLISECONDS)))
				.thenAnswer(ignored -> scheduled);
		LarkChannelLifecycle lifecycle = new LarkChannelLifecycle(
				client, handler(client), status, scheduler, Duration.ofSeconds(30));

		lifecycle.connect();
		client.signal(Signal.RECONNECTING);
		client.signal(Signal.RECONNECTED);
		lifecycle.recoverIfStalled();

		assertEquals(0, client.restartCount);
		assertEquals(LarkConnectionState.CONNECTED, status.snapshot().state());
		verify(scheduled).cancel(false);

		lifecycle.disconnect();
	}

	@Test
	void reportsFailedWhenStalledChannelReplacementFails() {
		FakeChannelClient client = new FakeChannelClient();
		client.restartResult = CompletableFuture.failedFuture(new IllegalStateException("offline"));
		LarkConnectionStatus status = new LarkConnectionStatus(properties());
		ScheduledExecutorService scheduler = mock(ScheduledExecutorService.class);
		when(scheduler.schedule(any(Runnable.class), eq(30_000L), eq(TimeUnit.MILLISECONDS)))
				.thenAnswer(ignored -> mock(ScheduledFuture.class));
		LarkChannelLifecycle lifecycle = new LarkChannelLifecycle(
				client, handler(client), status, scheduler, Duration.ofSeconds(30));

		lifecycle.connect();
		client.signal(Signal.RECONNECTING);
		lifecycle.recoverIfStalled();

		assertEquals(1, client.restartCount);
		assertEquals(LarkConnectionState.FAILED, status.snapshot().state());

		lifecycle.disconnect();
	}

	private static LarkDirectMessageHandler handler(FakeChannelClient client) {
		return new LarkDirectMessageHandler(
				properties(), mock(LarkMessageProcessingRepository.class),
				mock(LarkConversationBindingRepository.class), client,
				mock(ConversationRunCoordinator.class), mock(ConversationQueries.class),
				mock(WorkspaceConversationAgent.class));
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
		private CompletableFuture<BotProfile> restartResult = CompletableFuture.completedFuture(
				new BotProfile("ou-bot", "Synvo"));
		private int restartCount;

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
		public CompletableFuture<BotProfile> restart() {
			restartCount++;
			return restartResult;
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
