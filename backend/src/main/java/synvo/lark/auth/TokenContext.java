package synvo.lark.auth;

import java.nio.charset.StandardCharsets;

public record TokenContext(String tenantKey, String openId, TokenType tokenType) {

	public byte[] authenticatedData() {
		return (tenantKey + "\u001f" + openId + "\u001f" + tokenType.name())
				.getBytes(StandardCharsets.UTF_8);
	}

	public enum TokenType {
		ACCESS,
		REFRESH
	}
}
