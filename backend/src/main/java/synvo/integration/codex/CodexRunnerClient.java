package synvo.integration.codex;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import synvo.configuration.CodexProperties;
import synvo.workspaceagent.WorkspaceAgentEngine;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionField;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionFieldType;
import synvo.workspaceagent.WorkspaceAgentException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Private adapter for the compressed runner transport. */
public final class CodexRunnerClient implements WorkspaceAgentEngine {

	private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;

	private final URI baseUrl;
	private final Duration requestTimeout;
	private final Duration activityTimeout;
	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;

	public CodexRunnerClient(CodexProperties properties, ObjectMapper objectMapper) {
		this(
				properties.runnerBaseUrl(),
				properties.requestTimeout(),
				properties.activityPollTimeout(),
				HttpClient.newBuilder()
						.connectTimeout(properties.requestTimeout())
						.followRedirects(HttpClient.Redirect.NEVER)
						.build(),
				objectMapper);
	}

	CodexRunnerClient(
			URI baseUrl,
			Duration requestTimeout,
			Duration activityTimeout,
			HttpClient httpClient,
			ObjectMapper objectMapper) {
		this.baseUrl = baseUrl;
		this.requestTimeout = requestTimeout;
		this.activityTimeout = activityTimeout;
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
	}

	@Override
	public EngineStatus status() {
		try {
			JsonNode health = request("GET", "/health", null, requestTimeout, 200);
			return switch (text(health, "state")) {
				case "ready" -> EngineStatus.READY;
				case "disabled" -> EngineStatus.DISABLED;
				default -> EngineStatus.UNAVAILABLE;
			};
		}
		catch (WorkspaceAgentException unavailable) {
			return EngineStatus.UNAVAILABLE;
		}
	}

	@Override
	public Capabilities capabilities() {
		JsonNode body = request("GET", "/v1/capabilities", null, requestTimeout, 200);
		String runtime = requiredText(body, "runtimeVersion");
		String model = requiredText(body, "model");
		if (!CodexProperties.REQUIRED_RUNTIME.equals(runtime)
				|| !CodexProperties.REQUIRED_MODEL.equals(model)) {
			throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
		}
		return new Capabilities(
				runtime,
				model,
				stringList(body.path("reasoningEfforts"), 16, 32),
				stringList(body.path("enabledFeatures"), 256, 100));
	}

	@Override
	public AccountStatus account() {
		JsonNode body = request("GET", "/v1/account", null, requestTimeout, 200);
		return new AccountStatus(
				text(body, "authentication"),
				body.path("requiresAuthentication").asBoolean(false),
				text(body, "plan"),
				body.path("usedPercent").isNumber()
						? body.path("usedPercent").doubleValue() : null,
				body.path("resetsAt").canConvertToLong()
						? Instant.ofEpochSecond(body.path("resetsAt").longValue()) : null);
	}

	@Override
	public TaskHandle createTask(WorkspaceTarget workspace, RunMode mode) {
		JsonNode body = request(
				"POST",
				"/v1/tasks",
				workspaceBody(workspace, mode),
				requestTimeout,
				201);
		return taskHandle(body);
	}

	@Override
	public TaskHandle forkTask(String taskReference, WorkspaceTarget workspace) {
		JsonNode body = request(
				"POST",
				"/v1/tasks/" + segment(taskReference) + "/fork",
				workspaceBody(workspace, null),
				requestTimeout,
				201);
		return taskHandle(body);
	}

	@Override
	public void resumeTask(String taskReference, WorkspaceTarget workspace) {
		request(
				"POST",
				"/v1/tasks/" + segment(taskReference) + "/resume",
				workspaceBody(workspace, null),
				requestTimeout,
				200);
	}

	@Override
	public void renameTask(String taskReference, String title) {
		request(
				"POST",
				"/v1/tasks/" + segment(taskReference) + "/rename",
				Map.of("name", title),
				requestTimeout,
				204);
	}

	@Override
	public void archiveTask(String taskReference) {
		emptyTaskAction(taskReference, "archive");
	}

	@Override
	public void unarchiveTask(String taskReference) {
		emptyTaskAction(taskReference, "unarchive");
	}

	@Override
	public void deleteTask(String taskReference) {
		request(
				"DELETE",
				"/v1/tasks/" + segment(taskReference),
				Map.of(),
				requestTimeout,
				204);
	}

