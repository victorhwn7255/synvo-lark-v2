package synvo.lark.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AesGcmTokenCipher implements TokenCipher {

	private static final String VERSION = "v1";
	private static final int NONCE_BYTES = 12;
	private static final int TAG_BITS = 128;
	private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
	private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

	private final SecretKeySpec key;
	private final SecureRandom secureRandom;

	public AesGcmTokenCipher(String base64Key) {
		this(base64Key, new SecureRandom());
	}

	AesGcmTokenCipher(String base64Key, SecureRandom secureRandom) {
		byte[] decodedKey;
		try {
			decodedKey = Base64.getDecoder().decode(base64Key);
		}
		catch (IllegalArgumentException exception) {
			throw new IllegalArgumentException("Token encryption key must be valid Base64", exception);
		}
		if (decodedKey.length != 32) {
			throw new IllegalArgumentException("Token encryption key must decode to 256 bits");
		}
		this.key = new SecretKeySpec(decodedKey, "AES");
		this.secureRandom = secureRandom;
	}

	@Override
	public String encrypt(String plaintext, TokenContext context) {
		byte[] nonce = new byte[NONCE_BYTES];
		secureRandom.nextBytes(nonce);
		byte[] ciphertext = crypt(Cipher.ENCRYPT_MODE, plaintext.getBytes(StandardCharsets.UTF_8), nonce, context);
		return VERSION + "." + ENCODER.encodeToString(nonce) + "." + ENCODER.encodeToString(ciphertext);
	}

	@Override
	public String decrypt(String encodedCiphertext, TokenContext context) {
		String[] parts = encodedCiphertext.split("\\.", -1);
		if (parts.length != 3 || !VERSION.equals(parts[0])) {
			throw new TokenDecryptionException("Unsupported encrypted token format");
		}
		try {
			byte[] nonce = DECODER.decode(parts[1]);
			byte[] ciphertext = DECODER.decode(parts[2]);
			if (nonce.length != NONCE_BYTES) {
				throw new TokenDecryptionException("Invalid encrypted token nonce");
			}
			return new String(crypt(Cipher.DECRYPT_MODE, ciphertext, nonce, context), StandardCharsets.UTF_8);
		}
		catch (IllegalArgumentException exception) {
			throw new TokenDecryptionException("Invalid encrypted token encoding", exception);
		}
	}

	private byte[] crypt(int mode, byte[] input, byte[] nonce, TokenContext context) {
		try {
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
			cipher.updateAAD(context.authenticatedData());
			return cipher.doFinal(input);
		}
		catch (GeneralSecurityException exception) {
			throw new TokenDecryptionException("Encrypted token could not be authenticated", exception);
		}
	}

	public static final class TokenDecryptionException extends RuntimeException {
		public TokenDecryptionException(String message) {
			super(message);
		}

		public TokenDecryptionException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
