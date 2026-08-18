package synvo.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import synvo.agent.AgentIntent;
import synvo.agent.AgentLifecycleEvent;
import synvo.agent.ConversationContextMessage;
import synvo.agent.ConversationRequest;
import synvo.agent.ConversationResult;
import synvo.agent.ConversationStore;

@Repository
public class JdbcConversationStore implements ConversationStore {

	private static final int MAX_TITLE_LENGTH = 80;

	private final JdbcClient jdbcClient;

	public JdbcConversationStore(JdbcClient jdbcClient) {
		this.jdbcClient = jdbcClient;
	}

	@Override
	public Optional<ConversationResult> findTerminalResult(String requestId) {
		Optional<StoredTerminalRun> stored = jdbcClient.sql("""
				SELECT r.run_id, r.conversation_id, r.intent, r.outcome, r.status,
				       t.content AS assistant_response
				FROM agent_run r
				JOIN conversation_turn t ON t.turn_id = r.assistant_turn_id
				WHERE r.request_id = :requestId
				  AND r.status IN ('COMPLETED', 'FAILED')
				""")
				.param("requestId", requestId)
				.query(JdbcConversationStore::mapTerminalRun)
				.optional();

		return stored.map(run -> new ConversationResult(
				run.conversationId(),
				run.runId(),
				run.intent(),
				run.outcome(),
				run.status(),
				run.assistantResponse(),
				loadEvents(run.runId(), 0),
				true));
	}

	@Override
	@Transactional
	public RunHandle start(ConversationRequest request, AgentIntent intent) {
		Instant now = Instant.now();
		UUID conversationId = request.conversationId();
		if (conversationId == null) {
			conversationId = UUID.randomUUID();
			jdbcClient.sql("""
					INSERT INTO conversation (
					    conversation_id, owner_open_id, title, created_at, updated_at
					)
					VALUES (:conversationId, :ownerOpenId, :title, :now, :now)
					""")
					.param("conversationId", conversationId)
					.param("ownerOpenId", request.userOpenId())
					.param("title", deriveTitle(request.content()))
					.param("now", atUtc(now))
					.update();
		}
		else {
			String ownerOpenId = jdbcClient.sql("""
					SELECT owner_open_id
					FROM conversation
					WHERE conversation_id = :conversationId
					""")
					.param("conversationId", conversationId)
					.query(String.class)
					.optional()
					.orElseThrow(() -> new IllegalArgumentException("Conversation is unavailable"));
			if (!ownerOpenId.equals(request.userOpenId())) {
				throw new IllegalArgumentException("Conversation is unavailable");
			}
		}
		supersedeFailedAttempt(request, conversationId, now);

		UUID userTurnId = UUID.randomUUID();
		UUID assistantTurnId = UUID.randomUUID();
		insertTurn(userTurnId, conversationId, "USER", request.content(), "COMPLETED", now);
		insertTurn(assistantTurnId, conversationId, "ASSISTANT", "", "PENDING", now);

		UUID runId = UUID.randomUUID();
		jdbcClient.sql("""
				INSERT INTO agent_run (
				    run_id, request_id, conversation_id, user_turn_id,
				    assistant_turn_id, requested_by_open_id, intent, status,
				    created_at, updated_at
				)
				VALUES (
				    :runId, :requestId, :conversationId, :userTurnId,
				    :assistantTurnId, :requestedByOpenId, :intent, 'RUNNING',
				    :now, :now
				)
				""")
				.param("runId", runId)
				.param("requestId", request.requestId())
				.param("conversationId", conversationId)
				.param("userTurnId", userTurnId)
				.param("assistantTurnId", assistantTurnId)
				.param("requestedByOpenId", request.userOpenId())
				.param("intent", intent.name())
				.param("now", atUtc(now))
				.update();

		jdbcClient.sql("""
				UPDATE conversation
				SET updated_at = :now
				WHERE conversation_id = :conversationId
				""")
				.param("now", atUtc(now))
				.param("conversationId", conversationId)
				.update();

		return new RunHandle(conversationId, runId, userTurnId, assistantTurnId, intent);
	}

