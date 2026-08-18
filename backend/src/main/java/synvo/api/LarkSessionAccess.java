package synvo.api;

import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import org.springframework.stereotype.Component;
import synvo.configuration.LarkProperties;
import synvo.persistence.LarkUserConnection;
import synvo.persistence.LarkUserConnectionRepository;

@Component
final class LarkSessionAccess {

	private static final String AUTHORIZED_OPEN_ID = "authorizedLarkOpenId";
	private static final String AUTHORIZED_DISPLAY_NAME = "authorizedLarkDisplayName";
	private static final String AUTHORIZED_AVATAR_URL = "authorizedLarkAvatarUrl";

	private final LarkProperties properties;
	private final LarkUserConnectionRepository connectionRepository;

	LarkSessionAccess(
			LarkProperties properties,
			LarkUserConnectionRepository connectionRepository) {
		this.properties = properties;
		this.connectionRepository = connectionRepository;
	}

	void establish(HttpSession session, String openId, String displayName, String avatarUrl) {
		session.setAttribute(AUTHORIZED_OPEN_ID, openId);
		session.setAttribute(AUTHORIZED_DISPLAY_NAME, displayName);
		if (avatarUrl != null && !avatarUrl.isBlank()) {
			session.setAttribute(AUTHORIZED_AVATAR_URL, avatarUrl);
		}
	}

	Optional<AuthorizedUser> current(HttpSession session) {
		if (!properties.enabled()) {
			return Optional.empty();
		}
		Object sessionOpenId = session.getAttribute(AUTHORIZED_OPEN_ID);
		if (!properties.pilotOpenId().equals(sessionOpenId)) {
			return Optional.empty();
		}
		LarkUserConnection connection = connectionRepository
				.findByOpenId(properties.pilotOpenId())
				.orElse(null);
		if (connection == null
				|| connection.connectionStatus() != LarkUserConnection.ConnectionStatus.ACTIVE) {
			clear(session);
			return Optional.empty();
		}
		Object displayName = session.getAttribute(AUTHORIZED_DISPLAY_NAME);
		Object avatarUrl = session.getAttribute(AUTHORIZED_AVATAR_URL);
		return Optional.of(new AuthorizedUser(
				properties.pilotOpenId(),
				displayName instanceof String value ? value : "",
				avatarUrl instanceof String value ? value : null));
	}

	AuthorizedUser require(HttpSession session) {
		return current(session).orElseThrow(UnauthorizedSessionException::new);
	}

	void clear(HttpSession session) {
		session.removeAttribute(AUTHORIZED_OPEN_ID);
		session.removeAttribute(AUTHORIZED_DISPLAY_NAME);
		session.removeAttribute(AUTHORIZED_AVATAR_URL);
	}

	record AuthorizedUser(String openId, String displayName, String avatarUrl) {
	}

	static final class UnauthorizedSessionException extends RuntimeException {
	}
}
