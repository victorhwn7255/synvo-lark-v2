package synvo.workspaceagent;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDecision;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDetail;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionKind;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionField;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionFieldType;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionRequest;
import synvo.workspaceagent.WorkspaceAgentEngine.RunMode;
import synvo.workspaceagent.WorkspaceAgentRepository.TaskRecord;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceAgentPolicyTests {

	private final WorkspaceAgentPolicy policy = new WorkspaceAgentPolicy(
			"ou-victor", List.of("safe_fixture"));

	@Test
	void onlyTheConfiguredPilotOwnsWorkspaceAgentActions() {
		policy.requireOwner("ou-victor");
		assertThrows(WorkspaceAgentException.class, () -> policy.requireOwner("ou-other"));
	}

	@Test
	void everyCommandElevationFailsClosedRegardlessOfTaskModeOrCommandText() {
		var readOnly = policy.authorize(task(RunMode.READ_ONLY), command("python3 validate.py"));
		var fullEdit = policy.authorize(task(RunMode.WORKSPACE_WRITE), command("python3 validate.py"));
		var network = policy.authorize(task(RunMode.READ_ONLY), command("git push origin main"));
		var wrappedNetwork = policy.authorize(
				task(RunMode.READ_ONLY), command("bash -c 'curl https://example.invalid'"));

		assertFalse(readOnly.decisions().contains(InteractionDecision.APPROVE_ONCE));
		assertFalse(fullEdit.decisions().contains(InteractionDecision.APPROVE_ONCE));
		assertFalse(network.decisions().contains(InteractionDecision.APPROVE_ONCE));
		assertFalse(wrappedNetwork.decisions().contains(InteractionDecision.APPROVE_ONCE));
		for (String forbidden : List.of(
				"rm -rf build",
				"rm -fr build",
				"rm --recursive build",
				"git -C . push origin main",
				"git -c advice.detachedHead=false fetch origin",
				"npm --workspace frontend publish")) {
			assertFalse(policy.authorize(task(RunMode.WORKSPACE_WRITE), command(forbidden))
					.decisions().contains(InteractionDecision.APPROVE_ONCE));
		}
	}

	@Test
	void fileApprovalRequiresWorkspaceWriteAndMcpRequiresAnAllowlistedServer() {
		var file = interaction(
				InteractionKind.FILE_CHANGE_APPROVAL,
				new InteractionDetail(null, null, List.of("src/App.tsx"), null, null, null, null));
		assertFalse(policy.authorize(task(RunMode.READ_ONLY), file)
				.decisions().contains(InteractionDecision.APPROVE_ONCE));
		assertTrue(policy.authorize(task(RunMode.WORKSPACE_WRITE), file)
				.decisions().contains(InteractionDecision.APPROVE_ONCE));

		var unknown = interaction(
				InteractionKind.MCP_ELICITATION,
				new InteractionDetail(null, null, List.of(), "unknown", "write", "Continue?", "form"));
		var allowed = interaction(
				InteractionKind.MCP_ELICITATION,
				new InteractionDetail(null, null, List.of(), "safe_fixture", "write", "Continue?", "form"));
		assertFalse(policy.authorize(task(RunMode.WORKSPACE_WRITE), unknown)
				.decisions().contains(InteractionDecision.APPROVE_ONCE));
		assertTrue(policy.authorize(task(RunMode.WORKSPACE_WRITE), allowed)
				.decisions().contains(InteractionDecision.APPROVE_ONCE));
	}

	@Test
	void browserCannotWidenDecisionsOrSubmitSecretShapedElicitationFields() {
		assertThrows(WorkspaceAgentException.class, () -> policy.verifyDecision(
				List.of(InteractionDecision.DECLINE),
				InteractionDecision.APPROVE_ONCE,
				Map.of(),
				InteractionKind.COMMAND_APPROVAL,
				InteractionDetail.empty()));
		assertThrows(WorkspaceAgentException.class, () -> policy.verifyDecision(
				List.of(InteractionDecision.APPROVE_ONCE),
				InteractionDecision.APPROVE_ONCE,
				Map.of("access_token", "must-not-be-accepted"),
				InteractionKind.MCP_ELICITATION,
				InteractionDetail.empty()));
	}

	@Test
	void elicitationValuesMustMatchTheRunnerNormalizedFieldContract() {
		InteractionDetail detail = new InteractionDetail(
				null, null, List.of(), "safe_fixture", null, "Continue?", "form",
				null,
				List.of(
						new InteractionField(
								"confirm", "Confirm", InteractionFieldType.BOOLEAN,
								true, List.of(), 0),
						new InteractionField(
								"profile", "Profile", InteractionFieldType.SELECT,
								true, List.of("safe", "strict"), 0)));

		policy.verifyDecision(
				List.of(InteractionDecision.APPROVE_ONCE),
				InteractionDecision.APPROVE_ONCE,
				Map.of("confirm", "true", "profile", "strict"),
				InteractionKind.MCP_ELICITATION,
				detail);
		assertThrows(WorkspaceAgentException.class, () -> policy.verifyDecision(
				List.of(InteractionDecision.APPROVE_ONCE),
				InteractionDecision.APPROVE_ONCE,
				Map.of("confirm", "yes", "profile", "unsafe"),
				InteractionKind.MCP_ELICITATION,
				detail));
	}

	private static TaskRecord task(RunMode mode) {
		return new TaskRecord(
				UUID.randomUUID(), UUID.randomUUID(), "ou-victor", "pilot", mode,
				"Task", "private-ref", false, false, Instant.now(), Instant.now());
	}

	private static InteractionRequest command(String command) {
		return interaction(
				InteractionKind.COMMAND_APPROVAL,
				new InteractionDetail(command, ".", List.of(), null, null, null, null));
	}

	private static InteractionRequest interaction(
			InteractionKind kind,
			InteractionDetail detail) {
		return new InteractionRequest(
				"interaction-ref",
				"pilot",
				kind,
				"safe category",
				"safe reason",
				List.of(
						InteractionDecision.APPROVE_ONCE,
						InteractionDecision.DECLINE,
						InteractionDecision.CANCEL),
				detail,
				Instant.now().plusSeconds(60));
	}
}