	@Override
	public List<ConversationContextMessage> loadContext(
			UUID conversationId,
			int maxMessages,
			int maxCharacters) {
		if (maxMessages < 1 || maxCharacters < 1) {
			throw new IllegalArgumentException("Context bounds must be positive");
		}
		List<ConversationContextMessage> newestFirst = jdbcClient.sql("""
				SELECT role, content
				FROM conversation_turn
				WHERE conversation_id = :conversationId
				  AND status = 'COMPLETED'
				  AND superseded = FALSE
				  AND content <> ''
				ORDER BY ordinal DESC
				LIMIT :maxMessages
				""")
				.param("conversationId", conversationId)
				.param("maxMessages", maxMessages)
				.query((resultSet, rowNumber) -> new ConversationContextMessage(
						ConversationContextMessage.Role.valueOf(resultSet.getString("role")),
						resultSet.getString("content")))
				.list();

		Deque<ConversationContextMessage> bounded = new ArrayDeque<>();
		int remainingCharacters = maxCharacters;
		for (ConversationContextMessage message : newestFirst) {
			if (message.content().length() > remainingCharacters) {
				break;
			}
			bounded.addFirst(message);
			remainingCharacters -= message.content().length();
		}
		return List.copyOf(bounded);
	}

	@Override
	public void appendEvent(UUID runId, AgentLifecycleEvent event) {
		insertEvent(runId, event);
	}

	@Override
	@Transactional
	public void appendContentDelta(RunHandle run, AgentLifecycleEvent event) {
		if (event.state() != AgentLifecycleEvent.State.CONTENT_DELTA) {
			throw new IllegalArgumentException("Only content delta events may append assistant text");
		}
		int updated = jdbcClient.sql("""
				UPDATE conversation_turn
				SET content = content || :delta, status = 'STREAMING', updated_at = :now
				WHERE turn_id = :turnId AND status IN ('PENDING', 'STREAMING')
				""")
				.param("delta", event.contentDelta())
				.param("now", atUtc(Instant.now()))
				.param("turnId", run.assistantTurnId())
				.update();
		assertOne(updated, "Assistant turn was not streamable");
		insertEvent(run.runId(), event);
	}

	@Override
	@Transactional
	public void resetAssistantContent(RunHandle run, AgentLifecycleEvent event) {
		if (event.state() != AgentLifecycleEvent.State.CONTENT_RESET) {
			throw new IllegalArgumentException("Only a content reset event may reset assistant text");
		}
		int updated = jdbcClient.sql("""
				UPDATE conversation_turn
				SET content = '', status = 'PENDING', updated_at = :now
				WHERE turn_id = :turnId AND status IN ('PENDING', 'STREAMING')
				""")
				.param("now", atUtc(Instant.now()))
				.param("turnId", run.assistantTurnId())
				.update();
		assertOne(updated, "Assistant turn was not resettable");
		insertEvent(run.runId(), event);
	}

	private void insertEvent(UUID runId, AgentLifecycleEvent event) {
		jdbcClient.sql("""
				INSERT INTO agent_run_event (
				    run_id, sequence_number, event_type, safe_message, content_delta, created_at
				)
				VALUES (:runId, :sequence, :eventType, :safeMessage, :contentDelta, :now)
				""")
				.param("runId", runId)
				.param("sequence", event.sequence())
				.param("eventType", event.state().name())
				.param("safeMessage", event.safeMessage())
				.param("contentDelta", event.contentDelta())
				.param("now", atUtc(Instant.now()))
				.update();
	}

	@Override
	@Transactional
	public void complete(
			RunHandle run,
			ConversationResult.Outcome outcome,
			String assistantResponse,
			AgentLifecycleEvent terminalEvent) {
		finish(run, "COMPLETED", outcome.name(), null, assistantResponse, terminalEvent);
	}

	@Override
	@Transactional
	public void fail(
			RunHandle run,
			String errorCode,
			String safeAssistantResponse,
			AgentLifecycleEvent terminalEvent) {
		finish(
				run,
				"FAILED",
				ConversationResult.Outcome.FAILED.name(),
				errorCode,
				safeAssistantResponse,
				terminalEvent);
	}

