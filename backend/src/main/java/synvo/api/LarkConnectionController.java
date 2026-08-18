package synvo.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import synvo.configuration.LarkProperties;
import synvo.lark.auth.LarkAuthorizationException;
import synvo.lark.auth.LarkAuthorizationService;
import synvo.lark.channel.LarkConnectionStatus;

@RestController
@RequestMapping("/api/lark")
class LarkConnectionController {

	private static final String AUTH_FLOW_STATE = "larkAuthorizationState";

	private final LarkProperties properties;
	private final LarkConnectionStatus connectionStatus;
	private final LarkSessionAccess sessionAccess;
	private final ObjectProvider<LarkAuthorizationService> authorizationService;
	private final SecureRandom secureRandom = new SecureRandom();

	LarkConnectionController(
			LarkProperties properties,
			LarkConnectionStatus connectionStatus,
			LarkSessionAccess sessionAccess,
			ObjectProvider<LarkAuthorizationService> authorizationService) {
		this.properties = properties;
		this.connectionStatus = connectionStatus;
		this.sessionAccess = sessionAccess;
		this.authorizationService = authorizationService;
	}

	@GetMapping("/connection")
	ConnectionResponse connection(HttpSession session) {
		return currentConnection(session);
	}

	@GetMapping("/auth/bootstrap")
	AuthorizationBootstrap bootstrap(HttpSession session, CsrfToken csrfToken) {
		if (!properties.enabled()) {
			return new AuthorizationBootstrap(false, null, null, csrfToken.getToken());
		}
		byte[] stateBytes = new byte[32];
		secureRandom.nextBytes(stateBytes);
		String state = Base64.getUrlEncoder().withoutPadding().encodeToString(stateBytes);
		session.setAttribute(AUTH_FLOW_STATE, state);
		return new AuthorizationBootstrap(true, properties.appId(), state, csrfToken.getToken());
	}

	@PostMapping("/auth/exchange")
	ConnectionResponse exchange(
			@Valid @RequestBody AuthorizationExchange requestBody,
			HttpServletRequest request,
			HttpSession session) {
		if (!properties.enabled()) {
			throw new LarkUnavailableException();
		}
		Object expectedState = session.getAttribute(AUTH_FLOW_STATE);
		session.removeAttribute(AUTH_FLOW_STATE);
		if (!(expectedState instanceof String state) || !constantTimeEquals(state, requestBody.state())) {
			throw new InvalidAuthorizationStateException();
		}

		LarkAuthorizationService service = authorizationService.getIfAvailable();
		if (service == null) {
			throw new LarkUnavailableException();
		}
		LarkAuthorizationService.AuthorizedConnection authorized = service.authorize(requestBody.code());
		request.changeSessionId();
		sessionAccess.establish(
				session, authorized.openId(), authorized.displayName(), authorized.avatarUrl());
		return currentConnection(session);
	}

	@PostMapping("/auth/sign-out")
	ConnectionResponse signOut(HttpSession session) {
		session.invalidate();
		return new ConnectionResponse(
				properties.enabled(),
				connectionStatus.snapshot().state().name().toLowerCase(),
				properties.enabled() ? "unauthorized" : "disabled",
				null);
	}

	private ConnectionResponse currentConnection(HttpSession session) {
		String authorizationState = "disabled";
		SafeUser user = null;
		if (properties.enabled()) {
			authorizationState = "unauthorized";
			LarkSessionAccess.AuthorizedUser authorized = sessionAccess.current(session).orElse(null);
			if (authorized != null) {
				authorizationState = "connected";
				user = new SafeUser(authorized.displayName(), authorized.avatarUrl());
			}
		}
		return new ConnectionResponse(
				properties.enabled(),
				connectionStatus.snapshot().state().name().toLowerCase(),
				authorizationState,
				user);
	}

	private static boolean constantTimeEquals(String expected, String actual) {
		return MessageDigest.isEqual(
				expected.getBytes(StandardCharsets.UTF_8),
				actual.getBytes(StandardCharsets.UTF_8));
	}

	@ExceptionHandler(LarkAuthorizationService.PilotAccessDeniedException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	ApiError pilotDenied() {
		return new ApiError("PILOT_ACCESS_DENIED", "This Phase 1 pilot is restricted to Victor.");
	}

	@ExceptionHandler(LarkAuthorizationService.AuthorizationCodeReplayException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	ApiError replayedCode() {
		return new ApiError("AUTHORIZATION_CODE_REPLAYED", "Start a new Lark authorization attempt.");
	}

	@ExceptionHandler({LarkAuthorizationException.class, InvalidAuthorizationStateException.class})
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	ApiError authorizationFailed() {
		return new ApiError("LARK_AUTHORIZATION_FAILED", "Lark authorization could not be completed.");
	}

	@ExceptionHandler(LarkUnavailableException.class)
	@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
	ApiError larkUnavailable() {
		return new ApiError("LARK_NOT_CONFIGURED", "Lark connectivity is not enabled.");
	}

	record AuthorizationBootstrap(
			boolean larkEnabled, String appId, String state, String csrfToken) {
	}

	record AuthorizationExchange(
			@NotBlank @Size(max = 2048) String code,
			@NotBlank @Size(max = 256) String state) {
	}

	record ConnectionResponse(
			boolean larkEnabled,
			String botConnection,
			String userAuthorization,
			SafeUser user) {
	}

	record SafeUser(String displayName, String avatarUrl) {
	}

	record ApiError(String code, String message) {
	}

	private static final class InvalidAuthorizationStateException extends RuntimeException {
	}

	private static final class LarkUnavailableException extends RuntimeException {
	}
}
