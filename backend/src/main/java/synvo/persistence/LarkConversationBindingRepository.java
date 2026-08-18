package synvo.persistence;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LarkConversationBindingRepository {

	private final JdbcClient jdbcClient;

	public LarkConversationBindingRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public Optional<UUID> findConversationId(String chatId, String ownerOpenId) {
		return jdbcClient.sql("""
				SELECT conversation_id
				FROM lark_chat_conversation
				WHERE chat_id = :chatId AND owner_open_id = :ownerOpenId
				""")
				.param("chatId", chatId)
				.param("ownerOpenId", ownerOpenId)
				.query(UUID.class)
				.optional();
	}

	public void bind(String chatId, String ownerOpenId, UUID conversationId) {
		OffsetDateTime now = Instant.now().atOffset(ZoneOffset.UTC);
		jdbcClient.sql("""
				INSERT INTO lark_chat_conversation (
				    chat_id, owner_open_id, conversation_id, created_at, updated_at
				)
				VALUES (:chatId, :ownerOpenId, :conversationId, :now, :now)
				ON CONFLICT (chat_id) DO NOTHING
				""")
				.param("chatId", chatId)
				.param("ownerOpenId", ownerOpenId)
				.param("conversationId", conversationId)
				.param("now", now)
				.update();
	}
}
