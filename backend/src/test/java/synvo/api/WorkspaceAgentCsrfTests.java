package synvo.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration;
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import synvo.configuration.WebSecurityTestConfiguration;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDecision;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionKind;
import synvo.workspaceagent.WorkspaceAgentFacade;
import synvo.workspaceagent.WorkspaceAgentFacade.InteractionView;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkspaceAgentController.class)
@ImportAutoConfiguration({
		SecurityAutoConfiguration.class,
		ServletWebSecurityAutoConfiguration.class,
		SecurityFilterAutoConfiguration.class
})
@Import(WebSecurityTestConfiguration.class)
class WorkspaceAgentCsrfTests {

	@Autowired
	private MockMvc mvc;

	@MockitoBean
	private LarkSessionAccess sessionAccess;

	@MockitoBean
	private WorkspaceAgentFacade facade;

	@MockitoBean
	private WorkspaceAgentEventStream eventStream;

	@Test
	void interactionDecisionRequiresCsrfBeforeReachingTheApplicationBoundary() throws Exception {
		UUID interactionId = UUID.randomUUID();
		doReturn(new LarkSessionAccess.AuthorizedUser("ou-victor", "Victor", null))
				.when(sessionAccess).require(any());
		when(facade.decideInteraction(
				"ou-victor", interactionId, InteractionDecision.DECLINE, Map.of()))
				.thenReturn(new InteractionView(
						interactionId, UUID.randomUUID(), UUID.randomUUID(),
						"pilot", "Pilot workspace",
						InteractionKind.COMMAND_APPROVAL, "shell command", "Run a command",
						"workspace command", List.of(InteractionDecision.DECLINE),
						"DECIDED", InteractionDecision.DECLINE, Instant.now(), null));

		var request = post("/api/codex/interactions/{interactionId}/decision", interactionId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"decision\":\"DECLINE\"}");
		mvc.perform(request).andExpect(status().isForbidden());
		mvc.perform(request.with(csrf())).andExpect(status().isOk());
	}
}
