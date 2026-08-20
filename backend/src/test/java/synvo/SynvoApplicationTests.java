package synvo;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import synvo.agent.AgentIntent;
import synvo.agent.AgentLifecycleEvent;
import synvo.agent.ConversationContextMessage;
import synvo.agent.ConversationQueries;
import synvo.agent.ConversationQueries.ConversationDetail;
import synvo.agent.ConversationQueries.DeleteResult;
import synvo.agent.ConversationQueries.TurnStatus;
import synvo.agent.ConversationRequest;
import synvo.agent.ConversationResult;
import synvo.agent.ConversationStore;
import synvo.agent.IntentRouter;
import synvo.agent.SynvoAgentCore;
import synvo.agent.model.ModelGateway;
import synvo.agent.model.ModelGatewayException;
import synvo.lark.auth.AesGcmTokenCipher;
import synvo.lark.auth.TokenContext;
import synvo.persistence.LarkMessageProcessingRepository;
import synvo.persistence.LarkConversationBindingRepository;
import synvo.persistence.LarkUserConnection;
import synvo.persistence.LarkUserConnectionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class SynvoApplicationTests {

	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"csrfToken\\\":\\\"([^\\\"]+)\\\"");

	@LocalServerPort
	private int port;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private LarkMessageProcessingRepository messageRepository;

	@Autowired
	private LarkUserConnectionRepository connectionRepository;

	@Autowired
	private ConversationStore conversationStore;

	@Autowired
	private SynvoAgentCore agentCore;

	@Autowired
	private ConversationQueries conversationQueries;

	@Autowired
	private LarkConversationBindingRepository conversationBindings;

	@Test
	void applicationStartsAndConnectsToPostgres() {
		Integer result = jdbcTemplate.queryForObject("select 1", Integer.class);
		assertEquals(1, result);
		assertEquals("lark_message_processing", jdbcTemplate.queryForObject(
				"select to_regclass('public.lark_message_processing')::text", String.class));
		assertEquals("conversation", jdbcTemplate.queryForObject(
				"select to_regclass('public.conversation')::text", String.class));
		assertEquals("conversation_turn", jdbcTemplate.queryForObject(
				"select to_regclass('public.conversation_turn')::text", String.class));
		assertEquals("agent_run", jdbcTemplate.queryForObject(
				"select to_regclass('public.agent_run')::text", String.class));
		assertEquals("agent_run_event", jdbcTemplate.queryForObject(
				"select to_regclass('public.agent_run_event')::text", String.class));
		assertEquals("lark_chat_conversation", jdbcTemplate.queryForObject(
				"select to_regclass('public.lark_chat_conversation')::text", String.class));
	}

	@Test
	void streamingRunPersistsOrderedDeltasOneAssistantTurnAndRealHistory() {
		ModelGateway streamingModel = new ModelGateway() {
			@Override
			public ModelResponse generate(ModelRequest request) {
				return new ModelResponse("One \n\npersisted response.");
			}

			@Override
			public ModelResponse stream(
					ModelRequest request,
					Consumer<String> onDelta,
					ModelCancellation cancellation) {
				onDelta.accept("One ");
				onDelta.accept("\n\n");
				onDelta.accept("persisted ");
				onDelta.accept("response.");
				return new ModelResponse("One \n\npersisted response.");
			}
		};
		SynvoAgentCore streamingCore = new SynvoAgentCore(
				new IntentRouter(), conversationStore, streamingModel);

		ConversationResult result = streamingCore.converse(new ConversationRequest(
				"agent-integration-streaming", null, "ou-stream-owner", "Explain SSE clearly"));

		assertEquals(ConversationResult.Status.COMPLETED, result.status());
		assertEquals(8, conversationStore.loadEvents(result.runId(), 0).size());
		assertEquals(List.of("One ", "\n\n", "persisted ", "response."),
				conversationStore.loadEvents(result.runId(), 0).stream()
						.filter(event -> event.state() == AgentLifecycleEvent.State.CONTENT_DELTA)
						.map(AgentLifecycleEvent::contentDelta)
						.toList());
		ConversationDetail detail = conversationQueries
				.findConversation("ou-stream-owner", result.conversationId())
				.orElseThrow();
		assertEquals("Explain SSE clearly", detail.title());
		assertEquals(2, detail.turns().size());
		assertEquals("One \n\npersisted response.", detail.turns().getLast().content());
		assertEquals(TurnStatus.COMPLETED,
				detail.turns().getLast().status());
		assertTrue(conversationQueries.listRecent("ou-stream-owner").stream()
				.anyMatch(summary -> summary.conversationId().equals(result.conversationId())));
		assertTrue(conversationQueries.findConversation("ou-another-owner", result.conversationId()).isEmpty());
	}

	@Test
	void ownerScopedConversationDeletionRejectsActiveRunsAndCascadesAllConversationState() {
		String owner = "ou-delete-owner";
		ConversationStore.RunHandle run = conversationStore.start(
				new ConversationRequest("agent-delete-conversation", null, owner, "Delete this chat"),
				AgentIntent.DIRECT_ANSWER);
		conversationBindings.bind("chat-delete-conversation", owner, run.conversationId());

		assertEquals(DeleteResult.NOT_FOUND,
				conversationQueries.deleteOwnedConversation("ou-different-owner", run.conversationId()));
		assertEquals(DeleteResult.ACTIVE_RUN,
				conversationQueries.deleteOwnedConversation(owner, run.conversationId()));

		conversationStore.fail(
				run,
				"USER_CANCELLED",
				"Response stopped.",
				new AgentLifecycleEvent(1, AgentLifecycleEvent.State.FAILED, "Response stopped."));
		assertEquals(DeleteResult.DELETED,
				conversationQueries.deleteOwnedConversation(owner, run.conversationId()));

		assertEquals(0, jdbcTemplate.queryForObject(
				"select count(*) from conversation where conversation_id = ?",
				Integer.class,
				run.conversationId()));
		assertEquals(0, jdbcTemplate.queryForObject(
				"select count(*) from conversation_turn where conversation_id = ?",
				Integer.class,
				run.conversationId()));
		assertEquals(0, jdbcTemplate.queryForObject(
				"select count(*) from agent_run where conversation_id = ?",
				Integer.class,
				run.conversationId()));
		assertEquals(0, jdbcTemplate.queryForObject(
				"select count(*) from agent_run_event where run_id = ?",
				Integer.class,
				run.runId()));
		assertTrue(conversationBindings
				.findConversationId("chat-delete-conversation", owner)
				.isEmpty());
	}

	@Test
	void transientProviderFailureReplacesPersistedPartialContentBeforeRegeneration() {
		AtomicInteger streamAttempts = new AtomicInteger();
		AtomicInteger fallbackAttempts = new AtomicInteger();
		ModelGateway transientModel = new ModelGateway() {
			@Override
			public ModelResponse generate(ModelRequest request) {
				fallbackAttempts.incrementAndGet();
				return new ModelResponse("Recovered response.");
			}

			@Override
			public ModelResponse stream(
					ModelRequest request,
					Consumer<String> onDelta,
					ModelCancellation cancellation) {
				streamAttempts.incrementAndGet();
				onDelta.accept("Discarded partial response");
				throw new ModelGatewayException(ModelGatewayException.Code.PROVIDER_FAILURE);
			}
		};
		SynvoAgentCore transientCore = new SynvoAgentCore(
				new IntentRouter(), conversationStore, transientModel);

		ConversationResult result = transientCore.converse(new ConversationRequest(
				"agent-integration-provider-retry", null, "ou-provider-retry", "Explain OAuth"));

		assertEquals(ConversationResult.Status.COMPLETED, result.status());
		assertEquals(1, streamAttempts.get());
		assertEquals(1, fallbackAttempts.get());
		assertEquals(1, conversationStore.loadEvents(result.runId(), 0).stream()
				.filter(event -> event.state() == AgentLifecycleEvent.State.CONTENT_RESET)
				.count());
		ConversationDetail detail = conversationQueries
				.findConversation("ou-provider-retry", result.conversationId())
				.orElseThrow();
		assertEquals(2, detail.turns().size());
		assertEquals("Recovered response.", detail.turns().getLast().content());
		assertFalse(detail.turns().getLast().content().contains("Discarded"));
	}

	@Test
	void interruptedStreamingRunRecoversToOnePersistedSafeTerminalState() {
		ConversationStore.RunHandle run = conversationStore.start(
				new ConversationRequest(
						"agent-restart-recovery", null, "ou-recovery-owner", "Explain recovery"),
				AgentIntent.DIRECT_ANSWER);
		conversationStore.appendEvent(run.runId(), new AgentLifecycleEvent(
				1, AgentLifecycleEvent.State.ACCEPTED, "Request accepted"));
		conversationStore.appendContentDelta(
				run, AgentLifecycleEvent.contentDelta(2, "Partial content that must be replaced"));

		assertEquals(1, conversationStore.recoverInterruptedRuns(
				"The response was interrupted. Please try again."));

		ConversationResult recovered = conversationStore
				.findTerminalResult("agent-restart-recovery")
				.orElseThrow();
		assertEquals(ConversationResult.Status.FAILED, recovered.status());
		assertEquals("The response was interrupted. Please try again.", recovered.response());
		assertEquals(List.of(
				AgentLifecycleEvent.State.ACCEPTED,
				AgentLifecycleEvent.State.CONTENT_DELTA,
				AgentLifecycleEvent.State.FAILED),
				recovered.events().stream().map(AgentLifecycleEvent::state).toList());
	}

	@Test
	void retrySupersedesTheFailedVisiblePairButPreservesItsAuditRun() {
		String owner = "ou-retry-owner";
		String content = "help";
		ConversationStore.RunHandle failed = conversationStore.start(
				new ConversationRequest("agent-retry-failed", null, owner, content),
				AgentIntent.CLARIFICATION);
		conversationStore.fail(
				failed,
				"USER_CANCELLED",
				"Response stopped.",
				new AgentLifecycleEvent(
						1, AgentLifecycleEvent.State.FAILED, "Response stopped."));

		assertThrows(IllegalArgumentException.class, () -> agentCore.converse(
				new ConversationRequest(
						"agent-retry-invalid-content",
						failed.conversationId(),
						owner,
						"different content",
						failed.assistantTurnId())));

		ConversationResult replacement = agentCore.converse(new ConversationRequest(
				"agent-retry-replacement",
				failed.conversationId(),
				owner,
				content,
				failed.assistantTurnId()));

		assertEquals(ConversationResult.Status.COMPLETED, replacement.status());
		ConversationDetail visible = conversationQueries
				.findConversation(owner, failed.conversationId())
				.orElseThrow();
		assertEquals(2, visible.turns().size());
		assertEquals(content, visible.turns().getFirst().content());
		assertEquals(TurnStatus.COMPLETED,
				visible.turns().getLast().status());
		assertEquals(2, jdbcTemplate.queryForObject(
				"select count(*) from conversation_turn where conversation_id = ? and superseded",
				Integer.class,
				failed.conversationId()));
		assertEquals(4, jdbcTemplate.queryForObject(
				"select count(*) from conversation_turn where conversation_id = ?",
				Integer.class,
				failed.conversationId()));
		assertEquals(2, conversationStore.loadContext(
				failed.conversationId(), 12, 24_000).size());
		assertEquals(ConversationResult.Status.FAILED, conversationStore
				.findTerminalResult("agent-retry-failed")
				.orElseThrow()
				.status());
	}

	@Test
	void larkChatBindingPreservesOneConversationAcrossDirectMessages() {
		ConversationResult first = agentCore.converse(new ConversationRequest(
				"agent-lark-binding", null, "ou-lark-binding", "help"));

		conversationBindings.bind("chat-binding", "ou-lark-binding", first.conversationId());

		assertEquals(first.conversationId(), conversationBindings
				.findConversationId("chat-binding", "ou-lark-binding")
				.orElseThrow());
		assertTrue(conversationBindings
				.findConversationId("chat-binding", "ou-other")
				.isEmpty());
	}

	@Test
	void agentRunPersistsOneConversationTwoTurnsAndTerminalLifecycle() {
		ConversationRequest request = new ConversationRequest(
				"agent-integration-clarification", null, "ou-pilot", "help");

		ConversationResult first = agentCore.converse(request);
		ConversationResult replay = agentCore.converse(request);

		assertEquals(ConversationResult.Status.COMPLETED, first.status());
		assertEquals(ConversationResult.Outcome.CLARIFICATION, first.outcome());
		assertFalse(first.replayed());
		assertTrue(replay.replayed());
		assertEquals(first.runId(), replay.runId());
		assertEquals(1, jdbcTemplate.queryForObject(
				"select count(*) from conversation where conversation_id = ?",
				Integer.class,
				first.conversationId()));
		assertEquals(2, jdbcTemplate.queryForObject(
				"select count(*) from conversation_turn where conversation_id = ?",
				Integer.class,
				first.conversationId()));
		assertEquals(4, jdbcTemplate.queryForObject(
				"select count(*) from agent_run_event where run_id = ?",
				Integer.class,
				first.runId()));
	}

	@Test
	void conversationOwnershipAndContextBoundsAreEnforcedInPostgres() {
		ConversationResult first = agentCore.converse(new ConversationRequest(
				"agent-context-0", null, "ou-context-owner", "help"));
		UUID conversationId = first.conversationId();
		for (int index = 1; index < 8; index++) {
			agentCore.converse(new ConversationRequest(
					"agent-context-" + index,
					conversationId,
					"ou-context-owner",
					"help"));
		}

		List<ConversationContextMessage> bounded = conversationStore.loadContext(
				conversationId, 12, 24_000);

		assertEquals(12, bounded.size());
		assertThrows(IllegalArgumentException.class, () -> conversationStore.start(
				new ConversationRequest(
						"agent-context-other-user",
						conversationId,
						"ou-different-user",
						"help"),
				AgentIntent.CLARIFICATION));
	}

	@Test
	void characterBudgetDropsOlderContextInsteadOfSendingAnUnboundedPrompt() {
		String userContent = "u".repeat(8_000);
		String assistantContent = "a".repeat(8_000);
		ConversationStore.RunHandle run = conversationStore.start(
				new ConversationRequest(
						"agent-character-budget",
						null,
						"ou-character-budget",
						userContent),
				AgentIntent.DIRECT_ANSWER);
		conversationStore.appendEvent(run.runId(), new AgentLifecycleEvent(
				1, AgentLifecycleEvent.State.ACCEPTED, "Request accepted"));
		conversationStore.complete(
				run,
				ConversationResult.Outcome.DIRECT_ANSWER,
				assistantContent,
				new AgentLifecycleEvent(2, AgentLifecycleEvent.State.COMPLETED, null));

		List<ConversationContextMessage> bounded = conversationStore.loadContext(
				run.conversationId(), 12, 9_000);

		assertEquals(1, bounded.size());
		assertEquals(ConversationContextMessage.Role.ASSISTANT, bounded.getFirst().role());
		assertEquals(8_000, bounded.getFirst().content().length());
	}

	@Test
	void postgresEnforcesMessageDeduplication() {
		Instant receivedAt = Instant.now();
		assertTrue(messageRepository.tryClaim("message-integration", "ou-pilot", "p2p", receivedAt));
		assertFalse(messageRepository.tryClaim("message-integration", "ou-pilot", "p2p", receivedAt));
	}

	@Test
	void failedMessageRequiresOneExplicitRetryClaim() {
		String messageId = "message-explicit-retry";
		assertTrue(messageRepository.tryClaim(messageId, "ou-pilot", "p2p", Instant.now()));
		messageRepository.markFailed(messageId, "LARK_REPLY_FAILED");

		assertTrue(messageRepository.prepareFailedForExplicitRetry(messageId));
		assertFalse(messageRepository.prepareFailedForExplicitRetry(messageId));
		assertEquals(2, jdbcTemplate.queryForObject(
				"select attempt_count from lark_message_processing where message_id = ?",
				Integer.class,
				messageId));
	}

	@Test
	void postgresUpsertsOneEncryptedUserConnection() {
		LarkUserConnection first = connection("ciphertext-access-one", "ciphertext-refresh-one");
		LarkUserConnection second = connection("ciphertext-access-two", "ciphertext-refresh-two");
		connectionRepository.save(first);
		connectionRepository.save(second);

		assertEquals(1, jdbcTemplate.queryForObject(
				"select count(*) from lark_user_connection where open_id = 'ou-integration'",
				Integer.class));
		LarkUserConnection stored = connectionRepository.findByOpenId("ou-integration").orElseThrow();
		assertEquals("ciphertext-access-two", stored.accessTokenCiphertext());
		assertEquals("ciphertext-refresh-two", stored.refreshTokenCiphertext());
	}

	@Test
	void encryptedTokensSurviveAPostgresRoundTrip() {
		String key = Base64.getEncoder().encodeToString(new byte[32]);
		AesGcmTokenCipher cipher = new AesGcmTokenCipher(key);
		TokenContext accessContext = new TokenContext(
				"tenant-integration", "ou-encrypted-integration", TokenContext.TokenType.ACCESS);
		TokenContext refreshContext = new TokenContext(
				"tenant-integration", "ou-encrypted-integration", TokenContext.TokenType.REFRESH);
		LarkUserConnection connection = new LarkUserConnection(
				"ou-encrypted-integration",
				"tenant-integration",
				"Victor",
				cipher.encrypt("access-plaintext", accessContext),
				cipher.encrypt("refresh-plaintext", refreshContext),
				Instant.now().plusSeconds(3600),
				Instant.now().plusSeconds(7200),
				LarkUserConnection.ConnectionStatus.ACTIVE,
				Instant.now());

		connectionRepository.save(connection);
		LarkUserConnection stored = connectionRepository
				.findByOpenId("ou-encrypted-integration")
				.orElseThrow();

		assertEquals("access-plaintext", cipher.decrypt(stored.accessTokenCiphertext(), accessContext));
		assertEquals("refresh-plaintext", cipher.decrypt(stored.refreshTokenCiphertext(), refreshContext));
	}

	@Test
	void statusEndpointReturnsStableContract() throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create("http://localhost:" + port + "/api/status"))
				.GET()
				.build();

		HttpResponse<String> response = HttpClient.newHttpClient()
				.send(request, HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
		assertTrue(response.body().contains("\"service\":\"synvo-backend\""));
		assertTrue(response.body().contains("\"status\":\"ready\""));
	}

	@Test
	void sessionWritesAreProtectedByCsrfAndUseAnHttpOnlyStrictCookie()
			throws IOException, InterruptedException {
		CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
		URI signOutUri = URI.create("http://localhost:" + port + "/api/lark/auth/sign-out");

		HttpResponse<String> rejected = client.send(
				HttpRequest.newBuilder(signOutUri)
						.POST(HttpRequest.BodyPublishers.noBody())
						.build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(403, rejected.statusCode());

		HttpResponse<String> bootstrap = client.send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/lark/auth/bootstrap"))
						.GET()
						.build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, bootstrap.statusCode());
		String sessionCookie = bootstrap.headers().allValues("set-cookie").stream()
				.filter(value -> value.startsWith("SYNVO_SESSION="))
				.findFirst()
				.orElseThrow();
		assertTrue(sessionCookie.contains("HttpOnly"));
		assertTrue(sessionCookie.contains("SameSite=Strict"));

		Matcher csrfMatcher = CSRF_TOKEN_PATTERN.matcher(bootstrap.body());
		assertTrue(csrfMatcher.find());
		String csrfToken = csrfMatcher.group(1);
		HttpResponse<String> accepted = client.send(
				HttpRequest.newBuilder(signOutUri)
						.header("X-SYNVO-CSRF", csrfToken)
						.POST(HttpRequest.BodyPublishers.noBody())
						.build(),
				HttpResponse.BodyHandlers.ofString());
		assertEquals(200, accepted.statusCode());
		assertTrue(accepted.body().contains("\"userAuthorization\":\"disabled\""));
	}

	private static LarkUserConnection connection(String accessCiphertext, String refreshCiphertext) {
		return new LarkUserConnection(
				"ou-integration",
				"tenant-integration",
				"Victor",
				accessCiphertext,
				refreshCiphertext,
				Instant.now().plusSeconds(3600),
				Instant.now().plusSeconds(7200),
				LarkUserConnection.ConnectionStatus.ACTIVE,
				Instant.now());
	}
}
