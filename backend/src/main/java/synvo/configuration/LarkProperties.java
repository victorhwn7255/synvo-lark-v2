package synvo.configuration;

import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.StringUtils;

@Validated
@ConfigurationProperties("synvo.lark")
public record LarkProperties(boolean enabled, String appId, String appSecret) {

	@AssertTrue(message = "Lark app ID and app secret are required when Lark is enabled")
	public boolean isConfigurationValid() {
		return !enabled || (StringUtils.hasText(appId) && StringUtils.hasText(appSecret));
	}

	@Override
	public String toString() {
		return "LarkProperties[enabled=" + enabled + ", appId=" + redact(appId)
				+ ", appSecret=[redacted]]";
	}

	private static String redact(String value) {
		return StringUtils.hasText(value) ? "[configured]" : "[not configured]";
	}
}
