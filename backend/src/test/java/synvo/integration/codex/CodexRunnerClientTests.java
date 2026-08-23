package synvo.integration.codex;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import synvo.workspaceagent.WorkspaceAgentEngine;
import synvo.workspaceagent.WorkspaceAgentEngine.ActivityKind;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDecision;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionKind;
import synvo.workspaceagent.WorkspaceAgentEngine.GoalCommand;
import synvo.workspaceagent.WorkspaceAgentEngine.RunMode;
import synvo.workspaceagent.WorkspaceAgentEngine.TerminalStatus;
import synvo.workspaceagent.WorkspaceAgentEngine.TurnInput;
import synvo.workspaceagent.WorkspaceAgentEngine.WorkspaceTarget;
import synvo.workspaceagent.WorkspaceAgentException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodexRunnerClientTests {

	private final ObjectMapper objectMapper = new ObjectMapper();
	private final List<ReceivedRequest> requests = new ArrayList<>();
	private HttpServer server;
	private CodexRunnerClient client;

	@BeforeEach
	void startServer() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", this::respond);
		server.start();
		client = new CodexRunnerClient(
				URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
				Duration.ofSeconds(2),
				Duration.ofSeconds(2),
				HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build(),
				objectMapper);
	}

	@AfterEach
	void stopServer() {
		server.stop(0);
	}

	@Test
	void verifiesPinnedCapabilitiesAndReturnsOnlyNormalizedAccountData() {
		var capabilities = client.capabilities();
		var account = client.account();

		assertEquals("0.148.0", capabilities.runtimeVersion());
		assertEquals("gpt-5.6-sol", capabilities.model());
		assertEquals(List.of("low", "high"), capabilities.reasoningEfforts());
		assertEquals("chatgpt", account.authentication());
		assertEquals("pro", account.plan());
	}

	@Test
	void taskAndTurnTransportKeepTheConfiguredPathPrivateToTheAdapter() {
		WorkspaceTarget workspace = new WorkspaceTarget(
				"pilot", Path.of("/workspaces/pilot"));
		var task = client.createTask(workspace, RunMode.READ_ONLY);
		var operation = client.startTurn(
				task.reference(), workspace, RunMode.WORKSPACE_WRITE,
				new TurnInput("Inspect and update", "high", "safe-skill"));

		assertEquals("private-task-ref", task.reference());
		assertEquals("private-operation-ref", operation.reference());
		JsonNode create = requests.stream()
				.filter(request -> request.path().equals("/v1/tasks"))
				.findFirst().orElseThrow().body();
		assertEquals("/workspaces/pilot", create.path("workspacePath").textValue());
		assertEquals("readOnly", create.path("mode").textValue());
		JsonNode turn = requests.stream()
				.filter(request -> request.path().endsWith("/turns"))
				.findFirst().orElseThrow().body();
		assertEquals("workspaceWrite", turn.path("mode").textValue());
		assertEquals("safe-skill", turn.path("skillName").textValue());
	}

	@Test
	void orderedActivityPreservesWhitespaceAndNormalizesOneTerminalOutcome() {
		var batch = client.waitForActivity("private-operation-ref", -1);

		assertTrue(batch.terminal());
		assertEquals(2, batch.activities().size());
		assertEquals(ActivityKind.MESSAGE_DELTA, batch.activities().getFirst().kind());
		assertEquals(" \n", batch.activities().getFirst().text());
		assertEquals(ActivityKind.TURN_COMPLETED, batch.activities().getLast().kind());
		assertEquals(TerminalStatus.COMPLETED, batch.activities().getLast().terminalStatus());
	}

	@Test
	void usageLimitTerminalIsMappedDeterministicallyWithoutRealAccountExhaustion() {
		var batch = client.waitForActivity("usage-operation-ref", -1);

		assertTrue(batch.terminal());
		assertEquals(1, batch.activities().size());
		assertEquals(ActivityKind.TURN_COMPLETED, batch.activities().getFirst().kind());
		assertEquals(
				TerminalStatus.USAGE_LIMITED,
				batch.activities().getFirst().terminalStatus());
	}

	@Test
	void interactionDecisionsUseOnlyNormalizedStableValues() {
		var interactions = client.pendingInteractions("private-operation-ref");
		var interaction = interactions.getFirst();

		assertEquals(InteractionKind.COMMAND_APPROVAL, interaction.kind());
		assertEquals(List.of(
				InteractionDecision.APPROVE_ONCE,
				InteractionDecision.DECLINE), interaction.availableDecisions());
		assertEquals("python3 -m unittest", interaction.detail().command());
		assertEquals("confirm", interactions.get(1).detail().fields().getFirst().name());
		assertEquals(
				WorkspaceAgentEngine.InteractionFieldType.BOOLEAN,
				interactions.get(1).detail().fields().getFirst().type());
		client.decideInteraction(
				"private-operation-ref",
				interaction.reference(),
				InteractionDecision.DECLINE,
				Map.of());

		ReceivedRequest decision = requests.getLast();
		assertEquals("decline", decision.body().path("decision").textValue());
		assertFalse(decision.body().has("content"));
	}

	@Test
	void goalCommandsMapToPrivateAppServerStatusesOnlyInsideTheAdapter() {
		client.setGoal("private-task-ref", "Maintain verified reports", GoalCommand.SAVE);
		client.setGoal("private-task-ref", "Maintain verified reports", GoalCommand.RESUME);
		client.setGoal("private-task-ref", "Maintain verified reports", GoalCommand.PAUSE);

		List<JsonNode> goalBodies = requests.stream()
				.filter(request -> request.path().endsWith("/goal"))
				.map(ReceivedRequest::body)
				.toList();
		assertEquals(3, goalBodies.size());
		assertFalse(goalBodies.get(0).has("status"));
		assertEquals("active", goalBodies.get(1).path("status").textValue());
		assertEquals("paused", goalBodies.get(2).path("status").textValue());
	}

	@Test
	void busyAndRawFailuresBecomeBoundedApplicationErrors() {
		WorkspaceAgentException busy = assertThrows(
				WorkspaceAgentException.class,
				() -> client.startTurn(
						"busy",
						new WorkspaceTarget("pilot", Path.of("/workspaces/pilot")),
						RunMode.READ_ONLY,
						new TurnInput("Compete", "high", null)));
		assertEquals(WorkspaceAgentException.Code.BUSY, busy.code());
		assertFalse(allMessages(busy).contains("private-provider-failure"));
	}

	private void respond(HttpExchange exchange) throws IOException {
		byte[] requestBytes = exchange.getRequestBody().readAllBytes();
		JsonNode requestBody = requestBytes.length == 0
				? objectMapper.createObjectNode() : objectMapper.readTree(requestBytes);
		requests.add(new ReceivedRequest(exchange.getRequestMethod(), exchange.getRequestURI().toString(), requestBody));
		String path = exchange.getRequestURI().getPath();
		int status = 200;
		Object body;
		if (path.equals("/health")) {
			body = Map.of("state", "ready");
		}
		else if (path.equals("/v1/capabilities")) {
			body = Map.of(
					"runtimeVersion", "0.148.0",
					"model", "gpt-5.6-sol",
					"reasoningEfforts", List.of("low", "high"),
					"enabledFeatures", List.of("shell_tool"));
		}
		else if (path.equals("/v1/account")) {
			body = Map.of(
					"authentication", "chatgpt",
					"requiresAuthentication", false,
					"plan", "pro",
					"usedPercent", 5,
					"resetsAt", 2_000_000_000);
		}
		else if (path.equals("/v1/tasks")) {
			status = 201;
			body = Map.of("engineRef", "private-task-ref", "model", "gpt-5.6-sol");
		}
		else if (path.endsWith("/turns") && path.contains("busy")) {
			status = 409;
			body = Map.of("error", "ENGINE_BUSY", "detail", "private-provider-failure");
		}
		else if (path.endsWith("/turns")) {
			status = 202;
			body = Map.of("operationId", "private-operation-ref");
		}
		else if (path.endsWith("/events")) {
			if (path.contains("usage-operation-ref")) {
				body = Map.of(
						"terminal", true,
						"events", List.of(Map.of(
								"sequence", 0,
								"kind", "turn_completed",
								"payload", Map.of("status", "usageLimited"),
								"terminal", true)));
			}
			else {
				body = Map.of(
						"terminal", true,
						"events", List.of(
								Map.of(
										"sequence", 0,
										"kind", "message_delta",
										"payload", Map.of("text", " \n", "truncated", false),
										"terminal", false),
								Map.of(
										"sequence", 1,
										"kind", "turn_completed",
										"payload", Map.of("status", "completed"),
										"terminal", true)));
			}
		}
		else if (path.endsWith("/interactions")) {
			body = Map.of("interactions", List.of(
					Map.of(
							"interactionId", "private-interaction-ref",
							"workspaceId", "pilot",
							"kind", "command",
							"category", "shell command",
							"reason", "Run focused tests",
							"availableDecisions", List.of("accept", "decline"),
							"detail", Map.of("command", "python3 -m unittest"),
							"expiresAt", 2_000_000_000.0),
					Map.of(
							"interactionId", "private-mcp-interaction-ref",
							"workspaceId", "pilot",
							"kind", "mcp_elicitation",
							"category", "MCP request",
							"reason", "Confirm harmless marker",
							"availableDecisions", List.of("accept", "decline"),
							"detail", Map.of(
									"mode", "form",
									"fields", List.of(Map.of(
											"name", "confirm",
											"label", "Confirm",
											"type", "boolean",
											"required", true,
											"options", List.of(),
											"maxLength", 0))),
							"expiresAt", 2_000_000_000.0)));
		}
		else if (path.endsWith("/decisions")) {
			status = 204;
			body = Map.of();
		}
		else if (path.endsWith("/goal") && exchange.getRequestMethod().equals("POST")) {
			status = 204;
			body = Map.of();
		}
		else {
			status = 404;
			body = Map.of("error", "NOT_FOUND");
		}
		byte[] response = status == 204
				? new byte[0]
				: objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json");
		exchange.sendResponseHeaders(status, response.length);
		if (response.length > 0) {
			exchange.getResponseBody().write(response);
		}
		exchange.close();
	}

	private static String allMessages(Throwable failure) {
		StringBuilder result = new StringBuilder();
		for (Throwable current = failure; current != null; current = current.getCause()) {
			if (current.getMessage() != null) {
				result.append(current.getMessage());
			}
		}
		return result.toString();
	}

	private record ReceivedRequest(String method, String path, JsonNode body) {
	}
}
