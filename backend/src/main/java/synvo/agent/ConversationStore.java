package synvo.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ConversationStore {

	Optional<ConversationResult> findTerminalResult(String requestId);

	RunHandle start(ConversationRequest request, AgentIntent intent);

	List<ConversationContextMessage> loadContext(
			UUID conversationId,
			int maxMessages,
			int maxCharacters);

	void appendEvent(UUID runId, AgentLifecycleEvent event);

	void appendContentDelta(RunHandle run, AgentLifecycleEvent event);

	void resetAssistantContent(RunHandle run, AgentLifecycleEvent event);

	List<AgentLifecycleEvent> loadEvents(UUID runId, int afterSequence);

	void complete(
			RunHandle run,
			ConversationResult.Outcome outcome,
			String assistantResponse,
			AgentLifecycleEvent terminalEvent);

	void fail(
			RunHandle run,
			String errorCode,
			String safeAssistantResponse,
			AgentLifecycleEvent terminalEvent);

	int recoverInterruptedRuns(String safeAssistantResponse);

	record RunHandle(
			UUID conversationId,
			UUID runId,
			UUID userTurnId,
			UUID assistantTurnId,
			AgentIntent intent
	) {
	}
}