	private void finish(
			RunHandle run,
			String status,
			String outcome,
			String errorCode,
			String assistantResponse,
			AgentLifecycleEvent terminalEvent) {
		OffsetDateTime now = atUtc(Instant.now());
		int turnUpdated = jdbcClient.sql("""
				UPDATE conversation_turn
				SET content = :content, status = :status, updated_at = :now
				WHERE turn_id = :turnId AND status IN ('PENDING', 'STREAMING')
				""")
				.param("content", assistantResponse)
				.param("status", status)
				.param("now", now)
				.param("turnId", run.assistantTurnId())
				.update();
		assertOne(turnUpdated, "Assistant turn was not active");

		int runUpdated = jdbcClient.sql("""
				UPDATE agent_run
				SET status = :status, outcome = :outcome, error_code = :errorCode,
				    updated_at = :now
				WHERE run_id = :runId AND status = 'RUNNING'
				""")
				.param("status", status)
				.param("outcome", outcome)
				.param("errorCode", errorCode)
				.param("now", now)
				.param("runId", run.runId())
				.update();
		assertOne(runUpdated, "Agent run was not active");

		jdbcClient.sql("""
				UPDATE conversation
				SET updated_at = :now
				WHERE conversation_id = :conversationId
				""")
				.param("now", now)
				.param("conversationId", run.conversationId())
				.update();
		insertEvent(run.runId(), terminalEvent);
	}

	private void insertTurn(
			UUID turnId,
			UUID conversationId,
			String role,
			String content,
			String status,
			Instant now) {
		jdbcClient.sql("""
				INSERT INTO conversation_turn (
				    turn_id, conversation_id, role, content, status, created_at, updated_at
				)
				VALUES (:turnId, :conversationId, :role, :content, :status, :now, :now)
				""")
				.param("turnId", turnId)
				.param("conversationId", conversationId)
				.param("role", role)
				.param("content", content)
				.param("status", status)
				.param("now", atUtc(now))
				.update();
	}

	private void supersedeFailedAttempt(
			ConversationRequest request,
			UUID conversationId,
			Instant now) {
		UUID assistantTurnId = request.replaceFailedAssistantTurnId();
		if (assistantTurnId == null) {
			return;
		}
		RetryTarget target = jdbcClient.sql("""
				SELECT r.user_turn_id, u.content
				FROM agent_run r
				JOIN conversation_turn u ON u.turn_id = r.user_turn_id
				JOIN conversation_turn a ON a.turn_id = r.assistant_turn_id
				WHERE r.conversation_id = :conversationId
				  AND r.requested_by_open_id = :ownerOpenId
				  AND r.assistant_turn_id = :assistantTurnId
				  AND r.status = 'FAILED'
				  AND u.superseded = FALSE
				  AND a.superseded = FALSE
				""")
				.param("conversationId", conversationId)
				.param("ownerOpenId", request.userOpenId())
				.param("assistantTurnId", assistantTurnId)
				.query((resultSet, rowNumber) -> new RetryTarget(
						resultSet.getObject("user_turn_id", UUID.class),
						resultSet.getString("content")))
				.optional()
				.orElseThrow(() -> new IllegalArgumentException("Retry target is unavailable"));
		if (!target.userContent().equals(request.content())) {
			throw new IllegalArgumentException("Retry content must match the failed request");
		}
		int updated = jdbcClient.sql("""
				UPDATE conversation_turn
				SET superseded = TRUE, updated_at = :now
				WHERE conversation_id = :conversationId
				  AND superseded = FALSE
				  AND (turn_id = :userTurnId OR turn_id = :assistantTurnId)
				""")
				.param("now", atUtc(now))
				.param("conversationId", conversationId)
				.param("userTurnId", target.userTurnId())
				.param("assistantTurnId", assistantTurnId)
				.update();
		if (updated != 2) {
			throw new IllegalArgumentException("Retry target is unavailable");
		}
	}

