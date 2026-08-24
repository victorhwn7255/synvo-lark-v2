package synvo.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.StringUtils;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("synvo.codex")
public record CodexProperties(
		boolean enabled,
		URI runnerBaseUrl,
		String model,
		String runtimeVersion,
		String reasoningEffort,
		Duration requestTimeout,
		Duration activityPollTimeout,
		Duration interactionTimeout,
		List<@Valid Workspace> workspaces,
		List<String> allowedMcpServers
) {

	public static final String REQUIRED_MODEL = "gpt-5.6-sol";
	public static final String REQUIRED_RUNTIME = "0.148.0";

	public CodexProperties {
		model = defaultIfBlank(model, REQUIRED_MODEL);
		runtimeVersion = defaultIfBlank(runtimeVersion, REQUIRED_RUNTIME);
		reasoningEffort = defaultIfBlank(reasoningEffort, "high");
		requestTimeout = requestTimeout == null ? Duration.ofSeconds(30) : requestTimeout;
		activityPollTimeout = activityPollTimeout == null
				? Duration.ofSeconds(25) : activityPollTimeout;
		interactionTimeout = interactionTimeout == null
				? Duration.ofMinutes(5) : interactionTimeout;
		workspaces = workspaces == null ? List.of() : List.copyOf(workspaces);
		allowedMcpServers = allowedMcpServers == null
				? List.of() : List.copyOf(allowedMcpServers);
	}

	@AssertTrue(message = "The Codex runner must use the pinned model and runtime")
	public boolean isPinnedRuntimeValid() {
		return !enabled || (REQUIRED_MODEL.equals(model)
				&& REQUIRED_RUNTIME.equals(runtimeVersion));
	}

	@AssertTrue(message = "A private HTTP runner URL is required when Codex is enabled")
	public boolean isRunnerUrlValid() {
		if (!enabled) {
			return true;
		}
		return runnerBaseUrl != null
				&& "http".equalsIgnoreCase(runnerBaseUrl.getScheme())
				&& StringUtils.hasText(runnerBaseUrl.getHost())
				&& runnerBaseUrl.getUserInfo() == null
				&& runnerBaseUrl.getQuery() == null
				&& runnerBaseUrl.getFragment() == null
				&& (runnerBaseUrl.getPath() == null
						|| runnerBaseUrl.getPath().isEmpty()
						|| "/".equals(runnerBaseUrl.getPath()));
	}

	@AssertTrue(message = "Codex timeouts must be positive and bounded")
	public boolean isTimeoutsValid() {
		return positiveAndAtMost(requestTimeout, Duration.ofMinutes(2))
				&& positiveAndAtMost(activityPollTimeout, Duration.ofSeconds(30))
				&& positiveAndAtMost(interactionTimeout, Duration.ofMinutes(30));
	}

	@AssertTrue(message = "Enabled Codex requires unique configured workspaces and one native Chat default")
	public boolean isWorkspacesValid() {
		if (!enabled) {
			return true;
		}
		if (workspaces.isEmpty() || workspaces.stream().filter(Workspace::nativeChatDefault).count() != 1) {
			return false;
		}
		Set<String> ids = new HashSet<>();
		return workspaces.stream().allMatch(workspace -> workspace.isValid()
				&& ids.add(workspace.id()));
	}

	@AssertTrue(message = "Lark MCP access is forbidden and MCP server IDs must be unique")
	public boolean isMcpServersValid() {
		Set<String> identities = new HashSet<>();
		return allowedMcpServers.stream().allMatch(identity -> StringUtils.hasText(identity)
				&& identity.length() <= 100
				&& !identity.toLowerCase(Locale.ROOT).contains("lark")
				&& !identity.toLowerCase(Locale.ROOT).contains("feishu")
				&& identities.add(identity));
	}

	@Override
	public String toString() {
		return "CodexProperties[enabled=" + enabled
				+ ", runnerBaseUrl=" + configured(runnerBaseUrl)
				+ ", model=" + model
				+ ", runtimeVersion=" + runtimeVersion
				+ ", reasoningEffort=" + reasoningEffort
				+ ", requestTimeout=" + requestTimeout
				+ ", activityPollTimeout=" + activityPollTimeout
				+ ", interactionTimeout=" + interactionTimeout
				+ ", workspaces=" + workspaces.size()
				+ ", allowedMcpServers=" + allowedMcpServers.size() + "]";
	}

	private static boolean positiveAndAtMost(Duration value, Duration maximum) {
		return value != null && !value.isNegative() && !value.isZero()
				&& value.compareTo(maximum) <= 0;
	}

	private static String configured(Object value) {
		return value == null ? "[not configured]" : "[configured]";
	}

	private static String defaultIfBlank(String value, String defaultValue) {
		return StringUtils.hasText(value) ? value : defaultValue;
	}

	public record Workspace(
			String id,
			String displayName,
			String runnerRoot,
			boolean nativeChatDefault,
			boolean writeEnabled,
			String repositoryLabel
	) {
		boolean isValid() {
			if (!StringUtils.hasText(id) || id.length() > 100
					|| !StringUtils.hasText(displayName) || displayName.length() > 160
					|| !StringUtils.hasText(runnerRoot) || runnerRoot.length() > 4096) {
				return false;
			}
			try {
				Path path = Path.of(runnerRoot);
				return path.isAbsolute() && path.normalize().equals(path);
			}
			catch (InvalidPathException invalid) {
				return false;
			}
		}
	}
}
