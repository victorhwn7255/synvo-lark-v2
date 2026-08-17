package synvo.configuration;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("synvo.model")
public record ModelProperties(
		boolean enabled,
		String baseUrl,
		String name,
		String apiKey
) {

	@AssertTrue(message = "Model base URL, name, and API key are required when the model is enabled")
	public boolean isConfigurationValid() {
		return !enabled || (StringUtils.hasText(baseUrl)
				&& StringUtils.hasText(name)
				&& StringUtils.hasText(apiKey));
	}

	@Override
	public String toString() {
		return "ModelProperties[enabled=" + enabled + ", baseUrl=" + redact(baseUrl)
				+ ", name=" + redact(name) + ", apiKey=[redacted]]";
	}

	private static String redact(String value) {
		return StringUtils.hasText(value) ? "[configured]" : "[not configured]";
	}
}
