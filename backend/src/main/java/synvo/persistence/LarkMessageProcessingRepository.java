package synvo.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LarkMessageProcessingRepository {

	private final JdbcClient jdbcClient;

	public LarkMessageProcessingRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public boolean tryClaim(String messageId, String senderOpenId, String chatType, Instant receivedAt) {
		int inserted = jdbcClient.sql("""
				INSERT INTO lark_message_processing (
				    message_id, sender_open_id, chat_type, received_at,
				    processing_outcome, updated_at
				)
				VALUES (:messageId, :senderOpenId, :chatType, :receivedAt, 'PROCESSING', :now)
				ON CONFLICT (message_id) DO NOTHING
				""")
				.param("messageId", messageId)
				.param("senderOpenId", senderOpenId)
				.param("chatType", chatType)
				.param("receivedAt", atUtc(receivedAt))
				.param("now", atUtc(Instant.now()))
				.update();
		return inserted == 1;
	}

	public void markReplied(String messageId, String replyMessageId) {
		updateOutcome(messageId, "REPLIED", replyMessageId, null);
	}

	public void markFailed(String messageId, String errorCode) {
		updateOutcome(messageId, "FAILED", null, errorCode);
	}

	public boolean prepareFailedForExplicitRetry(String messageId) {
		return jdbcClient.sql("""
				UPDATE lark_message_processing
				SET processing_outcome = 'PROCESSING',
				    error_code = NULL,
				    attempt_count = attempt_count + 1,
				    updated_at = :now
				WHERE message_id = :messageId
				  AND processing_outcome = 'FAILED'
				""")
				.param("now", atUtc(Instant.now()))
				.param("messageId", messageId)
				.update() == 1;
	}

	public int deleteReceivedBefore(Instant cutoff) {
		return jdbcClient.sql("DELETE FROM lark_message_processing WHERE received_at < :cutoff")
				.param("cutoff", atUtc(cutoff))
				.update();
	}

	private void updateOutcome(String messageId, String outcome, String replyMessageId, String errorCode) {
		int updated = jdbcClient.sql("""
				UPDATE lark_message_processing
				SET processing_outcome = :outcome,
				    reply_message_id = :replyMessageId,
				    error_code = :errorCode,
				    updated_at = :now
				WHERE message_id = :messageId
				""")
				.param("outcome", outcome)
				.param("replyMessageId", replyMessageId)
				.param("errorCode", errorCode)
				.param("now", atUtc(Instant.now()))
				.param("messageId", messageId)
				.update();
		if (updated != 1) {
			throw new IllegalStateException("Message processing record was not found");
		}
	}

	private static OffsetDateTime atUtc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
