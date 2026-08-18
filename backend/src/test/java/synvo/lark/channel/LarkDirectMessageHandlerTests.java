package synvo.lark.channel;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import synvo.agent.AgentIntent;
import synvo.agent.AgentLifecycleEvent;
import synvo.agent.ConversationRequest;
import synvo.agent.ConversationResult;
import synvo.agent.SynvoAgentCore;
import synvo.configuration.LarkProperties;
import synvo.configuration.AgentRuntimeProperties;
import synvo.persistence.LarkConversationBindingRepository;
import synvo.persistence.LarkMessageProcessingRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LarkDirectMessageHandlerTests {

	private LarkMessageProcessingRepository repository;
	private LarkConversationBindingRepository conversationBindings;
	private LarkChannelClient channelClient;
	private SynvoAgentCore agentCore;
	private LarkDirectMessageHandler handler;

	@BeforeEach
	void setUp() {
		repository = mock(LarkMessageProcessingRepository.class);
		conversationBindings = mock(LarkConversationBindingRepository.class);
		channelClient = mock(LarkChannelClient.class);
		agentCore = mock(SynvoAgentCore.class);
		handler = new LarkDirectMessageHandler(
				properties(), repository, conversationBindings, channelClient, agentCore,
				new AgentRuntimeProperties(Duration.ofMinutes(2)));
		handler.setBotOpenId("ou-bot");
	}

	@Test
	void acceptedVictorTextStreamsOneAgentResponseAndBindsConversation() {
		InboundLarkMessage message = message("m-1", "p2p", "ou-victor", "text", "hello");
		UUID conversationId = UUID.randomUUID();
		UUID runId = UUID.randomUUID();
		FakeStreamWriter writer = new FakeStreamWriter();
		when(repository.tryClaim(eq("m-1"), eq("ou-victor"), eq("p2p"), any())).thenReturn(true);
		when(conversationBindings.findConversationId("chat-1", "ou-victor"))
				.thenReturn(Optional.empty());
		when(channelClient.stream(eq(message), any())).thenAnswer(invocation -> {
			LarkChannelClient.StreamProducer producer = invocation.getArgument(1);
			producer.produce(writer);
			return CompletableFuture.completedFuture("reply-1");
		});
		when(agentCore.converseStreaming(any(), any(), any())).thenAnswer(invocation -> {
			Consumer<AgentLifecycleEvent> listener = invocation.getArgument(1);
			listener.accept(new AgentLifecycleEvent(1, AgentLifecycleEvent.State.ACCEPTED, "Request accepted"));
			listener.accept(new AgentLifecycleEvent(2, AgentLifecycleEvent.State.STREAMING, "Writing a response"));
			listener.accept(AgentLifecycleEvent.contentDelta(3, "Discarded partial response"));
			listener.accept(AgentLifecycleEvent.contentReset(4));
			listener.accept(AgentLifecycleEvent.contentDelta(5, "Hello Victor."));
			listener.accept(new AgentLifecycleEvent(6, AgentLifecycleEvent.State.COMPLETED, null));
			return result(conversationId, runId, ConversationResult.Status.COMPLETED, "Hello Victor.");
		});

		handler.handle(message);

		ArgumentCaptor<ConversationRequest> request = ArgumentCaptor.forClass(ConversationRequest.class);
		verify(agentCore).converseStreaming(request.capture(), any(), any());
		assertEquals("m-1", request.getValue().requestId());
		assertEquals("hello", request.getValue().content());
		assertEquals("Hello Victor.", writer.content.toString());
		verify(conversationBindings).bind("chat-1", "ou-victor", conversationId);
		verify(repository).markReplied("m-1", "reply-1");
	}

	@Test
	void failedAgentRunReplacesPartialLarkTextWithOneSafeFinalState() {
		InboundLarkMessage message = message("m-failure", "p2p", "ou-victor", "text", "hello");
		FakeStreamWriter writer = new FakeStreamWriter();
		when(repository.tryClaim(eq("m-failure"), eq("ou-victor"), eq("p2p"), any()))
				.thenReturn(true);
		when(conversationBindings.findConversationId("chat-1", "ou-victor"))
				.thenReturn(Optional.empty());
		when(channelClient.stream(eq(message), any())).thenAnswer(invocation -> {
			LarkChannelClient.StreamProducer producer = invocation.getArgument(1);
			producer.produce(writer);
			return CompletableFuture.completedFuture("reply-failure");
		});
		when(agentCore.converseStreaming(any(), any(), any())).thenAnswer(invocation -> {
			Consumer<AgentLifecycleEvent> listener = invocation.getArgument(1);
			listener.accept(AgentLifecycleEvent.contentDelta(1, "Partial private output"));
			listener.accept(new AgentLifecycleEvent(
					2, AgentLifecycleEvent.State.FAILED, "I couldn’t complete that response. Please try again."));
			return result(
					UUID.randomUUID(), UUID.randomUUID(), ConversationResult.Status.FAILED,
					"I couldn’t complete that response. Please try again.");
		});

		handler.handle(message);

		assertEquals("I couldn’t complete that response. Please try again.", writer.content.toString());
		verify(repository).markReplied("m-failure", "reply-failure");
	}

	@Test
	void failedLarkStreamFallsBackToOneSafeNormalMessage() {
		InboundLarkMessage message = message("m-stream-failure", "p2p", "ou-victor", "text", "hello");
		when(repository.tryClaim(eq("m-stream-failure"), eq("ou-victor"), eq("p2p"), any()))
				.thenReturn(true);
		when(conversationBindings.findConversationId("chat-1", "ou-victor"))
				.thenReturn(Optional.empty());
		when(channelClient.stream(eq(message), any()))
				.thenReturn(CompletableFuture.failedFuture(new IllegalStateException("stream unavailable")));
		when(channelClient.respond(message, LarkDirectMessageHandler.DELIVERY_FAILURE_REPLY))
				.thenReturn(CompletableFuture.completedFuture("fallback-reply"));

		handler.handle(message);

		verify(agentCore, never()).converseStreaming(any(), any(), any());
		verify(channelClient).respond(message, LarkDirectMessageHandler.DELIVERY_FAILURE_REPLY);
		verify(repository).markReplied("m-stream-failure", "fallback-reply");
		verify(repository, never()).markFailed(any(), any());
	}

	@Test
	void failedLarkStreamAndFallbackMarkTheMessageFailed() {
		InboundLarkMessage message = message("m-total-failure", "p2p", "ou-victor", "text", "hello");
		when(repository.tryClaim(eq("m-total-failure"), eq("ou-victor"), eq("p2p"), any()))
				.thenReturn(true);
		when(conversationBindings.findConversationId("chat-1", "ou-victor"))
				.thenReturn(Optional.empty());
		when(channelClient.stream(eq(message), any()))
				.thenReturn(CompletableFuture.failedFuture(new IllegalStateException("stream unavailable")));
		when(channelClient.respond(message, LarkDirectMessageHandler.DELIVERY_FAILURE_REPLY))
				.thenReturn(CompletableFuture.failedFuture(new CompletionException(
						new IllegalStateException("fallback unavailable"))));

		handler.handle(message);

		verify(repository).markFailed("m-total-failure", "LARK_REPLY_FAILED");
	}

	@Test
	void rejectsOtherUsersGroupsAndBotMessagesWithoutClaimingOrReplying() {
		handler.handle(message("m-other", "p2p", "ou-other", "text", "hello"));
		handler.handle(message("m-group", "group", "ou-victor", "text", "hello"));
		handler.setBotOpenId("ou-victor");
		handler.handle(message("m-bot", "p2p", "ou-victor", "text", "hello"));

		verify(repository, never()).tryClaim(any(), any(), any(), any());
		verify(channelClient, never()).respond(any(), any());
		verify(channelClient, never()).stream(any(), any());
	}

	@Test
	void sendsSafeUnsupportedReplyForVictorMedia() {
		when(repository.tryClaim(eq("m-image"), eq("ou-victor"), eq("p2p"), any())).thenReturn(true);
		InboundLarkMessage message = message("m-image", "p2p", "ou-victor", "image", null);
		when(channelClient.respond(message, LarkDirectMessageHandler.UNSUPPORTED_REPLY))
				.thenReturn(CompletableFuture.completedFuture("reply-image"));

		handler.handle(message);

		verify(channelClient).respond(message, LarkDirectMessageHandler.UNSUPPORTED_REPLY);
		verify(channelClient, never()).stream(any(), any());
		verify(repository).markReplied("m-image", "reply-image");
	}

	@Test
	void duplicateMessageDoesNotCreateAnotherAgentRunOrResponse() {
		InboundLarkMessage duplicate = message("m-duplicate", "p2p", "ou-victor", "text", "hello");
		when(repository.tryClaim(eq("m-duplicate"), eq("ou-victor"), eq("p2p"), any()))
				.thenReturn(false);

		handler.handle(duplicate);
		handler.handle(duplicate);

		verify(channelClient, never()).respond(any(), any());
		verify(channelClient, never()).stream(any(), any());
		verify(agentCore, never()).converseStreaming(any(), any(), any());
	}

	private static ConversationResult result(
			UUID conversationId,
			UUID runId,
			ConversationResult.Status status,
			String response) {
		return new ConversationResult(
				conversationId,
				runId,
				AgentIntent.DIRECT_ANSWER,
				status == ConversationResult.Status.COMPLETED
						? ConversationResult.Outcome.DIRECT_ANSWER
						: ConversationResult.Outcome.FAILED,
				status,
				response,
				List.of(),
				false);
	}

	private static InboundLarkMessage message(
			String id, String chatType, String sender, String contentType, String content) {
		return new InboundLarkMessage(
				id, "chat-1", chatType, sender, content, contentType,
				null, null, null, Instant.now());
	}

	private static LarkProperties properties() {
		return new LarkProperties(
				true,
				"cli-test",
				"secret-test",
				"websocket",
				"ou-victor",
				null,
				Base64.getEncoder().encodeToString(new byte[32]),
				Duration.ofMinutes(5),
				Duration.ofDays(30));
	}

	private static final class FakeStreamWriter implements LarkChannelClient.StreamWriter {

		private final StringBuilder content = new StringBuilder();

		@Override
		public void append(String delta) {
			content.append(delta);
		}

		@Override
		public void setContent(String replacement) {
			content.setLength(0);
			content.append(replacement);
		}
	}
}
