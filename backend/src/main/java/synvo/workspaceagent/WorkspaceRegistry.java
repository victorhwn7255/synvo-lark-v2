package synvo.workspaceagent;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import synvo.workspaceagent.WorkspaceAgentEngine.RunMode;
import synvo.workspaceagent.WorkspaceAgentEngine.WorkspaceTarget;

/** Resolves stable workspace IDs to Java-owned canonical targets. */
public final class WorkspaceRegistry {

	private final Map<String, WorkspaceDefinition> definitions;
	private final WorkspaceDefinition nativeChatDefault;

	public WorkspaceRegistry(List<WorkspaceDefinition> definitions) {
		Objects.requireNonNull(definitions, "definitions");
		Map<String, WorkspaceDefinition> indexed = new LinkedHashMap<>();
		WorkspaceDefinition defaultWorkspace = null;
		for (WorkspaceDefinition definition : definitions) {
			if (indexed.putIfAbsent(definition.id(), definition) != null) {
				throw new IllegalArgumentException("Workspace IDs must be unique");
			}
			if (definition.nativeChatDefault()) {
				if (defaultWorkspace != null) {
					throw new IllegalArgumentException("Only one native Chat workspace may be default");
				}
				defaultWorkspace = definition;
			}
		}
		this.definitions = Collections.unmodifiableMap(new LinkedHashMap<>(indexed));
		this.nativeChatDefault = defaultWorkspace;
	}

	public List<WorkspaceSummary> summaries() {
		return definitions.values().stream()
				.map(definition -> new WorkspaceSummary(
						definition.id(),
						definition.displayName(),
						definition.nativeChatDefault(),
						definition.writeEnabled(),
						definition.repositoryLabel()))
				.toList();
	}

	boolean contains(String workspaceId) {
		return definitions.containsKey(workspaceId);
	}

	public WorkspaceDefinition require(String workspaceId) {
		WorkspaceDefinition definition = definitions.get(workspaceId);
		if (definition == null) {
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.INVALID_REQUEST);
		}
		return definition;
	}

	public WorkspaceDefinition requireNativeChatDefault() {
		if (nativeChatDefault == null) {
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.DISABLED);
		}
		return nativeChatDefault;
	}

	public WorkspaceTarget target(String workspaceId) {
		WorkspaceDefinition definition = require(workspaceId);
		return new WorkspaceTarget(definition.id(), definition.canonicalRoot());
	}

	public void verifyMode(String workspaceId, RunMode mode) {
		WorkspaceDefinition definition = require(workspaceId);
		if (mode == RunMode.WORKSPACE_WRITE && !definition.writeEnabled()) {
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.POLICY_DENIED);
		}
	}

	public record WorkspaceDefinition(
			String id,
			String displayName,
			Path canonicalRoot,
			boolean nativeChatDefault,
			boolean writeEnabled,
			String repositoryLabel
	) {
		public WorkspaceDefinition {
			if (id == null || id.isBlank() || displayName == null || displayName.isBlank()
					|| canonicalRoot == null || !canonicalRoot.isAbsolute()
					|| !canonicalRoot.normalize().equals(canonicalRoot)) {
				throw new IllegalArgumentException("Workspace definition is invalid");
			}
		}
	}

	public record WorkspaceSummary(
			String id,
			String displayName,
			boolean nativeChatDefault,
			boolean writeEnabled,
			String repositoryLabel
	) {
	}
}
