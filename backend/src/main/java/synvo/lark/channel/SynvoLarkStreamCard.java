package synvo.lark.channel;

import com.lark.oapi.channel.model.CardStreamController;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import synvo.agent.AgentLifecycleEvent.ActionHandoff;
import synvo.lark.channel.LarkChannelClient.TaskHandoff;

final class SynvoLarkStreamCard implements LarkChannelClient.StreamWriter {

	static final String WAITING_TEXT = "Synvo is thinking…";

	private static final String RESPONSE_ELEMENT_ID = "synvo_response";
	private static final int SUMMARY_LIMIT = 80;

	private final CardStreamController controller;
	private final StringBuilder content = new StringBuilder();
	private ActionHandoff actionHandoff;
	private String actionUrl;
	private TaskHandoff taskHandoff;
	private String taskUrl;

	SynvoLarkStreamCard(CardStreamController controller) {
		this.controller = controller;
	}

	static Map<String, Object> initialCard() {
		return cardFor("", null, null, null, null);
	}

	@Override
	public void append(String delta) {
		if (delta == null || delta.isEmpty()) {
			return;
		}
		content.append(delta);
		controller.update(cardFor(
				content.toString(), actionHandoff, actionUrl, taskHandoff, taskUrl));
	}

	@Override
	public void setContent(String replacement) {
		content.setLength(0);
		if (replacement != null) {
			content.append(replacement);
		}
		controller.update(cardFor(
				content.toString(), actionHandoff, actionUrl, taskHandoff, taskUrl));
	}

	@Override
	public void showActionRequired(ActionHandoff handoff, String h5Url) {
		actionHandoff = handoff;
		actionUrl = h5Url;
		controller.update(cardFor(
				content.toString(), actionHandoff, actionUrl, taskHandoff, taskUrl));
	}

	@Override
	public void clearActionRequired() {
		if (actionHandoff == null) {
			return;
		}
		actionHandoff = null;
		actionUrl = null;
		controller.update(cardFor(content.toString(), null, null, taskHandoff, taskUrl));
	}

	@Override
	public void showTaskHandoff(TaskHandoff handoff, String h5Url) {
		taskHandoff = handoff;
		taskUrl = h5Url;
		controller.update(cardFor(
				content.toString(), actionHandoff, actionUrl, taskHandoff, taskUrl));
	}

	private static Map<String, Object> cardFor(
			String answer,
			ActionHandoff handoff,
			String h5Url,
			TaskHandoff taskHandoff,
			String taskUrl) {
		String visibleContent = answer == null || answer.isBlank() ? WAITING_TEXT : answer;
		if (handoff != null) {
			visibleContent = visibleContent + "\n\n" + handoffContent(handoff, h5Url);
		}
		else if (taskHandoff != null) {
			visibleContent = visibleContent + "\n\n" + taskHandoffContent(taskHandoff, taskUrl);
		}

		Map<String, Object> markdown = new LinkedHashMap<>();
		markdown.put("tag", "markdown");
		markdown.put("element_id", RESPONSE_ELEMENT_ID);
		markdown.put("content", visibleContent);

		Map<String, Object> body = new LinkedHashMap<>();
		body.put("elements", List.of(markdown));

		Map<String, Object> summary = new LinkedHashMap<>();
		summary.put("content", summarize(visibleContent));

		Map<String, Object> config = new LinkedHashMap<>();
		config.put("summary", summary);

		Map<String, Object> card = new LinkedHashMap<>();
		card.put("schema", "2.0");
		card.put("config", config);
		card.put("body", body);
		return card;
	}

	private static String taskHandoffContent(TaskHandoff handoff, String h5Url) {
		StringBuilder value = new StringBuilder()
				.append("**Continue this task in H5**\n")
				.append("- Workspace: ").append(markdownText(handoff.workspaceName())).append("\n")
				.append("- Access: ").append(markdownText(handoff.accessMode())).append("\n\n");
		if (h5Url == null || h5Url.isBlank()) {
			return value.append("Open Synvo AI Assistant in H5 to continue this task.").toString();
		}
		return value.append("[Open this task in H5](")
				.append(h5Url)
				.append(")")
				.toString();
	}

	private static String handoffContent(ActionHandoff handoff, String h5Url) {
		StringBuilder value = new StringBuilder()
				.append("**Approval required**\n")
				.append("- Action: ").append(markdownText(handoff.category())).append("\n")
				.append("- Workspace: ").append(markdownText(handoff.workspaceName())).append("\n")
				.append("- Reason: ").append(markdownText(handoff.reason())).append("\n")
				.append("- Permission: ").append(markdownText(handoff.permissionScope())).append("\n\n");
		if (h5Url == null || h5Url.isBlank()) {
			return value.append("Open in H5 to review and approve.").toString();
		}
		return value.append("[Open in H5 to review and approve](")
				.append(h5Url)
				.append(")")
				.toString();
	}

	private static String markdownText(String value) {
		return value
				.replace("\\", "\\\\")
				.replace("*", "\\*")
				.replace("_", "\\_")
				.replace("[", "\\[")
				.replace("]", "\\]")
				.replace("(", "\\(")
				.replace(")", "\\)")
				.replace("`", "\\`")
				.replace("\n", " ")
				.replace("\r", " ");
	}

	private static String summarize(String value) {
		String normalized = value.replaceAll("\\s+", " ").trim();
		return normalized.length() <= SUMMARY_LIMIT
				? normalized
				: normalized.substring(0, SUMMARY_LIMIT - 1) + "…";
	}
}
