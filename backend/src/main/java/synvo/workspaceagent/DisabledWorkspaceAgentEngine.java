package synvo.workspaceagent;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class DisabledWorkspaceAgentEngine implements WorkspaceAgentEngine {

	@Override
	public EngineStatus status() {
		return EngineStatus.DISABLED;
	}

	@Override
	public Capabilities capabilities() {
		throw disabled();
	}

	@Override
	public AccountStatus account() {
		throw disabled();
	}

	@Override
	public TaskHandle createTask(WorkspaceTarget workspace, RunMode mode) {
		throw disabled();
	}

	@Override
	public TaskHandle forkTask(String taskReference, WorkspaceTarget workspace) {
		throw disabled();
	}

	@Override
	public void resumeTask(String taskReference, WorkspaceTarget workspace) {
		throw disabled();
	}

	@Override
	public void renameTask(String taskReference, String title) {
		throw disabled();
	}

	@Override
	public void archiveTask(String taskReference) {
		throw disabled();
	}

	@Override
	public void unarchiveTask(String taskReference) {
		throw disabled();
	}

	@Override
	public void deleteTask(String taskReference) {
		throw disabled();
	}

	@Override
	public OperationHandle startTurn(
			String taskReference,
			WorkspaceTarget workspace,
			RunMode mode,
			TurnInput input) {
		throw disabled();
	}

	@Override
	public OperationHandle startReview(
			String taskReference,
			WorkspaceTarget workspace,
			ReviewTarget target) {
		throw disabled();
	}

	@Override
	public ActivityBatch waitForActivity(String operationReference, long afterSequence) {
		throw disabled();
	}

	@Override
	public List<InteractionRequest> pendingInteractions(String operationReference) {
		throw disabled();
	}

	@Override
	public void decideInteraction(
			String operationReference,
			String interactionReference,
			InteractionDecision decision,
			Map<String, String> formValues) {
		throw disabled();
	}

	@Override
	public void steer(String operationReference, String text) {
		throw disabled();
	}

	@Override
	public void stop(String operationReference) {
		throw disabled();
	}

	@Override
	public Inventory inventory(String taskReference, WorkspaceTarget workspace) {
		throw disabled();
	}

	@Override
	public Optional<Goal> goal(String taskReference) {
		throw disabled();
	}

	@Override
	public void setGoal(String taskReference, String objective, GoalCommand command) {
		throw disabled();
	}

	@Override
	public void clearGoal(String taskReference) {
		throw disabled();
	}

	private static WorkspaceAgentException disabled() {
		return new WorkspaceAgentException(WorkspaceAgentException.Code.DISABLED);
	}
}
