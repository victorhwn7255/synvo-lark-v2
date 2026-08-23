package synvo.lark.channel;

import com.lark.oapi.channel.LarkChannel;
import com.lark.oapi.channel.model.BotIdentity;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OfficialLarkChannelClientRecoveryTests {

	@Test
	void replacesDisposedVendorChannelDuringRestart() {
		LarkChannel abandoned = mock(LarkChannel.class);
		LarkChannel replacement = mock(LarkChannel.class);
		BotIdentity identity = mock(BotIdentity.class);
		when(identity.getOpenId()).thenReturn("ou-bot");
		when(identity.getName()).thenReturn("Synvo");
		when(abandoned.disconnect()).thenReturn(CompletableFuture.completedFuture(null));
		when(replacement.connect()).thenReturn(CompletableFuture.completedFuture(identity));
		Supplier<LarkChannel> factory = new OrderedChannelFactory(abandoned, replacement);
		OfficialLarkChannelClient client = new OfficialLarkChannelClient(factory);

		LarkChannelClient.BotProfile profile = client.restart().join();

		assertEquals("ou-bot", profile.openId());
		assertEquals("Synvo", profile.displayName());
		var sequence = inOrder(abandoned, replacement);
		sequence.verify(abandoned).disconnect();
		sequence.verify(replacement).connect();
	}

	private static final class OrderedChannelFactory implements Supplier<LarkChannel> {
		private final LarkChannel[] channels;
		private int index;

		private OrderedChannelFactory(LarkChannel... channels) {
			this.channels = channels;
		}

		@Override
		public LarkChannel get() {
			return channels[index++];
		}
	}
}
