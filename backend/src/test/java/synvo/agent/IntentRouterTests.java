package synvo.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentRouterTests {

	private final IntentRouter router = new IntentRouter();

	@Test
	void routesGeneralQuestionsToDirectAnswers() {
		assertEquals(AgentIntent.DIRECT_ANSWER, router.route("Explain compound interest simply"));
		assertEquals(
				AgentIntent.DIRECT_ANSWER,
				router.route("Explain why source citations matter in enterprise research"));
		assertEquals(
				AgentIntent.DIRECT_ANSWER,
				router.route("Why are meeting transcripts useful?"));
	}

	@Test
	void routesOnlyExplicitlyAmbiguousRequestsToClarification() {
		assertEquals(AgentIntent.CLARIFICATION, router.route("help me"));
		assertEquals(AgentIntent.DIRECT_ANSWER, router.route("Help me understand HTTP caching"));
	}

	@Test
	void recognizesResearchWithoutPretendingTheWorkflowExists() {
		assertEquals(
				AgentIntent.RESEARCH,
				router.route("Find the launch plan in my Lark Drive folder"));
	}

	@Test
	void recognizesMeetingRequestsBeforeGeneralResearchSignals() {
		assertEquals(
				AgentIntent.MEETING,
				router.route("Read the meeting transcript and prepare action items"));
	}

	@Test
	void workflowNounsWithoutAnExecutionRequestRemainGeneralConversation() {
		assertEquals(
				AgentIntent.DIRECT_ANSWER,
				router.route("Describe our knowledge base architecture"));
		assertEquals(
				AgentIntent.DIRECT_ANSWER,
				router.route("What makes meeting minutes effective?"));
	}
}
