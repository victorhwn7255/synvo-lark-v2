package synvo.configuration;

import java.util.Map;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import synvo.agent.model.DisabledModelGateway;
import synvo.agent.model.ModelGateway;
import synvo.agent.model.NemotronModelGateway;

@Configuration(proxyBeanMethods = false)
class AgentModelConfiguration {

	@Bean
	@ConditionalOnProperty(prefix = "synvo.model", name = "enabled", havingValue = "true")
	ModelGateway nemotronModelGateway(ModelProperties properties) {
		OpenAiChatModel chatModel = OpenAiChatModel.builder()
				.options(nemotronOptions(properties))
				.build();
		return new NemotronModelGateway(chatModel);
	}

	static OpenAiChatOptions nemotronOptions(ModelProperties properties) {
		return OpenAiChatOptions.builder()
				.baseUrl(properties.baseUrl())
				.apiKey(properties.apiKey())
				.model(properties.name())
				.temperature(1.0)
				.topP(0.95)
				.maxTokens(4096)
				.extraBody(Map.of(
						"chat_template_kwargs", Map.of("enable_thinking", false)))
				.build();
	}

	@Bean
	@ConditionalOnMissingBean(ModelGateway.class)
	ModelGateway disabledModelGateway() {
		return new DisabledModelGateway();
	}
}
