package synvo.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import synvo.agent.AgentIntent;

@Repository
public class ConversationQueryRepository {

	private static final int RECENT_LIMIT = 50;

	private final JdbcClient jdbcClient;

	public ConversationQueryRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	public List<ConversationSummary> listRecent(String ownerOpenId) {
		return jdbcClient.sql("""
				SELECT conversation_id, title, updated_at
				FROM conversation
				WHERE owner_open_id = :ownerOpenId
				ORDER BY updated_at DESC
				LIMIT :limit
				""")
				.param("ownerOpenId", ownerOpenId)
				.param("limit", RECENT_LIMIT)
				.query((resultSet, rowNumber) -> new ConversationSummary(
						resultSet.getObject("conversation_id", UUID.class),
						resultSet.getString("title"),
						toInstant(resultSet, "updated_at")))
				.list();
	}

	public Optional<ConversationDetail> findConversation(String ownerOpenId, UUID conversationId) {
		Optional<ConversationHeader> header = jdbcClient.sql("""
				SELECT conversation_id, title, updated_at
				FROM conversation
				WHERE conversation_id = :conversationId
				  AND owner_open_id = :ownerOpenId
				""")
				.param("conversationId", conversationId)
				.param("ownerOpenId", ownerOpenId)
				.query((resultSet, rowNumber) -> new ConversationHeader(
						resultSet.getObject("conversation_id", UUID.class),
						resultSet.getString("title"),
						toInstant(resultSet, "updated_at")))
				.optional();
		if (header.isEmpty()) {
			return Optional.empty();
		}

		List<ConversationTurn> turns = jdbcClient.sql("""
				SELECT turn_id, role, content, status, created_at, updated_at
				FROM conversation_turn
				WHERE conversation_id = :conversationId
				  AND superseded = FALSE
				ORDER BY ordinal
				""")
				.param("conversationId", conversationId)
				.query((resultSet, rowNumber) -> new ConversationTurn(
						resultSet.getObject("turn_id", UUID.class),
						Role.valueOf(resultSet.getString("role")),
						resultSet.getString("content"),
						TurnStatus.valueOf(resultSet.getString("status")),
						toInstant(resultSet, "created_at"),
						toInstant(resultSet, "updated_at")))
				.list();
		ConversationHeader value = header.orElseThrow();
		return Optional.of(new ConversationDetail(
				value.conversationId(), value.title(), value.updatedAt(), turns));
	}

	public Optional<RunDescriptor> findOwnedRun(String ownerOpenId, UUID runId) {
		return jdbcClient.sql("""
				SELECT r.run_id, r.request_id, r.conversation_id, r.user_turn_id,
				       r.assistant_turn_id, r.intent, r.status
				FROM agent_run r
				JOIN conversation c ON c.conversation_id = r.conversation_id
				WHERE r.run_id = :runId
				  AND c.owner_open_id = :ownerOpenId
				""")
				.param("runId", runId)
				.param("ownerOpenId", ownerOpenId)
				.query(ConversationQueryRepository::mapRun)
				.optional();
	}

	@Transactional
	public DeleteResult deleteOwnedConversation(String ownerOpenId, UUID conversationId) {
		String result = jdbcClient.sql("""
				WITH candidate AS MATERIALIZED (
				    SELECT c.conversation_id,
				           EXISTS (
				               SELECT 1
				               FROM agent_run r
				               WHERE r.conversation_id = c.conversation_id
				                 AND r.status = 'RUNNING'
				           ) AS has_active_run
				    FROM conversation c
				    WHERE c.conversation_id = :conversationId
				      AND c.owner_open_id = :ownerOpenId
				    FOR UPDATE
				), deleted AS (
				    DELETE FROM conversation c
				    USING candidate
				    WHERE c.conversation_id = candidate.conversation_id
				      AND NOT candidate.has_active_run
				    RETURNING c.conversation_id
				)
				SELECT CASE
				    WHEN EXISTS (SELECT 1 FROM deleted) THEN 'DELETED'
				    WHEN EXISTS (SELECT 1 FROM candidate) THEN 'ACTIVE_RUN'
				    ELSE 'NOT_FOUND'
				END AS result
				""")
				.param("conversationId", conversationId)
				.param("ownerOpenId", ownerOpenId)
				.query(String.class)
				.single();
		return DeleteResult.valueOf(result);
	}

	private static RunDescriptor mapRun(ResultSet resultSet, int rowNumber) throws SQLException {
		return new RunDescriptor(
				resultSet.getObject("run_id", UUID.class),
				resultSet.getString("request_id"),
				resultSet.getObject("conversation_id", UUID.class),
				resultSet.getObject("user_turn_id", UUID.class),
				resultSet.getObject("assistant_turn_id", UUID.class),
				AgentIntent.valueOf(resultSet.getString("intent")),
				RunStatus.valueOf(resultSet.getString("status")));
	}

	private static Instant toInstant(ResultSet resultSet, String column) throws SQLException {
		return resultSet.getObject(column, OffsetDateTime.class).toInstant();
	}

	private record ConversationHeader(UUID conversationId, String title, Instant updatedAt) {
	}

	public record ConversationSummary(UUID conversationId, String title, Instant updatedAt) {
	}

	public record ConversationDetail(
			UUID conversationId,
			String title,
			Instant updatedAt,
			List<ConversationTurn> turns
	) {
		public ConversationDetail {
			turns = List.copyOf(turns);
		}
	}

	public record ConversationTurn(
			UUID turnId,
			Role role,
			String content,
			TurnStatus status,
			Instant createdAt,
			Instant updatedAt
	) {
	}

	public record RunDescriptor(
			UUID runId,
			String requestId,
			UUID conversationId,
			UUID userTurnId,
			UUID assistantTurnId,
			AgentIntent intent,
			RunStatus status
	) {
	}

	public enum Role {
		USER,
		ASSISTANT
	}

	public enum TurnStatus {
		PENDING,
		STREAMING,
		COMPLETED,
		FAILED
	}

	public enum RunStatus {
		RUNNING,
		COMPLETED,
		FAILED
	}

	public enum DeleteResult {
		DELETED,
		ACTIVE_RUN,
		NOT_FOUND
	}
}
