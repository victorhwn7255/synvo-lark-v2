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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import synvo.lark.auth.AesGcmTokenCipher;
import synvo.lark.auth.TokenContext;
import synvo.persistence.LarkMessageProcessingRepository;
import synvo.persistence.LarkUserConnection;
import synvo.persistence.LarkUserConnectionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

	@Test
	void applicationStartsAndConnectsToPostgres() {
		Integer result = jdbcTemplate.queryForObject("select 1", Integer.class);
		assertEquals(1, result);
		assertEquals("lark_message_processing", jdbcTemplate.queryForObject(
				"select to_regclass('public.lark_message_processing')::text", String.class));
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
