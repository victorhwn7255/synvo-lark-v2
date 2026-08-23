package synvo.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import synvo.workspaceagent.WorkspaceAgentEngine.ActivityKind;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDecision;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionKind;
import synvo.workspaceagent.WorkspaceAgentEngine.GoalCommand;
import synvo.workspaceagent.WorkspaceAgentEngine.RunMode;
import synvo.workspaceagent.WorkspaceAgentEngine.TerminalStatus;
import synvo.workspaceagent.WorkspaceAgentException;
import synvo.workspaceagent.WorkspaceAgentFacade;
import synvo.workspaceagent.WorkspaceAgentFacade.ActivityView;
import synvo.workspaceagent.WorkspaceAgentFacade.InteractionDetailView;
import synvo.workspaceagent.WorkspaceAgentFacade.InteractionView;
import synvo.workspaceagent.WorkspaceAgentFacade.OperationView;
import synvo.workspaceagent.WorkspaceAgentFacade.TaskDetail;
import synvo.workspaceagent.WorkspaceAgentFacade.TaskView;
import synvo.workspaceagent.WorkspaceAgentRepository.OperationStatus;
import synvo.workspaceagent.WorkspaceAgentRepository.OperationType;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class WorkspaceAgentControllerTests {

	private final UUID taskId = UUID.randomUUID();
	private final UUID conversationId = UUID.randomUUID();
	private final UUID operationId = UUID.randomUUID();
	private final UUID interactionId = UUID.randomUUID();
	private LarkSessionAccess sessionAccess;
	private WorkspaceAgentFacade facade;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		sessionAccess = mock(LarkSessionAccess.class);
		facade = mock(WorkspaceAgentFacade.class);
		doReturn(new LarkSessionAccess.AuthorizedUser("ou-victor", "Victor", null))
				.when(sessionAccess).require(any());
		WorkspaceAgentEventStream eventStream = new WorkspaceAgentEventStream();
		mvc = standaloneSetup(new WorkspaceAgentController(
				sessionAccess, facade, eventStream)).build();
	}

	@Test
	void taskShellUsesOnlyTheAuthorizedOwnerAndConfiguredWorkspaceId() throws Exception {
		TaskView task = task();
		when(facade.createTask("ou-victor", "pilot", RunMode.READ_ONLY, "Audit"))
				.thenReturn(task);
		when(facade.task("ou-victor", taskId)).thenReturn(new TaskDetail(
				task, operation(), operation(), List.of()));

		mvc.perform(post("/api/codex/tasks")
					.session(new MockHttpSession())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"workspaceId":"pilot","mode":"READ_ONLY","title":"Audit"}
							"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.taskId").value(taskId.toString()))
				.andExpect(jsonPath("$.workspaceId").value("pilot"))
				.andExpect(jsonPath("$.workspaceName").value("Pilot workspace"));

		mvc.perform(get("/api/codex/tasks/{taskId}", taskId)
					.session(new MockHttpSession()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.latestOperation.operationId")
						.value(operationId.toString()));
		verify(facade).createTask("ou-victor", "pilot", RunMode.READ_ONLY, "Audit");
	}

	@Test
	void activitySsePreservesWhitespaceAndCompletesOnTheExactTerminalStatus() throws Exception {
		when(facade.activity("ou-victor", operationId, -1)).thenReturn(List.of(
				new ActivityView(
						0, ActivityKind.MESSAGE_DELTA, "Writing the result", "\n", false, null),
				new ActivityView(
						1, ActivityKind.TURN_COMPLETED, "Codex task finished", null,
						false, TerminalStatus.STOPPED)));

		var initial = mvc.perform(get("/api/codex/operations/{operationId}/events", operationId)
					.session(new MockHttpSession())
					.accept(MediaType.TEXT_EVENT_STREAM))
				.andExpect(request().asyncStarted())
				.andExpect(header().string("Cache-Control", "no-store"))
				.andReturn();

		mvc.perform(asyncDispatch(initial))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("id:0")))
				.andExpect(content().string(containsString("event:message_delta")))
				.andExpect(content().string(containsString("\\n")))
				.andExpect(content().string(containsString("STOPPED")));
	}

	@Test
	void interactionDetailAndDecisionAreOwnerBoundNoStoreAndBounded() throws Exception {
		InteractionView interaction = interaction();
		when(facade.interactionDetail("ou-victor", interactionId)).thenReturn(interaction);
		when(facade.decideInteraction(
				"ou-victor", interactionId, InteractionDecision.APPROVE_ONCE,
				Map.of("confirmation", "yes"))).thenReturn(interaction);

		mvc.perform(get("/api/codex/interactions/{interactionId}", interactionId)
					.session(new MockHttpSession()))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")))
				.andExpect(jsonPath("$.category").value("shell command"))
				.andExpect(jsonPath("$.detail.command").value("./mvnw test"))
				.andExpect(jsonPath("$.detail.workingDirectory").value("backend"));

		mvc.perform(post("/api/codex/interactions/{interactionId}/decision", interactionId)
					.session(new MockHttpSession())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "decision":"APPROVE_ONCE",
							  "formValues":{"confirmation":"yes"}
							}
							"""))
				.andExpect(status().isOk())
				.andExpect(header().string("Cache-Control", containsString("no-store")));

		ArgumentCaptor<Map<String, String>> values = ArgumentCaptor.forClass(Map.class);
		verify(facade).decideInteraction(
				org.mockito.ArgumentMatchers.eq("ou-victor"),
				org.mockito.ArgumentMatchers.eq(interactionId),
				org.mockito.ArgumentMatchers.eq(InteractionDecision.APPROVE_ONCE),
				values.capture());
		assertEquals(Map.of("confirmation", "yes"), values.getValue());
	}

	@Test
	void goalLifecycleUsesExplicitCommandsAndDefaultsPlainSaves() throws Exception {
		mvc.perform(put("/api/codex/tasks/{taskId}/goal", taskId)
					.session(new MockHttpSession())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"objective":"Maintain verified reports","command":"RESUME"}
							"""))
				.andExpect(status().isNoContent());
		verify(facade).setGoal(
				"ou-victor", taskId, "Maintain verified reports", GoalCommand.RESUME);

		mvc.perform(put("/api/codex/tasks/{taskId}/goal", taskId)
					.session(new MockHttpSession())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"objective":"Maintain verified reports"}
							"""))
				.andExpect(status().isNoContent());
		verify(facade).setGoal(
				"ou-victor", taskId, "Maintain verified reports", GoalCommand.SAVE);

		mvc.perform(put("/api/codex/tasks/{taskId}/goal", taskId)
					.session(new MockHttpSession())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"objective":"Maintain verified reports","command":"BLOCK"}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CODEX_REQUEST"));
	}

	@Test
	void unauthorizedBusyAndMalformedRequestsHaveSafeDeterministicErrors() throws Exception {
		when(sessionAccess.require(any()))
				.thenThrow(new LarkSessionAccess.UnauthorizedSessionException());
		mvc.perform(get("/api/codex/tasks"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("LARK_AUTHORIZATION_REQUIRED"));
		verify(facade, never()).listTasks(any(), any(Boolean.class), any());

		doReturn(new LarkSessionAccess.AuthorizedUser("ou-victor", "Victor", null))
				.when(sessionAccess).require(any());
		when(facade.createTask(any(), any(), any(), any()))
				.thenThrow(new WorkspaceAgentException(WorkspaceAgentException.Code.BUSY));
		mvc.perform(post("/api/codex/tasks")
					.session(new MockHttpSession())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{"workspaceId":"pilot","mode":"READ_ONLY"}
							"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CODEX_BUSY"));

		mvc.perform(get("/api/codex/operations/{operationId}/events", operationId)
					.session(new MockHttpSession())
					.header("Last-Event-ID", "private-reasoning"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CODEX_REQUEST"));
	}

	private TaskView task() {
		Instant now = Instant.parse("2026-08-21T01:00:00Z");
		return new TaskView(
				taskId, conversationId, "Audit", "pilot", "Pilot workspace",
				RunMode.READ_ONLY, false, false, now, now);
	}

	private OperationView operation() {
		Instant now = Instant.parse("2026-08-21T01:00:00Z");
		return new OperationView(
				operationId, taskId, OperationType.TURN, OperationStatus.RUNNING, now, now);
	}

	private InteractionView interaction() {
		return new InteractionView(
				interactionId,
				taskId,
				operationId,
				"pilot",
				"Pilot workspace",
				InteractionKind.COMMAND_APPROVAL,
				"shell command",
				"Run focused tests",
				"workspace command",
				List.of(InteractionDecision.APPROVE_ONCE, InteractionDecision.DECLINE),
				"PENDING",
				null,
				Instant.parse("2026-08-21T01:05:00Z"),
				new InteractionDetailView(
						"./mvnw test", "backend", List.of(), null, null, null, null,
						null,
						List.of()));
	}
}
