package synvo.lark.channel;

import com.lark.oapi.channel.model.SendOptions;
import java.time.Instant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LarkResponseRoutingTests {

	@Test
	void ordinaryDirectMessageCreatesANaturalChatMessage() {
		assertNull(OfficialLarkChannelClient.responseOptions(message("p2p", null, null, null)));
	}

	@Test
	void explicitDirectMessageReplyPreservesItsAnchor() {
		SendOptions options = OfficialLarkChannelClient.responseOptions(
				message("p2p", null, null, "parent-message"));

		assertEquals("message-1", options.getReplyTo());
		assertNull(options.getReplyInThread());
	}

	@Test
	void futureGroupConversationUsesAnAnchoredReply() {
		SendOptions options = OfficialLarkChannelClient.responseOptions(
				message("group", null, null, null));

		assertEquals("message-1", options.getReplyTo());
		assertNull(options.getReplyInThread());
	}

	@Test
	void futureGroupThreadStaysInThread() {
		SendOptions options = OfficialLarkChannelClient.responseOptions(
				message("group", "root-message", "thread-1", "parent-message"));

		assertEquals("message-1", options.getReplyTo());
		assertTrue(options.getReplyInThread());
	}

	private InboundLarkMessage message(
			String chatType, String rootMessageId, String threadId, String replyToMessageId) {
		return new InboundLarkMessage(
				"message-1",
				"chat-1",
				chatType,
				"ou-victor",
				"hello",
				"text",
				rootMessageId,
				threadId,
				replyToMessageId,
				Instant.now());
	}
}
