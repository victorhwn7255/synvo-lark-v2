package synvo.persistence;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import synvo.configuration.LarkProperties;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LarkRetentionCleanupTests {

	@Test
	void removesExpiredOperationalMetadataOnceAtStartup() {
		LarkMessageProcessingRepository messages = mock(LarkMessageProcessingRepository.class);
		AuthorizationCodeClaimRepository codes = mock(AuthorizationCodeClaimRepository.class);
		LarkProperties properties = new LarkProperties(
				false,
				null,
				null,
				"websocket",
				null,
				null,
				null,
				Duration.ofMinutes(5),
				Duration.ofDays(30));
		LarkRetentionCleanup cleanup = new LarkRetentionCleanup(messages, codes, properties);
		Instant now = Instant.parse("2026-08-17T00:00:00Z");

		cleanup.cleanupAt(now);

		verify(messages).deleteReceivedBefore(Instant.parse("2026-07-18T00:00:00Z"));
		verify(codes).deleteClaimedBefore(Instant.parse("2026-08-16T00:00:00Z"));
	}
}
