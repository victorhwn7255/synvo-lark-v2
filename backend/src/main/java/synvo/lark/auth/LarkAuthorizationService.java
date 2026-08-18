package synvo.lark.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import synvo.configuration.LarkProperties;
import synvo.persistence.AuthorizationCodeClaimRepository;
import synvo.persistence.LarkUserConnection;
import synvo.persistence.LarkUserConnectionRepository;

@Service
@ConditionalOnProperty(prefix = "synvo.lark", name = "enabled", havingValue = "true")
public class LarkAuthorizationService {

	private final LarkProperties properties;
	private final LarkAuthClient authClient;
	private final TokenCipher tokenCipher;
	private final LarkUserConnectionRepository connectionRepository;
	private final AuthorizationCodeClaimRepository codeClaimRepository;

	public LarkAuthorizationService(
			LarkProperties properties,
			LarkAuthClient authClient,
			TokenCipher tokenCipher,
			LarkUserConnectionRepository connectionRepository,
			AuthorizationCodeClaimRepository codeClaimRepository) {
		this.properties = properties;
		this.authClient = authClient;
		this.tokenCipher = tokenCipher;
		this.connectionRepository = connectionRepository;
		this.codeClaimRepository = codeClaimRepository;
	}

	public AuthorizedConnection authorize(String authorizationCode) {
		if (!codeClaimRepository.tryClaim(hashCode(authorizationCode))) {
			throw new AuthorizationCodeReplayException();
		}
		LarkUserTokens tokens = authClient.exchangeAuthorizationCode(authorizationCode);
		requirePilot(tokens.openId());
		LarkUserConnection connection = encrypt(tokens);
		connectionRepository.save(connection);
		return new AuthorizedConnection(
				connection.openId(), connection.displayName(), tokens.avatarUrl());
	}

	public String getValidAccessToken() {
		LarkUserConnection connection = connectionRepository.findByOpenId(properties.pilotOpenId())
				.orElseThrow(ReauthorizationRequiredException::new);
		if (connection.connectionStatus() != LarkUserConnection.ConnectionStatus.ACTIVE) {
			throw new ReauthorizationRequiredException();
		}
		TokenContext accessContext = context(connection, TokenContext.TokenType.ACCESS);
		if (connection.accessExpiresAt().isAfter(Instant.now().plus(properties.tokenRefreshSkew()))) {
			return tokenCipher.decrypt(connection.accessTokenCiphertext(), accessContext);
		}

		String refreshToken = tokenCipher.decrypt(
				connection.refreshTokenCiphertext(),
				context(connection, TokenContext.TokenType.REFRESH));
		try {
			LarkUserTokens refreshed = authClient.refresh(refreshToken);
			requirePilot(refreshed.openId());
			LarkUserConnection updated = encrypt(refreshed);
			connectionRepository.save(updated);
			return tokenCipher.decrypt(
					updated.accessTokenCiphertext(),
					context(updated, TokenContext.TokenType.ACCESS));
		}
		catch (LarkAuthorizationException exception) {
			if (exception.reauthorizationRequired()) {
				connectionRepository.markReauthorizationRequired(connection.openId());
				throw new ReauthorizationRequiredException();
			}
			throw exception;
		}
	}

	private LarkUserConnection encrypt(LarkUserTokens tokens) {
		Instant now = Instant.now();
		TokenContext accessContext = new TokenContext(
				tokens.tenantKey(), tokens.openId(), TokenContext.TokenType.ACCESS);
		TokenContext refreshContext = new TokenContext(
				tokens.tenantKey(), tokens.openId(), TokenContext.TokenType.REFRESH);
		return new LarkUserConnection(
				tokens.openId(),
				tokens.tenantKey(),
				tokens.displayName(),
				tokenCipher.encrypt(tokens.accessToken(), accessContext),
				tokenCipher.encrypt(tokens.refreshToken(), refreshContext),
				tokens.accessExpiresAt(),
				tokens.refreshExpiresAt(),
				LarkUserConnection.ConnectionStatus.ACTIVE,
				now);
	}

	private void requirePilot(String openId) {
		if (!properties.pilotOpenId().equals(openId)) {
			throw new PilotAccessDeniedException();
		}
	}

	private static TokenContext context(
			LarkUserConnection connection, TokenContext.TokenType tokenType) {
		return new TokenContext(connection.tenantKey(), connection.openId(), tokenType);
	}

	private static String hashCode(String authorizationCode) {
		if (!StringUtils.hasText(authorizationCode)) {
			throw new LarkAuthorizationException("EMPTY_AUTHORIZATION_CODE", true);
		}
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
					.digest(authorizationCode.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		}
		catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	public record AuthorizedConnection(String openId, String displayName, String avatarUrl) {
	}

	public static final class AuthorizationCodeReplayException extends RuntimeException {
	}

	public static final class PilotAccessDeniedException extends RuntimeException {
	}

	public static final class ReauthorizationRequiredException extends RuntimeException {
	}
}
