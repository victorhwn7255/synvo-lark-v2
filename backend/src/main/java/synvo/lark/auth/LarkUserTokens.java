package synvo.lark.auth;

import java.time.Instant;

public record LarkUserTokens(
		String openId,
		String tenantKey,
		String displayName,
		String avatarUrl,
		String accessToken,
		String refreshToken,
		Instant accessExpiresAt,
		Instant refreshExpiresAt) {
}