	@Override
	public OperationHandle startTurn(
			String taskReference,
			WorkspaceTarget workspace,
			RunMode mode,
			TurnInput input) {
		Map<String, Object> body = new LinkedHashMap<>(workspaceBody(workspace, mode));
		body.put("text", input.text());
		body.put("effort", input.reasoningEffort());
		if (input.skillName() != null) {
			body.put("skillName", input.skillName());
		}
		return operationHandle(request(
				"POST",
				"/v1/tasks/" + segment(taskReference) + "/turns",
				body,
				requestTimeout,
				202));
	}

	@Override
	public OperationHandle startReview(
			String taskReference,
			WorkspaceTarget workspace,
			ReviewTarget target) {
		Map<String, Object> review = new LinkedHashMap<>();
		review.put("type", switch (target.kind()) {
			case UNCOMMITTED_CHANGES -> "uncommittedChanges";
			case BASE_BRANCH -> "baseBranch";
			case COMMIT -> "commit";
			case CUSTOM -> "custom";
		});
		if (target.value() != null) {
			review.put(switch (target.kind()) {
				case BASE_BRANCH -> "branch";
				case COMMIT -> "sha";
				case CUSTOM -> "instructions";
				case UNCOMMITTED_CHANGES -> throw new IllegalStateException();
			}, target.value());
		}
		Map<String, Object> body = new LinkedHashMap<>(workspaceBody(workspace, null));
		body.put("target", review);
		return operationHandle(request(
				"POST",
				"/v1/tasks/" + segment(taskReference) + "/reviews",
				body,
				requestTimeout,
				202));
	}

	@Override
	public ActivityBatch waitForActivity(String operationReference, long afterSequence) {
		JsonNode body = request(
				"GET",
				"/v1/operations/" + segment(operationReference)
						+ "/events?after=" + afterSequence,
				null,
				activityTimeout.plusSeconds(2),
				200);
		List<Activity> activities = new ArrayList<>();
		JsonNode rows = body.path("events");
		if (!rows.isArray()) {
			throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
		}
		for (JsonNode row : rows) {
			activities.add(activity(row));
		}
		return new ActivityBatch(activities, body.path("terminal").asBoolean(false));
	}

	@Override
	public List<InteractionRequest> pendingInteractions(String operationReference) {
		JsonNode body = request(
				"GET",
				"/v1/operations/" + segment(operationReference) + "/interactions",
				null,
				requestTimeout,
				200);
		JsonNode rows = body.path("interactions");
		if (!rows.isArray()) {
			throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
		}
		List<InteractionRequest> interactions = new ArrayList<>();
		for (JsonNode row : rows) {
			interactions.add(interaction(row));
		}
		return List.copyOf(interactions);
	}

