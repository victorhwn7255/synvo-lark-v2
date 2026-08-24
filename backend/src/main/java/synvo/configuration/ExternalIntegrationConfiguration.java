package synvo.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
		LarkProperties.class,
		ModelProperties.class,
		AgentRuntimeProperties.class,
		CodexProperties.class
})
class ExternalIntegrationConfiguration {
}
