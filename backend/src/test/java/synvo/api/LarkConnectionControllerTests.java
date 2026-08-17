package synvo.api;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.web.csrf.CsrfToken;
import synvo.configuration.LarkProperties;
import synvo.lark.auth.LarkAuthorizationService;
import synvo.lark.channel.LarkConnectionStatus;
import synvo.persistence.LarkUserConnection;
import synvo.persistence.LarkUserConnectionRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LarkConnectionControllerTests {

	@Test
	void validVictorExchangeEstablishesASafeBackendSession() {
		LarkProperties properties = properties();
		LarkUserConnectionRepository connections = mock(LarkUserConnectionRepository.class);
		LarkAuthorizationService authorization = mock(LarkAuthorizationService.class);
		@SuppressWarnings("unchecked")
		ObjectProvider<LarkAuthorizationService> provider = mock(ObjectProvider.class);
		when(provider.getIfAvailable()).thenReturn(authorization);
		when(authorization.authorize("single-use-code"))
				.thenReturn(new LarkAuthorizationService.AuthorizedConnection("ou-victor", "Victor"));
		when(connections.findByOpenId("ou-victor")).thenReturn(Optional.of(activeConnection()));
		LarkConnectionController controller = new LarkConnectionController(
				properties,
				new LarkConnectionStatus(properties),
				connections,
				provider);
		MockHttpSession session = new MockHttpSession();
		CsrfToken csrfToken = mock(CsrfToken.class);
		when(csrfToken.getToken()).thenReturn("csrf-token");

		LarkConnectionController.AuthorizationBootstrap bootstrap = controller.bootstrap(session, csrfToken);
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setSession(session);
		LarkConnectionController.ConnectionResponse response = controller.exchange(
				new LarkConnectionController.AuthorizationExchange("single-use-code", bootstrap.state()),
				request,
				session);

		assertNotNull(bootstrap.state());
		assertEquals("csrf-token", bootstrap.csrfToken());
		assertEquals("connected", response.userAuthorization());
		assertEquals("Victor", response.user().displayName());
		assertEquals("ou-victor", session.getAttribute("authorizedLarkOpenId"));
	}

	private static LarkProperties properties() {
		return new LarkProperties(
				true,
				"cli-test",
				"secret-test",
				"websocket",
				"ou-victor",
				null,
				Base64.getEncoder().encodeToString(new byte[32]),
				Duration.ofMinutes(5),
				Duration.ofDays(30));
	}

	private static LarkUserConnection activeConnection() {
		return new LarkUserConnection(
				"ou-victor",
				"tenant-synvo",
				"Victor",
				"ciphertext-access",
				"ciphertext-refresh",
				Instant.now().plusSeconds(3600),
				Instant.now().plusSeconds(7200),
				LarkUserConnection.ConnectionStatus.ACTIVE,
				Instant.now());
	}
}
