package synvo.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalIntegrationConfigurationTests {

	@Test
	void disabledIntegrationsAllowEmptyCredentials() {
		try (AnnotationConfigApplicationContext context = contextWith(
				"synvo.lark.enabled=false",
				"synvo.model.enabled=false",
				"synvo.codex.enabled=false")) {
			assertNotNull(context.getBean(LarkProperties.class));
			assertNotNull(context.getBean(ModelProperties.class));
			assertNotNull(context.getBean(CodexProperties.class));
		}
	}

	@Test
	void enabledCodexRequiresThePinnedStableRuntimeAndOneConfiguredDefaultWorkspace() {
		RuntimeException substituted = assertThrows(RuntimeException.class, () -> contextWith(
				"synvo.codex.enabled=true",
				"synvo.codex.runner-base-url=http://codex-runner:8765",
				"synvo.codex.model=another-model",
				"synvo.codex.runtime-version=0.148.0"));
		assertFalse(allMessages(substituted).contains("another-model"));

		RuntimeException unbounded = assertThrows(RuntimeException.class, () -> contextWith(
				"synvo.codex.enabled=true",
				"synvo.codex.runner-base-url=http://codex-runner:8765",
				"synvo.codex.model=gpt-5.6-sol",
				"synvo.codex.runtime-version=0.148.0",
				"synvo.codex.workspaces[0].id=pilot",
				"synvo.codex.workspaces[0].display-name=Pilot",
				"synvo.codex.workspaces[0].runner-root=relative/path",
				"synvo.codex.workspaces[0].native-chat-default=true"));
		assertFalse(allMessages(unbounded).contains("relative/path"));
	}

	@Test
	void enabledCodexAcceptsOneCanonicalWorkspaceWithoutPrintingItsPath() {
		String privatePath = "/workspaces/must-not-appear-pilot";
		try (AnnotationConfigApplicationContext context = contextWith(
				"synvo.codex.enabled=true",
				"synvo.codex.runner-base-url=http://codex-runner:8765",
				"synvo.codex.model=gpt-5.6-sol",
				"synvo.codex.runtime-version=0.148.0",
				"synvo.codex.workspaces[0].id=pilot",
				"synvo.codex.workspaces[0].display-name=Pilot",
				"synvo.codex.workspaces[0].runner-root=" + privatePath,
				"synvo.codex.workspaces[0].native-chat-default=true",
				"synvo.codex.workspaces[0].write-enabled=true")) {
			String rendered = context.getBean(CodexProperties.class).toString();
			assertFalse(rendered.contains(privatePath));
		}
	}

	@Test
	void codexConfigurationRejectsLarkMcpAccess() {
		assertThrows(RuntimeException.class, () -> contextWith(
				"synvo.codex.enabled=true",
				"synvo.codex.runner-base-url=http://codex-runner:8765",
				"synvo.codex.workspaces[0].id=pilot",
				"synvo.codex.workspaces[0].display-name=Pilot",
				"synvo.codex.workspaces[0].runner-root=/workspaces/pilot",
				"synvo.codex.workspaces[0].native-chat-default=true",
				"synvo.codex.allowed-mcp-servers[0]=lark-drive"));
	}

	@Test
	void enabledLarkRequiresCredentialsWithoutLeakingProvidedValues() {
		String secret = "must-not-appear-lark-secret";
		RuntimeException failure = assertThrows(RuntimeException.class, () -> contextWith(
				"synvo.lark.enabled=true",
				"synvo.lark.app-id=",
				"synvo.lark.app-secret=" + secret,
				"synvo.lark.pilot-open-id=ou-pilot",
				"synvo.lark.token-encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="));

		assertFalse(allMessages(failure).contains(secret));
	}

	@Test
	void enabledLarkRequiresPilotAndValidEncryptionKey() {
		RuntimeException failure = assertThrows(RuntimeException.class, () -> contextWith(
				"synvo.lark.enabled=true",
				"synvo.lark.app-id=cli-test",
				"synvo.lark.app-secret=secret-test",
				"synvo.lark.pilot-open-id=",
				"synvo.lark.token-encryption-key=not-a-key"));

		assertFalse(allMessages(failure).contains("secret-test"));
		assertFalse(allMessages(failure).contains("not-a-key"));
	}

	@Test
	void enabledLarkRequiresAnAppSecretAndRedactsAllConfiguredIdentifiers() {
		String appId = "must-not-appear-app-id";
		String pilotOpenId = "must-not-appear-open-id";
		String encryptionKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
		RuntimeException failure = assertThrows(RuntimeException.class, () -> contextWith(
				"synvo.lark.enabled=true",
				"synvo.lark.app-id=" + appId,
				"synvo.lark.app-secret=",
				"synvo.lark.pilot-open-id=" + pilotOpenId,
				"synvo.lark.token-encryption-key=" + encryptionKey));

		String messages = allMessages(failure);
		assertFalse(messages.contains(appId));
		assertFalse(messages.contains(pilotOpenId));
		assertFalse(messages.contains(encryptionKey));
	}

	@Test
	void enabledModelRequiresCompleteConfigurationWithoutLeakingProvidedValues() {
		String secret = "must-not-appear-model-secret";
		RuntimeException failure = assertThrows(RuntimeException.class, () -> contextWith(
				"synvo.model.enabled=true",
				"synvo.model.base-url=https://model.example.test",
				"synvo.model.name=",
				"synvo.model.api-key=" + secret));

		assertFalse(allMessages(failure).contains(secret));
	}

	@Test
	void enabledModelRejectsAnInvalidEndpointWithoutLeakingIt() {
		String invalidEndpoint = "must-not-appear-invalid-model-endpoint";
		RuntimeException failure = assertThrows(RuntimeException.class, () -> contextWith(
				"synvo.model.enabled=true",
				"synvo.model.base-url=" + invalidEndpoint,
				"synvo.model.name=nvidia/nemotron-3-super-120b-a12b",
				"synvo.model.api-key=test-key"));

		assertFalse(allMessages(failure).contains(invalidEndpoint));
	}

	@Test
	void agentTimeoutUsesASafeDefaultAndRejectsOutOfRangeValues() {
		try (AnnotationConfigApplicationContext context = contextWith(
				"synvo.lark.enabled=false",
				"synvo.model.enabled=false")) {
			assertNotNull(context.getBean(AgentRuntimeProperties.class).responseTimeout());
		}

		assertThrows(RuntimeException.class, () -> contextWith(
				"synvo.agent.response-timeout=999ms",
				"synvo.lark.enabled=false",
				"synvo.model.enabled=false"));
		assertThrows(RuntimeException.class, () -> contextWith(
				"synvo.agent.response-timeout=121m",
				"synvo.lark.enabled=false",
				"synvo.model.enabled=false"));
	}

	private AnnotationConfigApplicationContext contextWith(String... properties) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		TestPropertyValues.of(properties).applyTo(context);
		context.register(ExternalIntegrationConfiguration.class);
		context.refresh();
		return context;
	}

	private String allMessages(Throwable failure) {
		StringBuilder messages = new StringBuilder();
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current.getMessage() != null) {
				messages.append(current.getMessage());
			}
		}
		return messages.toString();
	}
}
