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
				"synvo.model.enabled=false")) {
			assertNotNull(context.getBean(LarkProperties.class));
			assertNotNull(context.getBean(ModelProperties.class));
		}
	}

	@Test
	void enabledLarkRequiresCredentialsWithoutLeakingProvidedValues() {
		String secret = "must-not-appear-lark-secret";
		RuntimeException failure = assertThrows(RuntimeException.class, () -> contextWith(
				"synvo.lark.enabled=true",
				"synvo.lark.app-id=",
				"synvo.lark.app-secret=" + secret));

		assertFalse(allMessages(failure).contains(secret));
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
