package synvo.lark.channel;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import synvo.configuration.LarkProperties;
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
	private LarkChannelClient channelClient;
	private LarkDirectMessageHandler handler;

	@BeforeEach
	void setUp() {
		repository = mock(LarkMessageProcessingRepository.class);
		channelClient = mock(LarkChannelClient.class);
		handler = new LarkDirectMessageHandler(properties(), repository, channelClient);
		handler.setBotOpenId("ou-bot");
	}

	@Test
	void acceptsVictorTextAndRepliesToTheOriginatingMessage() {
		InboundLarkMessage message = message("m-1", "p2p", "ou-victor", "text", "hello");
		when(repository.tryClaim(eq("m-1"), eq("ou-victor"), eq("p2p"), any())).thenReturn(true);
		when(channelClient.respond(message, LarkDirectMessageHandler.READY_REPLY))
				.thenReturn(CompletableFuture.completedFuture("reply-1"));

		handler.handle(message);

		verify(channelClient).respond(message, LarkDirectMessageHandler.READY_REPLY);
		verify(repository).markReplied("m-1", "reply-1");
		assertEquals(
				"Synvo is connected and ready. AI conversations and workflows will be enabled in the next phase.",
				LarkDirectMessageHandler.READY_REPLY);
	}

	@Test
	void rejectsOtherUsersGroupsAndBotMessagesWithoutClaimingOrReplying() {
		handler.handle(message("m-other", "p2p", "ou-other", "text", "hello"));
		handler.handle(message("m-group", "group", "ou-victor", "text", "hello"));
		handler.setBotOpenId("ou-victor");
		handler.handle(message("m-bot", "p2p", "ou-victor", "text", "hello"));

		verify(repository, never()).tryClaim(any(), any(), any(), any());
		verify(channelClient, never()).respond(any(), any());
	}

	@Test
	void sendsSafeUnsupportedReplyForVictorMedia() {
		when(repository.tryClaim(eq("m-image"), eq("ou-victor"), eq("p2p"), any())).thenReturn(true);
		InboundLarkMessage message = message("m-image", "p2p", "ou-victor", "image", null);
		when(channelClient.respond(message, LarkDirectMessageHandler.UNSUPPORTED_REPLY))
				.thenReturn(CompletableFuture.completedFuture("reply-image"));

		handler.handle(message);

		verify(channelClient).respond(message, LarkDirectMessageHandler.UNSUPPORTED_REPLY);
		verify(repository).markReplied("m-image", "reply-image");
	}

	@Test
	void duplicateMessageDoesNotReplyAgain() {
		InboundLarkMessage duplicate = message("m-duplicate", "p2p", "ou-victor", "text", "hello");
		when(repository.tryClaim(eq("m-duplicate"), eq("ou-victor"), eq("p2p"), any()))
				.thenReturn(false);

		handler.handle(duplicate);
		handler.handle(duplicate);

		verify(channelClient, never()).respond(any(), any());
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
}
