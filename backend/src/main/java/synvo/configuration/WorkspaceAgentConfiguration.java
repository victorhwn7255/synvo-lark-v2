package synvo.configuration;

import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import synvo.integration.codex.CodexRunnerClient;
import synvo.workspaceagent.DisabledWorkspaceAgentEngine;
import synvo.workspaceagent.WorkspaceAgentEngine;
import synvo.workspaceagent.WorkspaceAgentEventPublisher;
import synvo.workspaceagent.WorkspaceAgentFacade;
import synvo.workspaceagent.WorkspaceAgentPolicy;
import synvo.workspaceagent.WorkspaceAgentRepository;
import synvo.workspaceagent.WorkspaceRegistry;
import synvo.workspaceagent.WorkspaceRegistry.WorkspaceDefinition;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
class WorkspaceAgentConfiguration {

	@Bean
	WorkspaceRegistry workspaceRegistry(CodexProperties properties) {
		if (!properties.enabled()) {
			return new WorkspaceRegistry(List.of());
		}
		List<WorkspaceDefinition> definitions = properties.workspaces().stream()
				.map(workspace -> new WorkspaceDefinition(
						workspace.id(),
						workspace.displayName(),
						Path.of(workspace.runnerRoot()).toAbsolutePath().normalize(),
						workspace.nativeChatDefault(),
						workspace.writeEnabled(),
						workspace.repositoryLabel()))
				.toList();
		return new WorkspaceRegistry(definitions);
	}

	@Bean
	WorkspaceAgentPolicy workspaceAgentPolicy(
			LarkProperties larkProperties,
			CodexProperties codexProperties) {
		return new WorkspaceAgentPolicy(
				larkProperties.pilotOpenId(), codexProperties.allowedMcpServers());
	}

	@Bean
	@ConditionalOnProperty(prefix = "synvo.codex", name = "enabled", havingValue = "true")
	WorkspaceAgentEngine codexRunnerClient(
			CodexProperties properties,
			ObjectMapper objectMapper) {
		return new CodexRunnerClient(properties, objectMapper);
	}

	@Bean
	@ConditionalOnMissingBean(WorkspaceAgentEngine.class)
	WorkspaceAgentEngine disabledWorkspaceAgentEngine() {
		return new DisabledWorkspaceAgentEngine();
	}

	@Bean
	@ConditionalOnMissingBean(WorkspaceAgentEventPublisher.class)
	WorkspaceAgentEventPublisher disabledWorkspaceAgentEventPublisher() {
		return WorkspaceAgentEventPublisher.none();
	}

	@Bean
	WorkspaceAgentFacade workspaceAgentFacade(
			WorkspaceAgentEngine engine,
			WorkspaceAgentRepository repository,
			WorkspaceRegistry workspaces,
			WorkspaceAgentPolicy policy,
			WorkspaceAgentEventPublisher eventPublisher,
			CodexProperties properties) {
		return new WorkspaceAgentFacade(
				engine,
				repository,
				workspaces,
				policy,
				eventPublisher,
				properties.reasoningEffort(),
				properties.interactionTimeout(),
				properties.enabled());
	}
}
