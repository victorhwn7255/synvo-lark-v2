package synvo.workspaceagent;

import java.util.function.BooleanSupplier;
import synvo.workspaceagent.WorkspaceAgentFacade.ConversationCommand;
import synvo.workspaceagent.WorkspaceAgentFacade.ConversationObserver;
import synvo.workspaceagent.WorkspaceAgentFacade.ConversationOutcome;

/** Narrow conversation path used by Agent Core and its coordinator. */
public interface WorkspaceConversationAgent {

	boolean enabled();

	ConversationOutcome runConversation(
			ConversationCommand command,
			ConversationObserver observer,
			BooleanSupplier cancellation);

	void stopConversationRun(java.util.UUID conversationRunId);
}