	@Override
	public List<AgentLifecycleEvent> loadEvents(UUID runId, int afterSequence) {
		if (afterSequence < 0) {
			throw new IllegalArgumentException("Event sequence cannot be negative");
		}
		return jdbcClient.sql("""
				SELECT sequence_number, event_type, safe_message, content_delta
				FROM agent_run_event
				WHERE run_id = :runId
				  AND sequence_number > :afterSequence
				ORDER BY sequence_number
				""")
				.param("runId", runId)
				.param("afterSequence", afterSequence)
				.query((resultSet, rowNumber) -> new AgentLifecycleEvent(
						resultSet.getInt("sequence_number"),
						AgentLifecycleEvent.State.valueOf(resultSet.getString("event_type")),
						resultSet.getString("safe_message"),
						resultSet.getString("content_delta")))
				.list();
	}

	@Override
	@Transactional
	public int recoverInterruptedRuns(String safeAssistantResponse) {
		List<InterruptedRun> interrupted = jdbcClient.sql("""
				SELECT r.run_id, r.assistant_turn_id,
				       COALESCE(MAX(e.sequence_number), 0) + 1 AS next_sequence
				FROM agent_run r
				LEFT JOIN agent_run_event e ON e.run_id = r.run_id
				WHERE r.status = 'RUNNING'
				GROUP BY r.run_id, r.assistant_turn_id
				""")
				.query((resultSet, rowNumber) -> new InterruptedRun(
						resultSet.getObject("run_id", UUID.class),
						resultSet.getObject("assistant_turn_id", UUID.class),
						resultSet.getInt("next_sequence")))
				.list();

		for (InterruptedRun run : interrupted) {
			OffsetDateTime now = atUtc(Instant.now());
			jdbcClient.sql("""
					UPDATE conversation_turn
					SET content = :content, status = 'FAILED', updated_at = :now
					WHERE turn_id = :turnId AND status IN ('PENDING', 'STREAMING')
					""")
					.param("content", safeAssistantResponse)
					.param("now", now)
					.param("turnId", run.assistantTurnId())
					.update();
			jdbcClient.sql("""
					UPDATE agent_run
					SET status = 'FAILED', outcome = 'FAILED', error_code = 'BACKEND_RESTARTED',
					    updated_at = :now
					WHERE run_id = :runId AND status = 'RUNNING'
					""")
					.param("now", now)
					.param("runId", run.runId())
					.update();
			insertEvent(run.runId(), new AgentLifecycleEvent(
					run.nextSequence(), AgentLifecycleEvent.State.FAILED, safeAssistantResponse));
		}
		return interrupted.size();
	}

	private static StoredTerminalRun mapTerminalRun(ResultSet resultSet, int rowNumber)
			throws SQLException {
		return new StoredTerminalRun(
				resultSet.getObject("conversation_id", UUID.class),
				resultSet.getObject("run_id", UUID.class),
				AgentIntent.valueOf(resultSet.getString("intent")),
				ConversationResult.Outcome.valueOf(resultSet.getString("outcome")),
				ConversationResult.Status.valueOf(resultSet.getString("status")),
				resultSet.getString("assistant_response"));
	}

	private static String deriveTitle(String content) {
		String oneLine = content.replaceAll("\\s+", " ").strip();
		return oneLine.length() <= MAX_TITLE_LENGTH
				? oneLine
				: oneLine.substring(0, MAX_TITLE_LENGTH - 1).stripTrailing() + "…";
	}

	private static void assertOne(int count, String message) {
		if (count != 1) {
			throw new IllegalStateException(message);
		}
	}

	private static OffsetDateTime atUtc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private record StoredTerminalRun(
			UUID conversationId,
			UUID runId,
			AgentIntent intent,
			ConversationResult.Outcome outcome,
			ConversationResult.Status status,
			String assistantResponse
	) {
	}

	private record InterruptedRun(UUID runId, UUID assistantTurnId, int nextSequence) {
	}

	private record RetryTarget(UUID userTurnId, String userContent) {
	}
}
