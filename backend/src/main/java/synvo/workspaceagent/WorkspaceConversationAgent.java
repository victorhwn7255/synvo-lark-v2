package synvo.workspaceagent;

import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import synvo.workspaceagent.WorkspaceAgentEngine.RunMode;
import synvo.workspaceagent.WorkspaceAgentFacade.ConversationCommand;
import synvo.workspaceagent.WorkspaceAgentFacade.ConversationObserver;
import synvo.workspaceagent.WorkspaceAgentFacade.ConversationOutcome;

/** Narrow application path shared by Agent Core and conversation surfaces. */
public interface WorkspaceConversationAgent {

	boolean enabled();

	ConversationOutcome runConversation(
			ConversationCommand command,
			ConversationObserver observer,
			BooleanSupplier cancellation);

	void stopConversationRun(java.util.UUID conversationRunId);

	default Optional<ConversationTaskHandoff> conversationTask(
			String ownerOpenId,
			UUID conversationId) {
		return Optional.empty();
	}

	record ConversationTaskHandoff(
			UUID taskId,
			String workspaceName,
			RunMode mode
	) {
	}
}
