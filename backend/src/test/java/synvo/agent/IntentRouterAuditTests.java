package synvo.agent;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class IntentRouterAuditTests {

	private final IntentRouter router = new IntentRouter();

	@ParameterizedTest(name = "{0}")
	@MethodSource("routingCases")
	void routesAccordingToThePhaseTwoAcceptanceMatrix(
			String caseId,
			AgentIntent expected,
			String prompt) {
		assertEquals(expected, router.route(prompt), caseId + ": " + prompt);
	}

	private static Stream<Arguments> routingCases() {
		return Stream.of(
				// General and conceptual questions remain natural conversation.
				caseOf("direct-01", AgentIntent.DIRECT_ANSWER, "Explain compound interest simply."),
				caseOf("direct-02", AgentIntent.DIRECT_ANSWER,
						"In one short paragraph, explain why source citations matter in enterprise research."),
				caseOf("direct-03", AgentIntent.DIRECT_ANSWER, "Why are meeting transcripts useful?"),
				caseOf("direct-04", AgentIntent.DIRECT_ANSWER, "What makes meeting minutes effective?"),
				caseOf("direct-05", AgentIntent.DIRECT_ANSWER,
						"Describe how enterprise knowledge bases work."),
				caseOf("direct-06", AgentIntent.DIRECT_ANSWER, "How should a team organize its documents?"),
				caseOf("direct-07", AgentIntent.DIRECT_ANSWER, "What is a Lark Drive folder?"),
				caseOf("direct-08", AgentIntent.DIRECT_ANSWER, "How do I search my Lark Drive efficiently?"),
				caseOf("direct-09", AgentIntent.DIRECT_ANSWER,
						"Can you explain how to summarize a meeting transcript?"),
				caseOf("direct-10", AgentIntent.DIRECT_ANSWER,
						"Compare research and knowledge retrieval as concepts."),
				caseOf("direct-11", AgentIntent.DIRECT_ANSWER, "What are action items?"),
				caseOf("direct-12", AgentIntent.DIRECT_ANSWER, "Why should sources be cited?"),
				caseOf("direct-13", AgentIntent.DIRECT_ANSWER,
						"Explain how our knowledge base architecture should be designed."),
				caseOf("direct-14", AgentIntent.DIRECT_ANSWER,
						"What is discussed in a typical project meeting?"),
				caseOf("direct-15", AgentIntent.DIRECT_ANSWER, "How can I write better meeting notes?"),
				caseOf("direct-16", AgentIntent.DIRECT_ANSWER, "Define retrieval-augmented generation."),
				caseOf("direct-17", AgentIntent.DIRECT_ANSWER,
						"Tell me why internal documents need access control."),
				caseOf("direct-18", AgentIntent.DIRECT_ANSWER, "Describe the benefits of a shared Drive."),
				caseOf("direct-19", AgentIntent.DIRECT_ANSWER,
						"What does a good research report look like?"),
				caseOf("direct-20", AgentIntent.DIRECT_ANSWER,
						"How would a meeting-to-execution workflow operate?"),

				// Explicit requests against a bounded enterprise source become research intent.
				caseOf("research-01", AgentIntent.RESEARCH,
						"Find the launch plan in my Lark Drive folder."),
				caseOf("research-02", AgentIntent.RESEARCH, "Search our Drive for the pricing strategy."),
				caseOf("research-03", AgentIntent.RESEARCH,
						"Look up the Q3 roadmap in our knowledge base."),
				caseOf("research-04", AgentIntent.RESEARCH,
						"Summarize the internal document about onboarding."),
				caseOf("research-05", AgentIntent.RESEARCH,
						"Compare the two documents in our Drive."),
				caseOf("research-06", AgentIntent.RESEARCH,
						"Which document in our wiki mentions launch risks?"),
				caseOf("research-07", AgentIntent.RESEARCH,
						"What does our knowledge base say about retention?"),
				caseOf("research-08", AgentIntent.RESEARCH, "Where in my Drive is the hiring plan?"),
				caseOf("research-09", AgentIntent.RESEARCH,
						"Show me the latest company document on security."),
				caseOf("research-10", AgentIntent.RESEARCH,
						"Review these internal files and summarize the risks."),
				caseOf("research-11", AgentIntent.RESEARCH,
						"Retrieve the launch brief from the configured Drive folder."),
				caseOf("research-12", AgentIntent.RESEARCH,
						"Tell me what the document in our Drive says about pricing."),
				caseOf("research-13", AgentIntent.RESEARCH,
						"Check whether our wiki contains a travel policy."),
				caseOf("research-14", AgentIntent.RESEARCH,
						"Read this document and give me a summary."),
				caseOf("research-15", AgentIntent.RESEARCH,
						"Find citations in our documents for this claim."),
				caseOf("research-16", AgentIntent.RESEARCH,
						"SEARCH MY LARK DRIVE FOR THE SECURITY ROADMAP."),
				caseOf("research-17", AgentIntent.RESEARCH,
						"Could you please locate the launch notes in Lark Docs?"),
				caseOf("research-18", AgentIntent.RESEARCH,
						"Which internal file contains the onboarding checklist?"),

				// Explicit requests against meeting artifacts become meeting intent.
				caseOf("meeting-01", AgentIntent.MEETING,
						"Read the meeting transcript and prepare action items."),
				caseOf("meeting-02", AgentIntent.MEETING, "Summarize today's meeting."),
				caseOf("meeting-03", AgentIntent.MEETING,
						"Extract the decisions from the meeting transcript."),
				caseOf("meeting-04", AgentIntent.MEETING,
						"Turn these meeting minutes into a task list."),
				caseOf("meeting-05", AgentIntent.MEETING,
						"What did we decide in yesterday's meeting?"),
				caseOf("meeting-06", AgentIntent.MEETING,
						"Who owns the action item from today's meeting?"),
				caseOf("meeting-07", AgentIntent.MEETING,
						"List the follow-ups from our last meeting."),
				caseOf("meeting-08", AgentIntent.MEETING,
						"Review this meeting transcript for blockers."),
				caseOf("meeting-09", AgentIntent.MEETING,
						"Create an action plan from these call notes."),
				caseOf("meeting-10", AgentIntent.MEETING,
						"Compare the meeting minutes with the current action items."),
				caseOf("meeting-11", AgentIntent.MEETING,
						"Show me the deadlines from the meeting transcript."),
				caseOf("meeting-12", AgentIntent.MEETING,
						"Identify unresolved issues in the meeting notes."),
				caseOf("meeting-13", AgentIntent.MEETING,
						"Draft a follow-up from this meeting."),
				caseOf("meeting-14", AgentIntent.MEETING,
						"What were the key decisions in our meeting?"),
				caseOf("meeting-15", AgentIntent.MEETING,
						"Check whether the transcript assigns Victor a task."),
				caseOf("meeting-16", AgentIntent.MEETING, "Summarize today’s meeting."),
				caseOf("meeting-17", AgentIntent.MEETING,
						"Could you extract next steps from the call transcript?"),
				caseOf("meeting-18", AgentIntent.MEETING,
						"Turn the meeting-transcript into action items."),

				// Insufficient references should ask for clarification instead of guessing.
				caseOf("clarify-01", AgentIntent.CLARIFICATION, "help"),
				caseOf("clarify-02", AgentIntent.CLARIFICATION, "help me"),
				caseOf("clarify-03", AgentIntent.CLARIFICATION, "do it"),
				caseOf("clarify-04", AgentIntent.CLARIFICATION, "do that"),
				caseOf("clarify-05", AgentIntent.CLARIFICATION, "something"),
				caseOf("clarify-06", AgentIntent.CLARIFICATION, "Search it."),
				caseOf("clarify-07", AgentIntent.CLARIFICATION, "Summarize that."),
				caseOf("clarify-08", AgentIntent.CLARIFICATION,
						"Do the same for yesterday's meeting."),
				caseOf("clarify-09", AgentIntent.CLARIFICATION, "Handle those files."),
				caseOf("clarify-10", AgentIntent.CLARIFICATION, "Take care of this."),
				caseOf("clarify-11", AgentIntent.CLARIFICATION, "Find that for me."),
				caseOf("clarify-12", AgentIntent.CLARIFICATION,
						"Continue with the previous one."),

				// Two executable workflows in one turn must not be resolved by precedence.
				caseOf("mixed-01", AgentIntent.CLARIFICATION,
						"Search our Drive for the launch plan and extract actions from today's meeting."),
				caseOf("mixed-02", AgentIntent.CLARIFICATION,
						"Summarize our knowledge base and the meeting transcript."),
				caseOf("mixed-03", AgentIntent.CLARIFICATION,
						"Compare our internal docs with the meeting minutes."),
				caseOf("mixed-04", AgentIntent.CLARIFICATION,
						"Review the configured Drive folder and this meeting transcript."),
				caseOf("mixed-05", AgentIntent.CLARIFICATION,
						"Find launch risks in our wiki, then turn the meeting notes into tasks."),
				caseOf("mixed-06", AgentIntent.CLARIFICATION,
						"Retrieve the security policy from our Drive and create follow-ups from today's meeting."),

				// Negated, hypothetical, and instructional mentions must never launch a workflow.
				caseOf("nonexec-01", AgentIntent.DIRECT_ANSWER,
						"Don't search my Drive; explain how retrieval works."),
				caseOf("nonexec-02", AgentIntent.DIRECT_ANSWER,
						"Do not review our wiki. What is RAG?"),
				caseOf("nonexec-03", AgentIntent.DIRECT_ANSWER,
						"Without reading this document, explain the concept of citations."),
				caseOf("nonexec-04", AgentIntent.DIRECT_ANSWER,
						"I am not asking you to search our knowledge base; define embeddings."),
				caseOf("nonexec-05", AgentIntent.DIRECT_ANSWER,
						"If you searched my Drive, what permissions would be needed?"),
				caseOf("nonexec-06", AgentIntent.DIRECT_ANSWER,
						"How would I search our wiki myself?"),
				caseOf("nonexec-07", AgentIntent.DIRECT_ANSWER,
						"Can you explain how to summarize this meeting transcript without doing it?"),
				caseOf("nonexec-08", AgentIntent.DIRECT_ANSWER,
						"Don't extract action items from today's meeting; explain what action items are."),
				caseOf("nonexec-09", AgentIntent.DIRECT_ANSWER,
						"Imagine we reviewed our internal docs—what could go wrong?"),
				caseOf("nonexec-10", AgentIntent.DIRECT_ANSWER,
						"Instead of reading meeting minutes, describe best practices."),
				caseOf("nonexec-11", AgentIntent.DIRECT_ANSWER,
						"What would happen if Synvo searched our Drive?"),
				caseOf("nonexec-12", AgentIntent.DIRECT_ANSWER,
						"Explain why we should not automatically process meeting transcripts."));
	}

	private static Arguments caseOf(String id, AgentIntent expected, String prompt) {
		return Arguments.of(id, expected, prompt);
	}
}
