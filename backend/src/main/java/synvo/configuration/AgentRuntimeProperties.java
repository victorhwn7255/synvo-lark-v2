package synvo.configuration;

import java.time.Duration;
import jakarta.validation.constraints.AssertTrue;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("synvo.agent")
public record AgentRuntimeProperties(Duration responseTimeout) {

	public AgentRuntimeProperties {
		if (responseTimeout == null) {
			responseTimeout = Duration.ofMinutes(2);
		}
	}

	@AssertTrue(message = "Agent response timeout must be between 1 second and 10 minutes")
	public boolean isResponseTimeoutValid() {
		return responseTimeout != null
				&& responseTimeout.compareTo(Duration.ofSeconds(1)) >= 0
				&& responseTimeout.compareTo(Duration.ofMinutes(10)) <= 0;
	}
}
