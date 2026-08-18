package synvo.configuration;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import synvo.agent.model.DisabledModelGateway;
import synvo.agent.model.ModelGateway;
import synvo.agent.model.NemotronModelGateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AgentModelConfigurationTests {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
			.withUserConfiguration(
					ExternalIntegrationConfiguration.class,
					AgentModelConfiguration.class);

	@Test
	void modelIsDisabledByDefaultWithoutRequiringCredentials() {
		contextRunner.run(context -> {
			assertFalse(context.getStartupFailure() != null);
			assertEquals(DisabledModelGateway.class, context.getBean(ModelGateway.class).getClass());
		});
	}

	@Test
	void validNemotronConfigurationCreatesOnlyTheSynvoGatewayBoundary() {
		contextRunner.withPropertyValues(
				"synvo.model.enabled=true",
				"synvo.model.base-url=https://integrate.api.nvidia.com/v1",
				"synvo.model.name=nvidia/nemotron-3-super-120b-a12b",
				"synvo.model.api-key=test-only-key")
				.run(context -> {
					assertFalse(context.getStartupFailure() != null);
					assertEquals(NemotronModelGateway.class, context.getBean(ModelGateway.class).getClass());
					String properties = context.getBean(ModelProperties.class).toString();
					assertFalse(properties.contains("test-only-key"));
					assertFalse(properties.contains("integrate.api.nvidia.com"));
					assertFalse(properties.contains("nemotron-3-super"));
				});
	}

	@Test
	void nemotronOptionsUseTheProviderContractAndDisableReasoningOutput() {
		OpenAiChatOptions options = AgentModelConfiguration.nemotronOptions(new ModelProperties(
				true,
				"https://integrate.api.nvidia.com/v1",
				"nvidia/nemotron-3-super-120b-a12b",
				"test-only-key"));

		assertEquals("nvidia/nemotron-3-super-120b-a12b", options.getModel());
		assertEquals(1.0, options.getTemperature());
		assertEquals(0.95, options.getTopP());
		assertEquals(4096, options.getMaxTokens());
		assertEquals(
				Map.of("chat_template_kwargs", Map.of("enable_thinking", false)),
				options.getExtraBody());
	}
}
