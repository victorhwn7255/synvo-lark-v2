package synvo.agent;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class IntentRouter {

	private static final Set<String> AMBIGUOUS_REQUESTS = Set.of(
			"help", "help me", "do it", "do that", "that", "something");
	private static final Set<String> WORKFLOW_ACTION_SIGNALS = Set.of(
			"analyze", "check", "compare", "convert", "create", "draft", "extract",
			"find", "identify", "list", "locate", "look up", "prepare", "process",
			"read", "research", "retrieve", "review", "search", "show", "summarize",
			"turn");
	private static final Set<String> MEETING_RESOURCE_SIGNALS = Set.of(
			"meeting transcript", "call transcript", "this transcript", "the transcript",
			"meeting minutes", "these meeting minutes", "these minutes", "the minutes",
			"meeting notes", "call notes", "this meeting", "the meeting", "our meeting",
			"today's meeting", "yesterday's meeting", "last meeting", "our last meeting",
			"action item from", "action items from", "follow up from", "follow ups from");
	private static final Set<String> RESEARCH_RESOURCE_SIGNALS = Set.of(
			"lark drive", "my drive", "our drive", "shared drive", "drive folder",
			"configured drive folder", "lark docs", "my documents", "our documents",
			"company document", "company documents", "internal document", "this document",
			"these documents", "the document", "attached document", "internal docs",
			"company docs", "our docs", "our wiki", "company wiki", "internal wiki",
			"our knowledge base", "company knowledge base", "team knowledge base",
			"internal file", "internal files", "these internal files");
	private static final Set<String> RESEARCH_CONTENT_QUERY_SIGNALS = Set.of(
			"which document", "which file", "what does", "what do", "where in", "where is",
			"show me", "tell me what", "contains", "contain", "mentions", "mention", "says",
			"say", "according to");
	private static final Set<String> MEETING_CONTENT_QUERY_SIGNALS = Set.of(
			"what did", "what were", "what was", "who owns", "who is", "which action",
			"show me", "deadline", "deadlines", "decision", "decisions", "assigns",
			"assigned", "owner", "owners");
	private static final Set<String> GUIDANCE_SIGNALS = Set.of(
			"how do i", "how can i", "how should i", "how would i", "how to",
			"can you explain how to", "explain how to");
	private static final Set<String> NON_EXECUTION_SIGNALS = Set.of(
			"not asking you to", "what would happen if", "hypothetically", "without doing it");
	private static final Set<String> VAGUE_ACTION_SIGNALS = Set.of(
			"do", "handle", "take care", "continue", "same");
	private static final Set<String> VAGUE_REFERENCE_SIGNALS = Set.of(
			"it", "that", "this", "those", "them", "previous", "same");

	public AgentIntent route(String content) {
		String normalized = normalize(content);
		if (AMBIGUOUS_REQUESTS.contains(stripTerminalPunctuation(normalized))) {
			return AgentIntent.CLARIFICATION;
		}

		EnumSet<AgentIntent> workflows = EnumSet.noneOf(AgentIntent.class);
		boolean vagueReference = false;
		for (String clause : splitClauses(normalized)) {
			if (clause.isBlank() || isNonExecutingClause(clause) || isGuidanceClause(clause)) {
				continue;
			}

			boolean hasAction = containsAny(clause, WORKFLOW_ACTION_SIGNALS);
			boolean meeting = containsAny(clause, MEETING_RESOURCE_SIGNALS)
					&& (hasAction || containsAny(clause, MEETING_CONTENT_QUERY_SIGNALS));
			boolean research = containsAny(clause, RESEARCH_RESOURCE_SIGNALS)
					&& (hasAction || containsAny(clause, RESEARCH_CONTENT_QUERY_SIGNALS));

			if (meeting) {
				workflows.add(AgentIntent.MEETING);
			}
			if (research) {
				workflows.add(AgentIntent.RESEARCH);
			}
			if (!meeting && !research && isVagueExecutionRequest(clause)) {
				vagueReference = true;
			}
		}

		if (workflows.size() > 1 || vagueReference) {
			return AgentIntent.CLARIFICATION;
		}
		if (workflows.contains(AgentIntent.MEETING)) {
			return AgentIntent.MEETING;
		}
		if (workflows.contains(AgentIntent.RESEARCH)) {
			return AgentIntent.RESEARCH;
		}
		return AgentIntent.DIRECT_ANSWER;
	}

	private static boolean isNonExecutingClause(String clause) {
		return clause.startsWith("don't ")
				|| clause.startsWith("do not ")
				|| clause.startsWith("without ")
				|| clause.startsWith("if ")
				|| clause.startsWith("imagine ")
				|| clause.startsWith("instead of ")
				|| containsAny(clause, NON_EXECUTION_SIGNALS);
	}

	private static boolean isGuidanceClause(String clause) {
		return containsAny(clause, GUIDANCE_SIGNALS);
	}

	private static boolean isVagueExecutionRequest(String clause) {
		return (containsAny(clause, WORKFLOW_ACTION_SIGNALS)
				|| containsAny(clause, VAGUE_ACTION_SIGNALS))
				&& containsAny(clause, VAGUE_REFERENCE_SIGNALS);
	}

	private static boolean containsAny(String content, Set<String> signals) {
		return signals.stream().anyMatch(signal -> containsPhrase(content, signal));
	}

	private static boolean containsPhrase(String content, String phrase) {
		int searchFrom = 0;
		while (searchFrom <= content.length() - phrase.length()) {
			int match = content.indexOf(phrase, searchFrom);
			if (match < 0) {
				return false;
			}
			int end = match + phrase.length();
			boolean leftBoundary = match == 0 || !isWordCharacter(content.charAt(match - 1));
			boolean rightBoundary = end == content.length() || !isWordCharacter(content.charAt(end));
			if (leftBoundary && rightBoundary) {
				return true;
			}
			searchFrom = match + 1;
		}
		return false;
	}

	private static boolean isWordCharacter(char value) {
		return Character.isLetterOrDigit(value) || value == '_';
	}

	private static String[] splitClauses(String content) {
		return content.split("\\s*(?:[;.!?]+|\\bbut\\b|\\bthen\\b)\\s*");
	}

	private static String normalize(String content) {
		String normalized = Normalizer.normalize(content == null ? "" : content, Normalizer.Form.NFKC)
				.toLowerCase(Locale.ROOT)
				.replace('’', '\'')
				.replaceAll("[\\p{Pd}]+", " ")
				.replaceAll("\\s+", " ")
				.strip();
		return normalized;
	}

	private static String stripTerminalPunctuation(String content) {
		return content.replaceFirst("[.!?]+$", "").strip();
	}
}
