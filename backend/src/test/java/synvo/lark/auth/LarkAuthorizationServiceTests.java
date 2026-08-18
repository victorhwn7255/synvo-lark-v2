package synvo.lark.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import synvo.configuration.LarkProperties;
import synvo.persistence.AuthorizationCodeClaimRepository;
import synvo.persistence.LarkUserConnection;
import synvo.persistence.LarkUserConnectionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LarkAuthorizationServiceTests {

	private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
	private static final String PILOT = "ou-victor";

	private LarkAuthClient authClient;
	private LarkUserConnectionRepository connectionRepository;
	private AuthorizationCodeClaimRepository codeClaimRepository;
	private TokenCipher tokenCipher;
	private LarkAuthorizationService service;

	@BeforeEach
	void setUp() {
		authClient = mock(LarkAuthClient.class);
		connectionRepository = mock(LarkUserConnectionRepository.class);
		codeClaimRepository = mock(AuthorizationCodeClaimRepository.class);
		tokenCipher = new AesGcmTokenCipher(KEY);
		service = new LarkAuthorizationService(
				properties(), authClient, tokenCipher, connectionRepository, codeClaimRepository);
	}

	@Test
	void validPilotAuthorizationStoresOnlyEncryptedTokens() {
		when(codeClaimRepository.tryClaim(any())).thenReturn(true);
		when(authClient.exchangeAuthorizationCode("single-use-code"))
				.thenReturn(tokens(PILOT, "access-plain", "refresh-plain", Duration.ofHours(2)));

		LarkAuthorizationService.AuthorizedConnection authorized = service.authorize("single-use-code");

		assertEquals(PILOT, authorized.openId());
		assertEquals("Victor", authorized.displayName());
		assertEquals("https://example.com/victor.png", authorized.avatarUrl());
		ArgumentCaptor<LarkUserConnection> captor = ArgumentCaptor.forClass(LarkUserConnection.class);
		verify(connectionRepository).save(captor.capture());
		LarkUserConnection stored = captor.getValue();
		assertNotEquals("access-plain", stored.accessTokenCiphertext());
		assertNotEquals("refresh-plain", stored.refreshTokenCiphertext());
		assertEquals("access-plain", tokenCipher.decrypt(
				stored.accessTokenCiphertext(),
				new TokenContext("tenant-synvo", PILOT, TokenContext.TokenType.ACCESS)));
	}

	@Test
	void nonPilotAuthorizationIsRejectedAndNeverStored() {
		when(codeClaimRepository.tryClaim(any())).thenReturn(true);
		when(authClient.exchangeAuthorizationCode("other-code"))
				.thenReturn(tokens("ou-other", "access", "refresh", Duration.ofHours(2)));

		assertThrows(LarkAuthorizationService.PilotAccessDeniedException.class,
				() -> service.authorize("other-code"));
		verify(connectionRepository, never()).save(any());
	}

	@Test
	void replayedCodeIsRejectedBeforeLarkExchange() {
		when(codeClaimRepository.tryClaim(any())).thenReturn(false);

		assertThrows(LarkAuthorizationService.AuthorizationCodeReplayException.class,
				() -> service.authorize("replayed-code"));
		verify(authClient, never()).exchangeAuthorizationCode(any());
	}

	@Test
	void invalidOrExpiredCodeFailsWithoutPersistingTokens() {
		when(codeClaimRepository.tryClaim(any())).thenReturn(true);
		when(authClient.exchangeAuthorizationCode("expired-code"))
				.thenThrow(new LarkAuthorizationException("CODE_EXCHANGE_REJECTED", true));

		assertThrows(LarkAuthorizationException.class, () -> service.authorize("expired-code"));
		verify(connectionRepository, never()).save(any());
	}

	@Test
	void accessTokenInsideRefreshWindowIsRotatedOnDemand() {
		LarkUserConnection current = storedConnection(
				"old-access", "old-refresh", Instant.now().plusSeconds(30));
		when(connectionRepository.findByOpenId(PILOT)).thenReturn(Optional.of(current));
		when(authClient.refresh("old-refresh"))
				.thenReturn(tokens(PILOT, "new-access", "new-refresh", Duration.ofHours(2)));

		String accessToken = service.getValidAccessToken();

		assertEquals("new-access", accessToken);
		ArgumentCaptor<LarkUserConnection> captor = ArgumentCaptor.forClass(LarkUserConnection.class);
		verify(connectionRepository).save(captor.capture());
		LarkUserConnection rotated = captor.getValue();
		assertEquals("new-refresh", tokenCipher.decrypt(
				rotated.refreshTokenCiphertext(),
				new TokenContext("tenant-synvo", PILOT, TokenContext.TokenType.REFRESH)));
	}

	@Test
	void terminalRefreshFailureRequiresReauthorization() {
		LarkUserConnection current = storedConnection(
				"old-access", "old-refresh", Instant.now().plusSeconds(30));
		when(connectionRepository.findByOpenId(PILOT)).thenReturn(Optional.of(current));
		when(authClient.refresh("old-refresh"))
				.thenThrow(new LarkAuthorizationException("TOKEN_REFRESH_REJECTED", true));

		assertThrows(LarkAuthorizationService.ReauthorizationRequiredException.class,
				() -> service.getValidAccessToken());
		verify(connectionRepository).markReauthorizationRequired(PILOT);
	}

	private LarkUserConnection storedConnection(
			String accessToken, String refreshToken, Instant accessExpiresAt) {
		TokenContext accessContext = new TokenContext(
				"tenant-synvo", PILOT, TokenContext.TokenType.ACCESS);
		TokenContext refreshContext = new TokenContext(
				"tenant-synvo", PILOT, TokenContext.TokenType.REFRESH);
		return new LarkUserConnection(
				PILOT,
				"tenant-synvo",
				"Victor",
				tokenCipher.encrypt(accessToken, accessContext),
				tokenCipher.encrypt(refreshToken, refreshContext),
				accessExpiresAt,
				Instant.now().plus(Duration.ofDays(30)),
				LarkUserConnection.ConnectionStatus.ACTIVE,
				Instant.now());
	}

	private static LarkUserTokens tokens(
			String openId, String accessToken, String refreshToken, Duration accessLifetime) {
		return new LarkUserTokens(
				openId,
				"tenant-synvo",
				"Victor",
				"https://example.com/victor.png",
				accessToken,
				refreshToken,
				Instant.now().plus(accessLifetime),
				Instant.now().plus(Duration.ofDays(30)));
	}

	private static LarkProperties properties() {
		return new LarkProperties(
				true, "cli-test", "secret-test", "websocket", PILOT, null, KEY,
				Duration.ofMinutes(5), Duration.ofDays(30));
	}
}
