package synvo.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import synvo.agent.AgentIntent;
import synvo.agent.AgentLifecycleEvent;
import synvo.agent.ConversationQueries;
import synvo.agent.ConversationQueries.ConversationDetail;
import synvo.agent.ConversationQueries.ConversationSummary;
import synvo.agent.ConversationQueries.DeleteResult;
import synvo.agent.ConversationQueries.RunDescriptor;
import synvo.agent.ConversationQueries.RunStatus;
import synvo.agent.ConversationRequest;
import synvo.agent.ConversationRunCoordinator;
import synvo.agent.ConversationStore;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

class ConversationControllerTests {

	private final UUID conversationId = UUID.randomUUID();
	private final UUID runId = UUID.randomUUID();
	private LarkSessionAccess sessionAccess;
	private ConversationRunCoordinator coordinator;
	private ConversationQueries queries;
	private ConversationStore store;
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		sessionAccess = mock(LarkSessionAccess.class);
		coordinator = mock(ConversationRunCoordinator.class);
		queries = mock(ConversationQueries.class);
		store = mock(ConversationStore.class);
		doReturn(new LarkSessionAccess.AuthorizedUser("ou-victor", "Victor", null))
				.when(sessionAccess).require(any());
		ConversationEventStream eventStream = new ConversationEventStream(store);
		mvc = standaloneSetup(new ConversationController(
				sessionAccess, coordinator, eventStream, queries)).build();
	}

	@Test
	void listReturnsOnlyTheAuthorizedOwnersPersistedConversations() throws Exception {
		when(queries.listRecent("ou-victor")).thenReturn(List.of(
				new ConversationSummary(conversationId, "Persisted title", Instant.parse("2026-08-18T01:00:00Z"))));

		mvc.perform(get("/api/conversations").session(new MockHttpSession()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].conversationId").value(conversationId.toString()))
				.andExpect(jsonPath("$[0].title").value("Persisted title"));

		verify(queries).listRecent("ou-victor");
	}

	@Test
	void detailExposesOnlyTheOwningActiveRunNeededForRefreshReconnect() throws Exception {
		when(queries.findConversation("ou-victor", conversationId)).thenReturn(Optional.of(
				new ConversationDetail(
						conversationId,
						"Persisted title",
						Instant.parse("2026-08-18T01:00:00Z"),
						List.of(),
						ownedRun())));

		mvc.perform(get("/api/conversations/{conversationId}", conversationId)
					.session(new MockHttpSession()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.activeRun.runId").value(runId.toString()))
				.andExpect(jsonPath("$.activeRun.assistantTurnId").isNotEmpty())
				.andExpect(jsonPath("$.activeRun.status").value("RUNNING"));

		verify(queries).findConversation("ou-victor", conversationId);
	}

	@Test
	void deleteIsOwnerScopedAndReturnsNoContentOnlyAfterPersistentDeletion() throws Exception {
		when(queries.deleteOwnedConversation("ou-victor", conversationId))
				.thenReturn(DeleteResult.DELETED);

		mvc.perform(delete("/api/conversations/{conversationId}", conversationId)
					.session(new MockHttpSession()))
				.andExpect(status().isNoContent())
				.andExpect(content().string(""));

		verify(queries).deleteOwnedConversation("ou-victor", conversationId);
	}

	@Test
	void deleteRejectsMissingOrActiveConversationsWithSafeErrors() throws Exception {
		UUID activeConversationId = UUID.randomUUID();
		when(queries.deleteOwnedConversation("ou-victor", conversationId))
				.thenReturn(DeleteResult.NOT_FOUND);
		when(queries.deleteOwnedConversation("ou-victor", activeConversationId))
				.thenReturn(DeleteResult.ACTIVE_RUN);

		mvc.perform(delete("/api/conversations/{conversationId}", conversationId)
					.session(new MockHttpSession()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("The conversation is unavailable."));

		mvc.perform(delete("/api/conversations/{conversationId}", activeConversationId)
					.session(new MockHttpSession()))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("CONVERSATION_ACTIVE"))
				.andExpect(jsonPath("$.message")
						.value("Stop the active response before deleting this chat."));
	}

	@Test
	void submissionUsesBackendSessionIdentityAndNeverAcceptsAnOwnerFromTheBrowser() throws Exception {
		ConversationRunCoordinator.Submission submission = new ConversationRunCoordinator.Submission(
				"request-1", conversationId, runId, UUID.randomUUID(), UUID.randomUUID(),
				AgentIntent.DIRECT_ANSWER, "RUNNING", false);
		when(coordinator.submit(any())).thenReturn(submission);

		mvc.perform(post("/api/conversations/turns")
					.session(new MockHttpSession())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "requestId":"request-1",
							  "conversationId":null,
							  "content":"Explain SSE",
							  "reasoningEffort":"high",
							  "skillName":"workspace-audit"
							}
							"""))
				.andExpect(status().isAccepted())
				.andExpect(jsonPath("$.runId").value(runId.toString()))
				.andExpect(jsonPath("$.status").value("RUNNING"));

		ArgumentCaptor<ConversationRequest> request = ArgumentCaptor.forClass(ConversationRequest.class);
		verify(coordinator).submit(request.capture());
		assertEquals("ou-victor", request.getValue().userOpenId());
		assertEquals("Explain SSE", request.getValue().content());
		assertEquals(null, request.getValue().replaceFailedAssistantTurnId());
		assertEquals("high", request.getValue().reasoningEffort());
		assertEquals("workspace-audit", request.getValue().skillName());
	}

	@Test
	void retrySubmissionCarriesOnlyTheExplicitFailedAssistantTurn() throws Exception {
		UUID failedAssistantTurnId = UUID.randomUUID();
		ConversationRunCoordinator.Submission submission = new ConversationRunCoordinator.Submission(
				"request-retry", conversationId, runId, UUID.randomUUID(), UUID.randomUUID(),
				AgentIntent.DIRECT_ANSWER, "RUNNING", false);
		when(coordinator.submit(any())).thenReturn(submission);

		mvc.perform(post("/api/conversations/turns")
					.session(new MockHttpSession())
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "requestId":"request-retry",
							  "conversationId":"%s",
							  "content":"Explain SSE",
							  "replaceFailedAssistantTurnId":"%s"
							}
							""".formatted(conversationId, failedAssistantTurnId)))
				.andExpect(status().isAccepted());

		ArgumentCaptor<ConversationRequest> request = ArgumentCaptor.forClass(ConversationRequest.class);
		verify(coordinator).submit(request.capture());
		assertEquals(failedAssistantTurnId, request.getValue().replaceFailedAssistantTurnId());
		assertEquals("ou-victor", request.getValue().userOpenId());
	}

	@Test
	void sseReconnectReplaysOnlyEventsAfterLastEventIdAndTerminates() throws Exception {
		when(queries.findOwnedRun("ou-victor", runId)).thenReturn(Optional.of(ownedRun()));
		when(store.loadEvents(runId, 2)).thenReturn(List.of(
				new AgentLifecycleEvent(3, AgentLifecycleEvent.State.STREAMING, "Writing a response"),
				AgentLifecycleEvent.contentDelta(4, "Hello"),
				AgentLifecycleEvent.contentReset(5),
				new AgentLifecycleEvent(6, AgentLifecycleEvent.State.COMPLETED, null)));

		var initial = mvc.perform(get("/api/conversations/runs/{runId}/events", runId)
					.session(new MockHttpSession())
					.header("Last-Event-ID", "2")
					.accept(MediaType.TEXT_EVENT_STREAM))
				.andExpect(request().asyncStarted())
				.andReturn();

		mvc.perform(asyncDispatch(initial))
				.andExpect(status().isOk())
				.andExpect(content().string(containsString("id:3")))
				.andExpect(content().string(containsString("event:content_delta")))
				.andExpect(content().string(containsString("Hello")))
				.andExpect(content().string(containsString("event:content_reset")))
				.andExpect(content().string(containsString("event:completed")));
		verify(store).loadEvents(runId, 2);
	}

	@Test
	void unauthorizedAndCrossOwnerRunsReturnSafeErrors() throws Exception {
		when(sessionAccess.require(any())).thenThrow(new LarkSessionAccess.UnauthorizedSessionException());

		mvc.perform(get("/api/conversations"))
				.andExpect(status().isUnauthorized())
				.andExpect(jsonPath("$.code").value("LARK_AUTHORIZATION_REQUIRED"));

		doReturn(new LarkSessionAccess.AuthorizedUser("ou-victor", "Victor", null))
				.when(sessionAccess).require(any());
		when(queries.findOwnedRun("ou-victor", runId)).thenReturn(Optional.empty());
		mvc.perform(post("/api/conversations/runs/{runId}/stop", runId)
					.session(new MockHttpSession()))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.message").value("The conversation is unavailable."));
		verify(coordinator, never()).stop(runId);
	}

	@Test
	void malformedReplayCursorAndTurnPayloadAreRejectedSafely() throws Exception {
		when(queries.findOwnedRun("ou-victor", runId)).thenReturn(Optional.of(ownedRun()));

		mvc.perform(get("/api/conversations/runs/{runId}/events", runId)
					.session(new MockHttpSession())
					.header("Last-Event-ID", "private-reasoning"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CONVERSATION_REQUEST"));

		mvc.perform(post("/api/conversations/turns")
					.session(new MockHttpSession())
					.contentType(MediaType.APPLICATION_JSON)
					.content("{\"requestId\":\"\",\"content\":\"\"}"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_CONVERSATION_REQUEST"))
				.andExpect(jsonPath("$.message").value("The conversation request is invalid."));
		verify(coordinator, never()).submit(any());
	}

	private RunDescriptor ownedRun() {
		return new RunDescriptor(
				runId, "request-1", conversationId, UUID.randomUUID(), UUID.randomUUID(),
				AgentIntent.DIRECT_ANSWER, RunStatus.RUNNING);
	}
}
