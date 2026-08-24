package synvo.workspaceagent;

import java.util.UUID;

public interface WorkspaceAgentEventPublisher {

	void publish(UUID operationId, WorkspaceAgentFacade.ActivityView activity);

	void interactionRequired(
			UUID operationId,
			WorkspaceAgentFacade.InteractionView interaction);

	static WorkspaceAgentEventPublisher none() {
		return new WorkspaceAgentEventPublisher() {
			@Override
			public void publish(UUID operationId, WorkspaceAgentFacade.ActivityView activity) {
			}

			@Override
			public void interactionRequired(
					UUID operationId,
					WorkspaceAgentFacade.InteractionView interaction) {
			}
		};
	}
}
