package synvo.lark.auth;

import com.lark.oapi.Client;
import com.lark.oapi.core.enums.BaseUrlEnum;
import com.lark.oapi.core.request.RequestOptions;
import com.lark.oapi.service.authen.v1.model.CreateAccessTokenReq;
import com.lark.oapi.service.authen.v1.model.CreateAccessTokenReqBody;
import com.lark.oapi.service.authen.v1.model.CreateAccessTokenResp;
import com.lark.oapi.service.authen.v1.model.CreateAccessTokenRespBody;
import com.lark.oapi.service.authen.v1.model.CreateRefreshAccessTokenReq;
import com.lark.oapi.service.authen.v1.model.CreateRefreshAccessTokenReqBody;
import com.lark.oapi.service.authen.v1.model.CreateRefreshAccessTokenResp;
import com.lark.oapi.service.authen.v1.model.CreateRefreshAccessTokenRespBody;
import com.lark.oapi.service.authen.v1.model.GetUserInfoResp;
import com.lark.oapi.service.authen.v1.model.GetUserInfoRespBody;
import java.time.Instant;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import synvo.configuration.LarkProperties;

@Component
@ConditionalOnProperty(prefix = "synvo.lark", name = "enabled", havingValue = "true")
final class OfficialLarkAuthClient implements LarkAuthClient {

	private final Client client;

	OfficialLarkAuthClient(LarkProperties properties) {
		this.client = Client.newBuilder(properties.appId(), properties.appSecret())
				.openBaseUrl(BaseUrlEnum.LarkSuite)
				.logReqAtDebug(false)
				.source("synvo-assistant")
				.build();
	}

	@Override
	public LarkUserTokens exchangeAuthorizationCode(String authorizationCode) {
		try {
			CreateAccessTokenReq request = CreateAccessTokenReq.newBuilder()
					.createAccessTokenReqBody(CreateAccessTokenReqBody.newBuilder()
							.grantType("authorization_code")
							.code(authorizationCode)
							.build())
					.build();
			CreateAccessTokenResp response = client.authen().v1().accessToken().create(request);
			if (!response.success() || response.getData() == null) {
				throw new LarkAuthorizationException("CODE_EXCHANGE_REJECTED", true);
			}
			return verifyIdentity(response.getData());
		}
		catch (LarkAuthorizationException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new LarkAuthorizationException("CODE_EXCHANGE_UNAVAILABLE", false, exception);
		}
	}

	@Override
	public LarkUserTokens refresh(String refreshToken) {
		try {
			CreateRefreshAccessTokenReq request = CreateRefreshAccessTokenReq.newBuilder()
					.createRefreshAccessTokenReqBody(CreateRefreshAccessTokenReqBody.newBuilder()
							.grantType("refresh_token")
							.refreshToken(refreshToken)
							.build())
					.build();
			CreateRefreshAccessTokenResp response = client.authen().v1().refreshAccessToken().create(request);
			if (!response.success() || response.getData() == null) {
				throw new LarkAuthorizationException("TOKEN_REFRESH_REJECTED", true);
			}
			return verifyIdentity(response.getData());
		}
		catch (LarkAuthorizationException exception) {
			throw exception;
		}
		catch (Exception exception) {
			throw new LarkAuthorizationException("TOKEN_REFRESH_UNAVAILABLE", false, exception);
		}
	}

	private LarkUserTokens verifyIdentity(CreateAccessTokenRespBody tokens) throws Exception {
		GetUserInfoRespBody identity = fetchIdentity(tokens.getAccessToken());
		return createTokens(
				identity,
				tokens.getAccessToken(),
				tokens.getRefreshToken(),
				tokens.getExpiresIn(),
				tokens.getRefreshExpiresIn());
	}

	private LarkUserTokens verifyIdentity(CreateRefreshAccessTokenRespBody tokens) throws Exception {
		GetUserInfoRespBody identity = fetchIdentity(tokens.getAccessToken());
		return createTokens(
				identity,
				tokens.getAccessToken(),
				tokens.getRefreshToken(),
				tokens.getExpiresIn(),
				tokens.getRefreshExpiresIn());
	}

	private GetUserInfoRespBody fetchIdentity(String accessToken) throws Exception {
		if (!StringUtils.hasText(accessToken)) {
			throw new LarkAuthorizationException("MALFORMED_TOKEN_RESPONSE", true);
		}
		RequestOptions options = RequestOptions.newBuilder().userAccessToken(accessToken).build();
		GetUserInfoResp response = client.authen().v1().userInfo().get(options);
		if (!response.success() || response.getData() == null) {
			throw new LarkAuthorizationException("IDENTITY_LOOKUP_REJECTED", true);
		}
		return response.getData();
	}

	private static LarkUserTokens createTokens(
			GetUserInfoRespBody identity,
			String accessToken,
			String refreshToken,
			Integer accessExpiresIn,
			Integer refreshExpiresIn) {
		if (!StringUtils.hasText(identity.getOpenId())
				|| !StringUtils.hasText(identity.getTenantKey())
				|| !StringUtils.hasText(identity.getName())
				|| !StringUtils.hasText(accessToken)
				|| !StringUtils.hasText(refreshToken)
				|| accessExpiresIn == null
				|| accessExpiresIn <= 0
				|| refreshExpiresIn == null
				|| refreshExpiresIn <= 0) {
			throw new LarkAuthorizationException("MALFORMED_TOKEN_RESPONSE", true);
		}
		Instant now = Instant.now();
		return new LarkUserTokens(
				identity.getOpenId(),
				identity.getTenantKey(),
				identity.getName(),
				firstNonBlank(identity.getAvatarThumb(), identity.getAvatarUrl()),
				accessToken,
				refreshToken,
				now.plusSeconds(accessExpiresIn),
				now.plusSeconds(refreshExpiresIn));
	}

	private static String firstNonBlank(String preferred, String fallback) {
		if (StringUtils.hasText(preferred)) {
			return preferred;
		}
		return StringUtils.hasText(fallback) ? fallback : null;
	}
}
