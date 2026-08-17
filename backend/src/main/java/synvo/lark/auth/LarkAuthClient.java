package synvo.lark.auth;

public interface LarkAuthClient {

	LarkUserTokens exchangeAuthorizationCode(String authorizationCode);

	LarkUserTokens refresh(String refreshToken);
}
