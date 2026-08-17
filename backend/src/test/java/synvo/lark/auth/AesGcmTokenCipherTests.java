package synvo.lark.auth;

import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AesGcmTokenCipherTests {

	private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);
	private static final TokenContext CONTEXT = new TokenContext(
			"tenant-test", "open-id-test", TokenContext.TokenType.ACCESS);

	private final AesGcmTokenCipher cipher = new AesGcmTokenCipher(KEY);

	@Test
	void encryptsAndAuthenticatesTokensWithFreshNonces() {
		String first = cipher.encrypt("access-token-value", CONTEXT);
		String second = cipher.encrypt("access-token-value", CONTEXT);

		assertNotEquals(first, second);
		assertEquals("access-token-value", cipher.decrypt(first, CONTEXT));
		assertEquals("access-token-value", cipher.decrypt(second, CONTEXT));
	}

	@Test
	void rejectsTamperingAndDifferentAuthenticatedContext() {
		String encrypted = cipher.encrypt("refresh-token-value", CONTEXT);
		TokenContext differentContext = new TokenContext(
				"different-tenant", "open-id-test", TokenContext.TokenType.ACCESS);

		assertThrows(AesGcmTokenCipher.TokenDecryptionException.class,
				() -> cipher.decrypt(encrypted, differentContext));
		assertThrows(AesGcmTokenCipher.TokenDecryptionException.class,
				() -> cipher.decrypt(encrypted.substring(0, encrypted.length() - 2) + "aa", CONTEXT));
	}
}
