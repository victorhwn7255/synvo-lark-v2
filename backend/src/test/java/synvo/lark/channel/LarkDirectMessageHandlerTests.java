package synvo.lark.channel;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import synvo.agent.AgentIntent;
import synvo.agent.AgentLifecycleEvent;
import synvo.agent.AgentLifecycleEvent.ActionHandoff;
import synvo.agent.ConversationQueries;
import synvo.agent.ConversationRequest;
import synvo.agent.ConversationResult;
import synvo.agent.ConversationRunCoordinator;
import synvo.configuration.LarkProperties;
import synvo.persistence.LarkConversationBindingRepository;
import synvo.persistence.LarkMessageProcessingRepository;
import synvo.workspaceagent.WorkspaceAgentEngine.RunMode;
import synvo.workspaceagent.WorkspaceConversationAgent;
import synvo.workspaceagent.WorkspaceConversationAgent.ConversationTaskHandoff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LarkDirectMessageHandlerTests {

	private LarkMessageProcessingRepository repository;
	private LarkConversationBindingRepository conversationBindings;
	private LarkChannelClient channelClient;
	private ConversationRunCoordinator conversations;
	private ConversationQueries conversationQueries;
	private WorkspaceConversationAgent workspaceConversations;
	private LarkDirectMessageHandler handler;

	@BeforeEach
	void setUp() {
		repository = mock(LarkMessageProcessingRepository.class);
		conversationBindings = mock(LarkConversationBindingRepository.class);
		channelClient = mock(LarkChannelClient.class);
		conversations = mock(ConversationRunCoordinator.class);
		conversationQueries = mock(ConversationQueries.class);
		workspaceConversations = mock(WorkspaceConversationAgent.class);
		handler = new LarkDirectMessageHandler(
				properties(), repository, conversationBindings, channelClient,
				conversations, conversationQueries, workspaceConversations);
		handler.setBotOpenId("ou-bot");
	}

	@Test
	void completedNativeTaskShowsOneOwningH5ContinuationWithoutInventingAnInteraction() {
		InboundLarkMessage message = message(
				"m-task-handoff", "p2p", "ou-victor", "text", "Create one report");
		UUID conversationId = UUID.randomUUID();
		UUID runId = UUID.randomUUID();
		UUID taskId = UUID.randomUUID();
		FakeStreamWriter writer = new FakeStreamWriter();
		when(repository.tryClaim(eq("m-task-handoff"), eq("ou-victor"), eq("p2p"), any()))
				.thenReturn(true);
		when(conversationBindings.findConversationId("chat-1", "ou-victor"))
				.thenReturn(Optional.empty());
		when(channelClient.stream(eq(message), any())).thenAnswer(invocation -> {
			LarkChannelClient.StreamProducer producer = invocation.getArgument(1);
			producer.produce(writer);
			return CompletableFuture.completedFuture("reply-task-handoff");
		});
		when(conversations.run(any(), any(), any())).thenAnswer(invocation -> {
			Consumer<AgentLifecycleEvent> listener = invocation.getArgument(2);
			listener.accept(AgentLifecycleEvent.contentDelta(
					1, "No file was changed. Open this task in H5 and select Full Edit."));
			listener.accept(new AgentLifecycleEvent(2, AgentLifecycleEvent.State.COMPLETED, null));
			return result(
					conversationId, runId, ConversationResult.Status.COMPLETED,
					"No file was changed. Open this task in H5 and select Full Edit.");
		});
		when(workspaceConversations.conversationTask("ou-victor", conversationId))
				.thenReturn(Optional.of(new ConversationTaskHandoff(
						taskId, "Products", RunMode.READ_ONLY)));

		handler.handle(message);

		assertEquals("Products", writer.shownTask.workspaceName());
		assertEquals("Read Only", writer.shownTask.accessMode());
		org.junit.jupiter.api.Assertions.assertTrue(
				writer.shownTaskUrl.startsWith(
						"https://applink.larksuite.com/client/web_app/open?"));
		assertEquals("cli-test", appLinkParameter(writer.shownTaskUrl, "appId"));
		assertEquals("appCenter", appLinkParameter(writer.shownTaskUrl, "mode"));
		assertEquals("true", appLinkParameter(writer.shownTaskUrl, "reload"));
		assertEquals("/h5", appLinkParameter(writer.shownTaskUrl, "path"));
		assertEquals(taskId.toString(), appLinkParameter(writer.shownTaskUrl, "codexTask"));
		assertEquals(null, appLinkParameter(writer.shownTaskUrl, "codexInteraction"));
		assertEquals(null, writer.shownAction);
	}

	@Test
	void acceptedVictorTextStreamsOneAgentResponseAndBindsConversation() {
		InboundLarkMessage message = message("m-1", "p2p", "ou-victor", "text", "hello");
		UUID conversationId = UUID.randomUUID();
		UUID runId = UUID.randomUUID();
		FakeStreamWriter writer = new FakeStreamWriter();
		when(repository.tryClaim(eq("m-1"), eq("ou-victor"), eq("p2p"), any())).thenReturn(true);
		when(conversationBindings.findConversationId("chat-1", "ou-victor"))
				.thenReturn(Optional.empty());
		when(channelClient.stream(eq(message), any())).thenAnswer(invocation -> {
			LarkChannelClient.StreamProducer producer = invocation.getArgument(1);
			producer.produce(writer);
			return CompletableFuture.completedFuture("reply-1");
		});
		when(conversations.run(any(), any(), any())).thenAnswer(invocation -> {
			Consumer<ConversationRunCoordinator.Submission> onSubmission =
					invocation.getArgument(1);
			Consumer<AgentLifecycleEvent> listener = invocation.getArgument(2);
			onSubmission.accept(submission(conversationId, runId));
			listener.accept(new AgentLifecycleEvent(1, AgentLifecycleEvent.State.ACCEPTED, "Request accepted"));
			listener.accept(new AgentLifecycleEvent(2, AgentLifecycleEvent.State.STREAMING, "Writing a response"));
			listener.accept(AgentLifecycleEvent.contentDelta(3, "Discarded partial response"));
			listener.accept(AgentLifecycleEvent.contentReset(4));
			listener.accept(AgentLifecycleEvent.contentDelta(5, "Hello Victor."));
			listener.accept(new AgentLifecycleEvent(6, AgentLifecycleEvent.State.COMPLETED, null));
			return result(conversationId, runId, ConversationResult.Status.COMPLETED, "Hello Victor.");
		});

		handler.handle(message);

		ArgumentCaptor<ConversationRequest> request = ArgumentCaptor.forClass(ConversationRequest.class);
		verify(conversations).run(request.capture(), any(), any());
		assertEquals("m-1", request.getValue().requestId());
		assertEquals("hello", request.getValue().content());
		assertEquals("Hello Victor.", writer.content.toString());
		verify(conversationBindings).bind("chat-1", "ou-victor", conversationId);
		verify(repository).markReplied("m-1", "reply-1");
	}

	@Test
	void failedAgentRunReplacesPartialLarkTextWithOneSafeFinalState() {
		InboundLarkMessage message = message("m-failure", "p2p", "ou-victor", "text", "hello");
		FakeStreamWriter writer = new FakeStreamWriter();
		when(repository.tryClaim(eq("m-failure"), eq("ou-victor"), eq("p2p"), any()))
				.thenReturn(true);
		when(conversationBindings.findConversationId("chat-1", "ou-victor"))
				.thenReturn(Optional.empty());
		when(channelClient.stream(eq(message), any())).thenAnswer(invocation -> {
			LarkChannelClient.StreamProducer producer = invocation.getArgument(1);
			producer.produce(writer);
			return CompletableFuture.completedFuture("reply-failure");
		});
		when(conversations.run(any(), any(), any())).thenAnswer(invocation -> {
			Consumer<AgentLifecycleEvent> listener = invocation.getArgument(2);
			listener.accept(AgentLifecycleEvent.contentDelta(1, "Partial private output"));
			listener.accept(new AgentLifecycleEvent(
					2, AgentLifecycleEvent.State.FAILED, "I couldn’t complete that response. Please try again."));
			return result(
					UUID.randomUUID(), UUID.randomUUID(), ConversationResult.Status.FAILED,
					"I couldn’t complete that response. Please try again.");
		});

		handler.handle(message);

		assertEquals("I couldn’t complete that response. Please try again.", writer.content.toString());
		verify(repository).markReplied("m-failure", "reply-failure");
	}

	@Test
	void nativeInteractionProducesOneSafeOwningH5Handoff() {
		InboundLarkMessage message = message(
				"m-interaction", "p2p", "ou-victor", "text", "Update the workspace");
		FakeStreamWriter writer = new FakeStreamWriter();
		UUID taskId = UUID.randomUUID();
		UUID interactionId = UUID.randomUUID();
		when(repository.tryClaim(eq("m-interaction"), eq("ou-victor"), eq("p2p"), any()))
				.thenReturn(true);
		when(conversationBindings.findConversationId("chat-1", "ou-victor"))
				.thenReturn(Optional.empty());
		when(channelClient.stream(eq(message), any())).thenAnswer(invocation -> {
			LarkChannelClient.StreamProducer producer = invocation.getArgument(1);
			producer.produce(writer);
			return CompletableFuture.completedFuture("reply-interaction");
		});
		when(conversations.run(any(), any(), any())).thenAnswer(invocation -> {
			Consumer<AgentLifecycleEvent> listener = invocation.getArgument(2);
			listener.accept(AgentLifecycleEvent.actionRequired(
					1, new ActionHandoff(
							taskId, interactionId, "file change", "Pilot workspace",
							"Update one file", "workspace files")));
			listener.accept(AgentLifecycleEvent.contentDelta(2, "Change completed."));
			listener.accept(new AgentLifecycleEvent(
					3, AgentLifecycleEvent.State.COMPLETED, null));
			return result(
					UUID.randomUUID(), UUID.randomUUID(),
					ConversationResult.Status.COMPLETED, "Change completed.");
		});

		handler.handle(message);

		assertEquals(taskId, writer.shownAction.taskId());
		assertEquals(interactionId, writer.shownAction.interactionId());
		org.junit.jupiter.api.Assertions.assertTrue(
				writer.shownUrl.startsWith(
						"https://applink.larksuite.com/client/web_app/open?"));
		assertEquals("cli-test", appLinkParameter(writer.shownUrl, "appId"));
		assertEquals("appCenter", appLinkParameter(writer.shownUrl, "mode"));
		assertEquals("true", appLinkParameter(writer.shownUrl, "reload"));
		assertEquals("/h5", appLinkParameter(writer.shownUrl, "path"));
		assertEquals(taskId.toString(), appLinkParameter(writer.shownUrl, "codexTask"));
		assertEquals(
				interactionId.toString(), appLinkParameter(writer.shownUrl, "codexInteraction"));
		assertEquals("Change completed.", writer.content.toString());
		org.junit.jupiter.api.Assertions.assertTrue(writer.clearCount > 0);
	}

	@Test
	void failedLarkStreamFallsBackToOneSafeNormalMessage() {
		InboundLarkMessage message = message("m-stream-failure", "p2p", "ou-victor", "text", "hello");
		when(repository.tryClaim(eq("m-stream-failure"), eq("ou-victor"), eq("p2p"), any()))
				.thenReturn(true);
		when(conversationBindings.findConversationId("chat-1", "ou-victor"))
				.thenReturn(Optional.empty());
		when(channelClient.stream(eq(message), any()))
				.thenReturn(CompletableFuture.failedFuture(new IllegalStateException("stream unavailable")));
		when(channelClient.respond(message, LarkDirectMessageHandler.DELIVERY_FAILURE_REPLY))
				.thenReturn(CompletableFuture.completedFuture("fallback-reply"));

		handler.handle(message);

		verify(conversations, never()).run(any(), any(), any());
		verify(channelClient).respond(message, LarkDirectMessageHandler.DELIVERY_FAILURE_REPLY);
		verify(repository).markReplied("m-stream-failure", "fallback-reply");
		verify(repository, never()).markFailed(any(), any());
	}

	@Test
	void failedLarkStreamAndFallbackMarkTheMessageFailed() {
		InboundLarkMessage message = message("m-total-failure", "p2p", "ou-victor", "text", "hello");
		when(repository.tryClaim(eq("m-total-failure"), eq("ou-victor"), eq("p2p"), any()))
				.thenReturn(true);
		when(conversationBindings.findConversationId("chat-1", "ou-victor"))
				.thenReturn(Optional.empty());
		when(channelClient.stream(eq(message), any()))
				.thenReturn(CompletableFuture.failedFuture(new IllegalStateException("stream unavailable")));
		when(channelClient.respond(message, LarkDirectMessageHandler.DELIVERY_FAILURE_REPLY))
				.thenReturn(CompletableFuture.failedFuture(new CompletionException(
						new IllegalStateException("fallback unavailable"))));

		handler.handle(message);

		verify(repository).markFailed("m-total-failure", "LARK_REPLY_FAILED");
	}

	@Test
	void rejectsOtherUsersGroupsAndBotMessagesWithoutClaimingOrReplying() {
		handler.handle(message("m-other", "p2p", "ou-other", "text", "hello"));
		handler.handle(message("m-group", "group", "ou-victor", "text", "hello"));
		handler.setBotOpenId("ou-victor");
		handler.handle(message("m-bot", "p2p", "ou-victor", "text", "hello"));

		verify(repository, never()).tryClaim(any(), any(), any(), any());
		verify(channelClient, never()).respond(any(), any());
		verify(channelClient, never()).stream(any(), any());
	}

	@Test
	void sendsSafeUnsupportedReplyForVictorMedia() {
		when(repository.tryClaim(eq("m-image"), eq("ou-victor"), eq("p2p"), any())).thenReturn(true);
		InboundLarkMessage message = message("m-image", "p2p", "ou-victor", "image", null);
		when(channelClient.respond(message, LarkDirectMessageHandler.UNSUPPORTED_REPLY))
				.thenReturn(CompletableFuture.completedFuture("reply-image"));

		handler.handle(message);

		verify(channelClient).respond(message, LarkDirectMessageHandler.UNSUPPORTED_REPLY);
		verify(channelClient, never()).stream(any(), any());
		verify(repository).markReplied("m-image", "reply-image");
	}

	@Test
	void duplicateMessageDoesNotCreateAnotherAgentRunOrResponse() {
		InboundLarkMessage duplicate = message("m-duplicate", "p2p", "ou-victor", "text", "hello");
		when(repository.tryClaim(eq("m-duplicate"), eq("ou-victor"), eq("p2p"), any()))
				.thenReturn(false);

		handler.handle(duplicate);
		handler.handle(duplicate);

		verify(channelClient, never()).respond(any(), any());
		verify(channelClient, never()).stream(any(), any());
		verify(conversations, never()).run(any(), any(), any());
	}

	@Test
	void nativeStopCommandCancelsTheBoundActiveRunWithoutStartingAnotherTurn() {
		InboundLarkMessage message = message(
				"m-stop", "p2p", "ou-victor", "text", " /STOP ");
		UUID conversationId = UUID.randomUUID();
		UUID runId = UUID.randomUUID();
		when(repository.tryClaim(eq("m-stop"), eq("ou-victor"), eq("p2p"), any()))
				.thenReturn(true);
		when(conversationBindings.findConversationId("chat-1", "ou-victor"))
				.thenReturn(Optional.of(conversationId));
		when(conversationQueries.findConversation("ou-victor", conversationId))
				.thenReturn(Optional.of(new ConversationQueries.ConversationDetail(
						conversationId,
						"Active task",
						Instant.now(),
						List.of(),
						new ConversationQueries.RunDescriptor(
								runId,
								"request-active",
								conversationId,
								UUID.randomUUID(),
								UUID.randomUUID(),
								AgentIntent.DIRECT_ANSWER,
								ConversationQueries.RunStatus.RUNNING))));
		when(conversations.stop(runId)).thenReturn(true);
		when(channelClient.respond(message, LarkDirectMessageHandler.STOP_REQUESTED_REPLY))
				.thenReturn(CompletableFuture.completedFuture("reply-stop"));

		handler.handle(message);

		verify(conversations).stop(runId);
		verify(conversations, never()).run(any(), any(), any());
		verify(channelClient).respond(message, LarkDirectMessageHandler.STOP_REQUESTED_REPLY);
		verify(repository).markReplied("m-stop", "reply-stop");
	}

	private static ConversationResult result(
			UUID conversationId,
			UUID runId,
			ConversationResult.Status status,
			String response) {
		return new ConversationResult(
				conversationId,
				runId,
				AgentIntent.DIRECT_ANSWER,
				status == ConversationResult.Status.COMPLETED
						? ConversationResult.Outcome.DIRECT_ANSWER
						: ConversationResult.Outcome.FAILED,
				status,
				response,
				List.of(),
				false);
	}

	private static ConversationRunCoordinator.Submission submission(
			UUID conversationId,
			UUID runId) {
		return new ConversationRunCoordinator.Submission(
				"request", conversationId, runId, UUID.randomUUID(), UUID.randomUUID(),
				AgentIntent.DIRECT_ANSWER, "RUNNING", false);
	}

	private static InboundLarkMessage message(
			String id, String chatType, String sender, String contentType, String content) {
		return new InboundLarkMessage(
				id, "chat-1", chatType, sender, content, contentType,
				null, null, null, Instant.now());
	}

	private static String appLinkParameter(String appLink, String name) {
		String rawQuery = URI.create(appLink).getRawQuery();
		for (String parameter : rawQuery.split("&")) {
			int separator = parameter.indexOf('=');
			if (separator > 0 && name.equals(parameter.substring(0, separator))) {
				return URLDecoder.decode(
						parameter.substring(separator + 1), StandardCharsets.UTF_8);
			}
		}
		return null;
	}

	private static LarkProperties properties() {
		return new LarkProperties(
				true,
				"cli-test",
				"secret-test",
				"websocket",
				"ou-victor",
				"https://synvo.example/h5",
				Base64.getEncoder().encodeToString(new byte[32]),
				Duration.ofMinutes(5),
				Duration.ofDays(30));
	}

	private static final class FakeStreamWriter implements LarkChannelClient.StreamWriter {

		private final StringBuilder content = new StringBuilder();
		private ActionHandoff shownAction;
		private String shownUrl;
		private int clearCount;
		private LarkChannelClient.TaskHandoff shownTask;
		private String shownTaskUrl;

		@Override
		public void append(String delta) {
			content.append(delta);
		}

		@Override
		public void setContent(String replacement) {
			content.setLength(0);
			content.append(replacement);
		}

		@Override
		public void showActionRequired(ActionHandoff handoff, String h5Url) {
			shownAction = handoff;
			shownUrl = h5Url;
		}

		@Override
		public void clearActionRequired() {
			clearCount++;
		}

		@Override
		public void showTaskHandoff(LarkChannelClient.TaskHandoff handoff, String h5Url) {
			shownTask = handoff;
			shownTaskUrl = h5Url;
		}
	}
}
