package synvo.persistence;

import java.time.Instant;

public record LarkUserConnection(
		String openId,
		String tenantKey,
		String displayName,
		String accessTokenCiphertext,
		String refreshTokenCiphertext,
		Instant accessExpiresAt,
		Instant refreshExpiresAt,
		ConnectionStatus connectionStatus,
		Instant updatedAt) {

	public enum ConnectionStatus {
		ACTIVE,
		REAUTHORIZATION_REQUIRED
	}
}