	@Override
	public void decideInteraction(
			String operationReference,
			String interactionReference,
			InteractionDecision decision,
			Map<String, String> formValues) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("interactionId", interactionReference);
		body.put("decision", decisionValue(decision));
		if (formValues != null && !formValues.isEmpty()) {
			body.put("content", Map.copyOf(formValues));
		}
		request(
				"POST",
				"/v1/operations/" + segment(operationReference) + "/decisions",
				body,
				requestTimeout,
				204);
	}

	@Override
	public void steer(String operationReference, String text) {
		operationAction(operationReference, "steer", Map.of("text", text));
	}

	@Override
	public void stop(String operationReference) {
		operationAction(operationReference, "stop", Map.of());
	}

	@Override
	public Inventory inventory(String taskReference, WorkspaceTarget workspace) {
		JsonNode body = request(
				"POST",
				"/v1/tasks/" + segment(taskReference) + "/inventory",
				workspaceBody(workspace, null),
				requestTimeout,
				200);
		List<Skill> skills = new ArrayList<>();
		for (JsonNode row : body.path("skills")) {
			skills.add(new Skill(requiredText(row, "name"), text(row, "description")));
		}
		List<McpServer> servers = new ArrayList<>();
		for (JsonNode row : body.path("mcpServers")) {
			servers.add(new McpServer(
					requiredText(row, "name"),
					text(row, "authStatus"),
					stringList(row.path("tools"), 256, 200)));
		}
		return new Inventory(skills, servers);
	}

	@Override
	public Optional<Goal> goal(String taskReference) {
		JsonNode body = request(
				"GET",
				"/v1/tasks/" + segment(taskReference) + "/goal",
				null,
				requestTimeout,
				200);
		if (body.path("goal").isNull()) {
			return Optional.empty();
		}
		return Optional.of(new Goal(
				requiredText(body, "objective"),
				requiredText(body, "status"),
				body.path("tokensUsed").asLong(0),
				body.path("timeUsedSeconds").asLong(0)));
	}

	@Override
	public void setGoal(String taskReference, String objective, GoalCommand command) {
		Map<String, String> body = switch (command) {
			case SAVE -> Map.of("objective", objective);
			case RESUME -> Map.of("objective", objective, "status", "active");
			case PAUSE -> Map.of("objective", objective, "status", "paused");
		};
		request(
				"POST",
				"/v1/tasks/" + segment(taskReference) + "/goal",
				body,
				requestTimeout,
				204);
	}

	@Override
	public void clearGoal(String taskReference) {
		request(
				"DELETE",
				"/v1/tasks/" + segment(taskReference) + "/goal",
				Map.of(),
				requestTimeout,
				204);
	}

	private Activity activity(JsonNode row) {
		long sequence = row.path("sequence").asLong(-1);
		String kind = requiredText(row, "kind");
		JsonNode payload = row.path("payload");
		ActivityKind activityKind = switch (kind) {
			case "turn_started" -> ActivityKind.TURN_STARTED;
			case "message_delta" -> ActivityKind.MESSAGE_DELTA;
			case "message_completed" -> ActivityKind.MESSAGE_COMPLETED;
			case "plan_started" -> ActivityKind.PLAN_STARTED;
			case "plan_delta" -> ActivityKind.PLAN_DELTA;
			case "plan_completed" -> ActivityKind.PLAN_COMPLETED;
			case "plan_updated" -> ActivityKind.PLAN_UPDATED;
			case "reasoning_started" -> ActivityKind.REASONING_STARTED;
			case "reasoning_delta" -> ActivityKind.REASONING_DELTA;
			case "reasoning_completed" -> ActivityKind.REASONING_COMPLETED;
			case "command_started" -> ActivityKind.COMMAND_STARTED;
			case "command_output" -> ActivityKind.COMMAND_OUTPUT;
			case "command_completed" -> ActivityKind.COMMAND_COMPLETED;
			case "file_change_started" -> ActivityKind.FILE_CHANGE_STARTED;
			case "file_output" -> ActivityKind.FILE_OUTPUT;
			case "diff" -> ActivityKind.DIFF;
			case "file_change_completed" -> ActivityKind.FILE_CHANGE_COMPLETED;
			case "mcp_started" -> ActivityKind.MCP_STARTED;
			case "mcp_progress" -> ActivityKind.MCP_PROGRESS;
			case "mcp_completed" -> ActivityKind.MCP_COMPLETED;
			case "nested_activity_started" -> ActivityKind.NESTED_ACTIVITY_STARTED;
			case "nested_activity_completed" -> ActivityKind.NESTED_ACTIVITY_COMPLETED;
			case "review_entered" -> ActivityKind.REVIEW_ENTERED;
			case "review_exited" -> ActivityKind.REVIEW_EXITED;
			case "compacted" -> ActivityKind.COMPACTED;
			case "usage_updated" -> ActivityKind.USAGE_UPDATED;
			case "interaction_resolved" -> ActivityKind.INTERACTION_RESOLVED;
			case "wait_started" -> ActivityKind.WAIT_STARTED;
			case "wait_completed" -> ActivityKind.WAIT_COMPLETED;
			case "turn_completed" -> ActivityKind.TURN_COMPLETED;
			default -> throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
		};
		TerminalStatus terminalStatus = activityKind == ActivityKind.TURN_COMPLETED
				? terminalStatus(text(payload, "status")) : null;
		return new Activity(
				sequence,
				activityKind,
				text(payload, "text"),
				payload.path("truncated").asBoolean(false),
				text(payload, "itemRef"),
				terminalStatus);
	}

	private InteractionRequest interaction(JsonNode row) {
		JsonNode detail = row.path("detail");
		List<String> paths = optionalStringList(detail.path("affectedPaths"), 100, 1024);
		List<InteractionField> fields = interactionFields(detail.path("fields"));
		double expiresAt = row.path("expiresAt").asDouble(-1);
		if (expiresAt <= 0) {
			throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
		}
		return new InteractionRequest(
				requiredText(row, "interactionId"),
				requiredText(row, "workspaceId"),
				interactionKind(requiredText(row, "kind")),
				requiredText(row, "category"),
				requiredText(row, "reason"),
				decisions(row.path("availableDecisions")),
				new InteractionDetail(
						text(detail, "command"),
						text(detail, "workingDirectory"),
						paths,
						text(detail, "mcpServer"),
						text(detail, "mcpTool"),
						text(detail, "message"),
						text(detail, "mode"),
						text(detail, "elicitationUrl"),
						fields),
				Instant.ofEpochMilli((long) (expiresAt * 1_000)));
	}

	private static List<InteractionField> interactionFields(JsonNode node) {
		if (node.isMissingNode() || node.isNull()) {
			return List.of();
		}
		if (!node.isArray() || node.size() > 20) {
			throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
		}
		List<InteractionField> fields = new ArrayList<>();
		for (JsonNode field : node) {
			String name = requiredText(field, "name");
			String label = requiredText(field, "label");
			if (name.length() > 100 || label.length() > 120) {
				throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
			}
			InteractionFieldType type = switch (requiredText(field, "type")) {
				case "string" -> InteractionFieldType.TEXT;
				case "boolean" -> InteractionFieldType.BOOLEAN;
				case "number" -> InteractionFieldType.NUMBER;
				case "integer" -> InteractionFieldType.INTEGER;
				case "select" -> InteractionFieldType.SELECT;
				default -> throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
			};
			if (!field.path("required").isBoolean() || !field.path("maxLength").canConvertToInt()) {
				throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
			}
			int maxLength = field.path("maxLength").intValue();
			if (maxLength < 0 || maxLength > 2_000) {
				throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
			}
			List<String> options = optionalStringList(field.path("options"), 20, 200);
			fields.add(new InteractionField(
					name, label, type, field.path("required").booleanValue(),
					options, maxLength));
		}
		return List.copyOf(fields);
	}

	private JsonNode request(
			String method,
			String path,
			Map<String, ?> body,
			Duration timeout,
			int expectedStatus) {
		try {
			HttpRequest.Builder builder = HttpRequest.newBuilder(baseUrl.resolve(path))
					.timeout(timeout)
					.header("Accept", "application/json")
					.header("Cache-Control", "no-store");
			if (body == null) {
				builder.method(method, HttpRequest.BodyPublishers.noBody());
			}
			else {
				builder.header("Content-Type", "application/json")
						.method(method, HttpRequest.BodyPublishers.ofByteArray(
								objectMapper.writeValueAsBytes(body)));
			}
			HttpResponse<InputStream> response = httpClient.send(
					builder.build(), HttpResponse.BodyHandlers.ofInputStream());
			byte[] bytes;
			try (InputStream stream = response.body()) {
				bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
			}
			if (bytes.length > MAX_RESPONSE_BYTES) {
				throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
			}
			JsonNode payload = bytes.length == 0
					? objectMapper.createObjectNode()
					: objectMapper.readTree(bytes);
			if (response.statusCode() != expectedStatus) {
				throw failure(errorCode(response.statusCode(), payload));
			}
			if (!payload.isObject()) {
				throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
			}
			return payload;
		}
		catch (WorkspaceAgentException known) {
			throw known;
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw failure(WorkspaceAgentException.Code.UNAVAILABLE);
		}
		catch (IOException | RuntimeException failure) {
			throw new WorkspaceAgentException(
					WorkspaceAgentException.Code.UNAVAILABLE, failure);
		}
	}

	private WorkspaceAgentException.Code errorCode(int status, JsonNode body) {
		String error = text(body, "error");
		if (status == 409 && "ENGINE_BUSY".equals(error)) {
			return WorkspaceAgentException.Code.BUSY;
		}
		if (status == 409 && "INTERACTION_CONFLICT".equals(error)) {
			return WorkspaceAgentException.Code.INTERACTION_CONFLICT;
		}
		if (status == 404) {
			return WorkspaceAgentException.Code.NOT_FOUND;
		}
		if (status == 400) {
			return WorkspaceAgentException.Code.INVALID_REQUEST;
		}
		if (status == 503 && "RUNNER_DISABLED".equals(error)) {
			return WorkspaceAgentException.Code.DISABLED;
		}
		return WorkspaceAgentException.Code.UNAVAILABLE;
	}

	private void emptyTaskAction(String taskReference, String action) {
		request(
				"POST",
				"/v1/tasks/" + segment(taskReference) + "/" + action,
				Map.of(),
				requestTimeout,
				204);
	}

	private void operationAction(String operationReference, String action, Map<String, ?> body) {
		request(
				"POST",
				"/v1/operations/" + segment(operationReference) + "/" + action,
				body,
				requestTimeout,
				204);
	}

	private static Map<String, Object> workspaceBody(
			WorkspaceTarget workspace,
			RunMode mode) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("workspaceId", workspace.id());
		body.put("workspacePath", workspace.canonicalRoot().toString());
		if (mode != null) {
			body.put("mode", mode == RunMode.READ_ONLY ? "readOnly" : "workspaceWrite");
		}
		return body;
	}

	private static TaskHandle taskHandle(JsonNode body) {
		String model = requiredText(body, "model");
		if (!CodexProperties.REQUIRED_MODEL.equals(model)) {
			throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
		}
		return new TaskHandle(requiredText(body, "engineRef"), model);
	}

	private static OperationHandle operationHandle(JsonNode body) {
		return new OperationHandle(requiredText(body, "operationId"));
	}

	private static InteractionKind interactionKind(String value) {
		return switch (value) {
			case "command" -> InteractionKind.COMMAND_APPROVAL;
			case "file" -> InteractionKind.FILE_CHANGE_APPROVAL;
			case "mcp_tool" -> InteractionKind.MCP_TOOL_APPROVAL;
			case "mcp_elicitation" -> InteractionKind.MCP_ELICITATION;
			default -> throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
		};
	}

	private static List<InteractionDecision> decisions(JsonNode values) {
		List<InteractionDecision> decisions = new ArrayList<>();
		for (String value : stringList(values, 8, 32)) {
			decisions.add(switch (value) {
				case "accept" -> InteractionDecision.APPROVE_ONCE;
				case "decline" -> InteractionDecision.DECLINE;
				case "cancel" -> InteractionDecision.CANCEL;
				default -> throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
			});
		}
		return List.copyOf(decisions);
	}

	private static String decisionValue(InteractionDecision decision) {
		return switch (decision) {
			case APPROVE_ONCE -> "accept";
			case DECLINE -> "decline";
			case CANCEL -> "cancel";
		};
	}

	private static TerminalStatus terminalStatus(String value) {
		return switch (value == null ? "" : value) {
			case "completed" -> TerminalStatus.COMPLETED;
			case "stopped", "interrupted" -> TerminalStatus.STOPPED;
			case "timeout" -> TerminalStatus.TIMEOUT;
			case "usageLimit", "usageLimited" -> TerminalStatus.USAGE_LIMITED;
			case "authenticationRequired" -> TerminalStatus.AUTHENTICATION_REQUIRED;
			case "protocolIncompatible" -> TerminalStatus.PROTOCOL_INCOMPATIBLE;
			case "runnerUnavailable", "engineError" -> TerminalStatus.ENGINE_UNAVAILABLE;
			default -> TerminalStatus.FAILED;
		};
	}

	private static List<String> stringList(JsonNode node, int maxValues, int maxLength) {
		if (!node.isArray() || node.size() > maxValues) {
			throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
		}
		List<String> values = new ArrayList<>();
		for (JsonNode value : node) {
			if (!value.isTextual() || value.textValue().length() > maxLength) {
				throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
			}
			values.add(value.textValue());
		}
		return List.copyOf(values);
	}

	private static List<String> optionalStringList(
			JsonNode node,
			int maxValues,
			int maxLength) {
		return node.isMissingNode() || node.isNull()
				? List.of() : stringList(node, maxValues, maxLength);
	}

	private static String requiredText(JsonNode body, String name) {
		String value = text(body, name);
		if (value == null || value.isBlank()) {
			throw failure(WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
		}
		return value;
	}

	private static String text(JsonNode body, String name) {
		JsonNode value = body.path(name);
		return value.isTextual() ? value.textValue() : null;
	}

	private static String segment(String value) {
		if (value == null || value.isBlank() || value.length() > 256) {
			throw failure(WorkspaceAgentException.Code.INVALID_REQUEST);
		}
		return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
	}

	private static WorkspaceAgentException failure(WorkspaceAgentException.Code code) {
		return new WorkspaceAgentException(code);
	}
}
