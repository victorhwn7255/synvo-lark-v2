package synvo.agent;

import java.util.List;
import java.util.UUID;

public record ConversationResult(
		UUID conversationId,
		UUID runId,
		AgentIntent intent,
		Outcome outcome,
		Status status,
		String response,
		List<AgentLifecycleEvent> events,
		boolean replayed
) {

	public ConversationResult {
		events = List.copyOf(events);
	}

	public enum Outcome {
		DIRECT_ANSWER,
		CLARIFICATION,
		WORKFLOW_UNAVAILABLE,
		FAILED
	}

	public enum Status {
		COMPLETED,
		FAILED
	}
}
