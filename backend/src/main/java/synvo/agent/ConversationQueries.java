package synvo.agent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Application-owned read contract for conversation surfaces. Persistence adapters
 * implement the SQL and mapping details behind these stable view models.
 */
public interface ConversationQueries {

	List<ConversationSummary> listRecent(String ownerOpenId);

	Optional<ConversationDetail> findConversation(String ownerOpenId, UUID conversationId);

	Optional<RunDescriptor> findOwnedRun(String ownerOpenId, UUID runId);

	DeleteResult deleteOwnedConversation(String ownerOpenId, UUID conversationId);

	record ConversationSummary(UUID conversationId, String title, Instant updatedAt) {
	}

	record ConversationDetail(
			UUID conversationId,
			String title,
			Instant updatedAt,
			List<ConversationTurn> turns,
			RunDescriptor activeRun
	) {
		public ConversationDetail {
			turns = List.copyOf(turns);
		}
	}

	record ConversationTurn(
			UUID turnId,
			Role role,
			String content,
			TurnStatus status,
			Instant createdAt,
			Instant updatedAt
	) {
	}

	record RunDescriptor(
			UUID runId,
			String requestId,
			UUID conversationId,
			UUID userTurnId,
			UUID assistantTurnId,
			AgentIntent intent,
			RunStatus status
	) {
	}

	enum Role {
		USER,
		ASSISTANT
	}

	enum TurnStatus {
		PENDING,
		STREAMING,
		COMPLETED,
		FAILED
	}

	enum RunStatus {
		RUNNING,
		COMPLETED,
		FAILED
	}

	enum DeleteResult {
		DELETED,
		ACTIVE_RUN,
		NOT_FOUND
	}
}
