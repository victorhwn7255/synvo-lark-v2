package synvo.lark.auth;

public interface TokenCipher {

	String encrypt(String plaintext, TokenContext context);

	String decrypt(String ciphertext, TokenContext context);
}
