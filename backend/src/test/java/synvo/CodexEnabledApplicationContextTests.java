package synvo;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import synvo.integration.codex.CodexRunnerClient;
import synvo.workspaceagent.WorkspaceAgentEngine;
import synvo.workspaceagent.WorkspaceRegistry;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(
		webEnvironment = SpringBootTest.WebEnvironment.MOCK,
		properties = {
			"synvo.codex.enabled=true",
			"synvo.codex.runner-base-url=http://codex-runner:8090",
			"synvo.codex.model=gpt-5.6-sol",
			"synvo.codex.runtime-version=0.148.0",
			"synvo.codex.workspaces[0].id=finance",
			"synvo.codex.workspaces[0].display-name=Finance",
			"synvo.codex.workspaces[0].runner-root=/workspaces/finance",
			"synvo.codex.workspaces[0].native-chat-default=false",
			"synvo.codex.workspaces[0].write-enabled=true",
			"synvo.codex.workspaces[0].repository-label=Synvo Workspaces/Finance",
			"synvo.codex.workspaces[1].id=products",
			"synvo.codex.workspaces[1].display-name=Products",
			"synvo.codex.workspaces[1].runner-root=/workspaces/products",
			"synvo.codex.workspaces[1].native-chat-default=true",
			"synvo.codex.workspaces[1].write-enabled=true",
			"synvo.codex.workspaces[1].repository-label=Synvo Workspaces/Products",
			"synvo.codex.workspaces[2].id=sales",
			"synvo.codex.workspaces[2].display-name=Sales",
			"synvo.codex.workspaces[2].runner-root=/workspaces/sales",
			"synvo.codex.workspaces[2].native-chat-default=false",
			"synvo.codex.workspaces[2].write-enabled=true",
			"synvo.codex.workspaces[2].repository-label=Synvo Workspaces/Sales",
			"synvo.codex.allowed-mcp-servers[0]=synvo_safe_fixture"
		})
@ActiveProfiles("test")
class CodexEnabledApplicationContextTests {

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private WorkspaceAgentEngine engine;

	@Autowired
	private WorkspaceRegistry workspaces;

	@Test
	void enabledApplicationUsesBootJsonMapperAndRealRunnerAdapter() {
		assertNotNull(objectMapper);
		assertInstanceOf(CodexRunnerClient.class, engine);
		assertEquals(List.of("Finance", "Products", "Sales"), workspaces.summaries().stream()
				.map(WorkspaceRegistry.WorkspaceSummary::displayName)
				.toList());
		assertEquals("products", workspaces.requireNativeChatDefault().id());
	}
}
