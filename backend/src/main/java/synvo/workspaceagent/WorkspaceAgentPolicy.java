package synvo.workspaceagent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDecision;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDetail;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionField;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionFieldType;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionKind;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionRequest;
import synvo.workspaceagent.WorkspaceAgentEngine.RunMode;
import synvo.workspaceagent.WorkspaceAgentRepository.TaskRecord;

/** Deterministic Phase 3 authorization and action ceiling. */
public final class WorkspaceAgentPolicy {

	private final String authorizedOwnerOpenId;
	private final Set<String> allowedMcpServers;

	public WorkspaceAgentPolicy(String authorizedOwnerOpenId, List<String> allowedMcpServers) {
		this.authorizedOwnerOpenId = authorizedOwnerOpenId;
		this.allowedMcpServers = Set.copyOf(allowedMcpServers);
	}

	public void requireOwner(String ownerOpenId) {
		if (authorizedOwnerOpenId == null || authorizedOwnerOpenId.isBlank()
				|| !authorizedOwnerOpenId.equals(ownerOpenId)) {
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.FORBIDDEN);
		}
	}

	public AuthorizedInteraction authorize(
			TaskRecord task,
			InteractionRequest request) {
		if (!task.workspaceId().equals(request.workspaceId())) {
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.POLICY_DENIED);
		}
		List<InteractionDecision> decisions = new ArrayList<>(request.availableDecisions());
		decisions.retainAll(List.of(
				InteractionDecision.APPROVE_ONCE,
				InteractionDecision.DECLINE,
				InteractionDecision.CANCEL));
		if (request.kind() == InteractionKind.COMMAND_APPROVAL) {
			decisions.remove(InteractionDecision.APPROVE_ONCE);
		}
		if (request.kind() == InteractionKind.FILE_CHANGE_APPROVAL
				&& task.mode() != RunMode.WORKSPACE_WRITE) {
			decisions.remove(InteractionDecision.APPROVE_ONCE);
		}
		if (request.kind() == InteractionKind.MCP_TOOL_APPROVAL
				|| request.kind() == InteractionKind.MCP_ELICITATION) {
			String server = request.detail().mcpServer();
			if (server == null || !allowedMcpServers.contains(server)) {
				decisions.remove(InteractionDecision.APPROVE_ONCE);
			}
		}
		if (decisions.isEmpty()) {
			decisions = List.of(InteractionDecision.CANCEL);
		}
		return new AuthorizedInteraction(
				List.copyOf(decisions),
				permissionScope(request.kind()),
				!decisions.contains(InteractionDecision.APPROVE_ONCE));
	}

	public void verifyDecision(
			List<InteractionDecision> available,
			InteractionDecision decision,
			Map<String, String> formValues,
			InteractionKind kind,
			InteractionDetail detail) {
		if (!available.contains(decision)) {
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.POLICY_DENIED);
		}
		Map<String, String> supplied = formValues == null ? Map.of() : formValues;
		boolean approval = decision == InteractionDecision.APPROVE_ONCE;
		boolean mcpInput = kind == InteractionKind.MCP_ELICITATION
				|| kind == InteractionKind.MCP_TOOL_APPROVAL;
		if (!supplied.isEmpty() && (!mcpInput || !approval)) {
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.INVALID_REQUEST);
		}
		if (!mcpInput || !approval) {
			return;
		}
		if (decision != InteractionDecision.APPROVE_ONCE || supplied.size() > 20) {
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.INVALID_REQUEST);
		}
		Map<String, InteractionField> fields = new java.util.LinkedHashMap<>();
		for (InteractionField field : detail.fields()) {
			if (fields.put(field.name(), field) != null) {
				throw new WorkspaceAgentException(WorkspaceAgentException.Code.INVALID_REQUEST);
			}
		}
		Set<String> keys = new HashSet<>();
		for (Map.Entry<String, String> entry : supplied.entrySet()) {
			if (entry.getKey() == null || entry.getKey().isBlank()
					|| entry.getKey().length() > 100
					|| !keys.add(entry.getKey())
					|| entry.getValue() == null || entry.getValue().length() > 2_000
					|| looksSensitive(entry.getKey())
					|| !fields.containsKey(entry.getKey())) {
				throw new WorkspaceAgentException(WorkspaceAgentException.Code.INVALID_REQUEST);
			}
			verifyFieldValue(fields.get(entry.getKey()), entry.getValue());
		}
		if (fields.values().stream().anyMatch(
				field -> field.required() && !supplied.containsKey(field.name()))) {
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.INVALID_REQUEST);
		}
	}

	private static void verifyFieldValue(InteractionField field, String value) {
		boolean valid = switch (field.type()) {
			case TEXT -> value.length() <= field.maxLength();
			case BOOLEAN -> "true".equals(value) || "false".equals(value);
			case SELECT -> field.options().contains(value);
			case INTEGER -> integer(value);
			case NUMBER -> number(value);
		};
		if (!valid) {
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.INVALID_REQUEST);
		}
	}

	private static boolean integer(String value) {
		try {
			Long.parseLong(value);
			return true;
		}
		catch (NumberFormatException invalid) {
			return false;
		}
	}

	private static boolean number(String value) {
		try {
			return Double.isFinite(Double.parseDouble(value));
		}
		catch (NumberFormatException invalid) {
			return false;
		}
	}

	private static String permissionScope(InteractionKind kind) {
		return switch (kind) {
			case COMMAND_APPROVAL -> "workspace command";
			case FILE_CHANGE_APPROVAL -> "workspace files";
			case MCP_TOOL_APPROVAL -> "allowlisted MCP tool";
			case MCP_ELICITATION -> "allowlisted MCP response";
		};
	}

	private static boolean looksSensitive(String key) {
		String normalized = key.toLowerCase(Locale.ROOT);
		return normalized.contains("token")
				|| normalized.contains("secret")
				|| normalized.contains("password")
				|| normalized.contains("credential")
				|| normalized.contains("api_key")
				|| normalized.contains("apikey");
	}

	public record AuthorizedInteraction(
			List<InteractionDecision> decisions,
			String permissionScope,
			boolean approvalCategoricallyDenied
	) {
	}
}
