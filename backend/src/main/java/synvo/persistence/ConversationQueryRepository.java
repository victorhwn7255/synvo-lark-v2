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
import synvo.agent.ConversationQueries;

@Repository
class ConversationQueryRepository implements ConversationQueries {

	private static final int RECENT_LIMIT = 50;
	private static final String OWNER_OPEN_ID_PARAMETER = "ownerOpenId";
	private static final String CONVERSATION_ID_PARAMETER = "conversationId";
	private static final String CONVERSATION_ID_COLUMN = "conversation_id";
	private static final String UPDATED_AT_COLUMN = "updated_at";

	private final JdbcClient jdbcClient;

	ConversationQueryRepository(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public List<ConversationSummary> listRecent(String ownerOpenId) {
		return jdbcClient.sql("""
				SELECT conversation_id, title, updated_at
				FROM conversation
				WHERE owner_open_id = :ownerOpenId
				ORDER BY updated_at DESC
				LIMIT :limit
				""")
				.param(OWNER_OPEN_ID_PARAMETER, ownerOpenId)
				.param("limit", RECENT_LIMIT)
				.query((resultSet, ignoredRowNumber) -> new ConversationSummary(
						resultSet.getObject(CONVERSATION_ID_COLUMN, UUID.class),
						resultSet.getString("title"),
						toInstant(resultSet, UPDATED_AT_COLUMN)))
				.list();
	}

	@Override
	public Optional<ConversationDetail> findConversation(String ownerOpenId, UUID conversationId) {
		Optional<ConversationHeader> header = jdbcClient.sql("""
				SELECT conversation_id, title, updated_at
				FROM conversation
				WHERE conversation_id = :conversationId
				  AND owner_open_id = :ownerOpenId
				""")
				.param(CONVERSATION_ID_PARAMETER, conversationId)
				.param(OWNER_OPEN_ID_PARAMETER, ownerOpenId)
				.query((resultSet, ignoredRowNumber) -> new ConversationHeader(
						resultSet.getObject(CONVERSATION_ID_COLUMN, UUID.class),
						resultSet.getString("title"),
						toInstant(resultSet, UPDATED_AT_COLUMN)))
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
				.param(CONVERSATION_ID_PARAMETER, conversationId)
				.query((resultSet, ignoredRowNumber) -> new ConversationTurn(
						resultSet.getObject("turn_id", UUID.class),
						Role.valueOf(resultSet.getString("role")),
						resultSet.getString("content"),
						TurnStatus.valueOf(resultSet.getString("status")),
						toInstant(resultSet, "created_at"),
						toInstant(resultSet, UPDATED_AT_COLUMN)))
				.list();
		ConversationHeader value = header.orElseThrow();
		RunDescriptor activeRun = jdbcClient.sql("""
				SELECT r.run_id, r.request_id, r.conversation_id, r.user_turn_id,
				       r.assistant_turn_id, r.intent, r.status
				FROM agent_run r
				WHERE r.conversation_id = :conversationId
				  AND r.status = 'RUNNING'
				""")
				.param(CONVERSATION_ID_PARAMETER, conversationId)
				.query(ConversationQueryRepository::mapRun)
				.optional()
				.orElse(null);
		return Optional.of(new ConversationDetail(
				value.conversationId(), value.title(), value.updatedAt(), turns, activeRun));
	}

	@Override
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
				.param(OWNER_OPEN_ID_PARAMETER, ownerOpenId)
				.query(ConversationQueryRepository::mapRun)
				.optional();
	}

	@Transactional
	@Override
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
				.param(CONVERSATION_ID_PARAMETER, conversationId)
				.param(OWNER_OPEN_ID_PARAMETER, ownerOpenId)
				.query(String.class)
				.single();
		return DeleteResult.valueOf(result);
	}

	private static RunDescriptor mapRun(ResultSet resultSet, int ignoredRowNumber) throws SQLException {
		return new RunDescriptor(
				resultSet.getObject("run_id", UUID.class),
				resultSet.getString("request_id"),
				resultSet.getObject(CONVERSATION_ID_COLUMN, UUID.class),
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

}
