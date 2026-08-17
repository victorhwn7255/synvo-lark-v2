package synvo.persistence;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import synvo.configuration.LarkProperties;

@Component
class LarkRetentionCleanup {

	private static final Logger LOGGER = LoggerFactory.getLogger(LarkRetentionCleanup.class);
	private static final Duration AUTHORIZATION_CODE_RETENTION = Duration.ofDays(1);

	private final LarkMessageProcessingRepository messages;
	private final AuthorizationCodeClaimRepository authorizationCodes;
	private final LarkProperties properties;

	LarkRetentionCleanup(
			LarkMessageProcessingRepository messages,
			AuthorizationCodeClaimRepository authorizationCodes,
			LarkProperties properties) {
		this.messages = messages;
		this.authorizationCodes = authorizationCodes;
		this.properties = properties;
	}

	@EventListener(ApplicationReadyEvent.class)
	void onApplicationReady() {
		cleanupAt(Instant.now());
	}

	void cleanupAt(Instant now) {
		int deletedMessages = messages.deleteReceivedBefore(now.minus(properties.messageRetention()));
		int deletedCodes = authorizationCodes.deleteClaimedBefore(now.minus(AUTHORIZATION_CODE_RETENTION));
		if (deletedMessages > 0 || deletedCodes > 0) {
			LOGGER.info(
					"Lark retention cleanup removed {} message records and {} authorization-code claims",
					deletedMessages,
					deletedCodes);
		}
	}
}
