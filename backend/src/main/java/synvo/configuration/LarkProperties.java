package synvo.configuration;

import jakarta.validation.constraints.AssertTrue;
import java.time.Duration;
import java.util.Base64;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import org.springframework.util.StringUtils;

@Validated
@ConfigurationProperties("synvo.lark")
public record LarkProperties(
		boolean enabled,
		String appId,
		String appSecret,
		String transport,
		String pilotOpenId,
		String h5BaseUrl,
		String tokenEncryptionKey,
		Duration tokenRefreshSkew,
		Duration messageRetention) {

	public LarkProperties {
		transport = defaultIfBlank(transport, "websocket");
		tokenRefreshSkew = tokenRefreshSkew == null ? Duration.ofMinutes(5) : tokenRefreshSkew;
		messageRetention = messageRetention == null ? Duration.ofDays(30) : messageRetention;
	}

	@AssertTrue(message = "Lark app ID, app secret, pilot open ID, and a Base64 AES-256 key are required when Lark is enabled")
	public boolean isConfigurationValid() {
		return !enabled || (StringUtils.hasText(appId)
				&& StringUtils.hasText(appSecret)
				&& StringUtils.hasText(pilotOpenId)
				&& hasValidEncryptionKey(tokenEncryptionKey));
	}

	@AssertTrue(message = "Only the websocket Lark transport is supported")
	public boolean isTransportValid() {
		return !enabled || "websocket".equals(transport);
	}

	@AssertTrue(message = "Lark token refresh skew and message retention must be positive")
	public boolean areDurationsValid() {
		return !tokenRefreshSkew.isNegative()
				&& !tokenRefreshSkew.isZero()
				&& !messageRetention.isNegative()
				&& !messageRetention.isZero();
	}

	@Override
	public String toString() {
		return "LarkProperties[enabled=" + enabled
				+ ", appId=" + redact(appId)
				+ ", appSecret=" + redact(appSecret)
				+ ", transport=" + transport
				+ ", pilotOpenId=" + redact(pilotOpenId)
				+ ", h5BaseUrl=" + redact(h5BaseUrl)
				+ ", tokenEncryptionKey=" + redact(tokenEncryptionKey)
				+ ", tokenRefreshSkew=" + tokenRefreshSkew
				+ ", messageRetention=" + messageRetention + "]";
	}

	private static String redact(String value) {
		return StringUtils.hasText(value) ? "[configured]" : "[not configured]";
	}

	private static String defaultIfBlank(String value, String defaultValue) {
		return StringUtils.hasText(value) ? value : defaultValue;
	}

	private static boolean hasValidEncryptionKey(String value) {
		if (!StringUtils.hasText(value)) {
			return false;
		}
		try {
			return Base64.getDecoder().decode(value).length == 32;
		}
		catch (IllegalArgumentException exception) {
			return false;
		}
	}
}
