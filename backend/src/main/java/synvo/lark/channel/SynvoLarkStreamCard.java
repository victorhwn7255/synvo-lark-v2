package synvo.lark.channel;

import com.lark.oapi.channel.model.CardStreamController;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SynvoLarkStreamCard implements LarkChannelClient.StreamWriter {

	static final String WAITING_TEXT = "Synvo is thinking…";

	private static final String RESPONSE_ELEMENT_ID = "synvo_response";
	private static final int SUMMARY_LIMIT = 80;

	private final CardStreamController controller;
	private final StringBuilder content = new StringBuilder();

	SynvoLarkStreamCard(CardStreamController controller) {
		this.controller = controller;
	}

	static Map<String, Object> initialCard() {
		return cardFor("");
	}

	@Override
	public void append(String delta) {
		if (delta == null || delta.isEmpty()) {
			return;
		}
		content.append(delta);
		controller.update(cardFor(content.toString()));
	}

	@Override
	public void setContent(String replacement) {
		content.setLength(0);
		if (replacement != null) {
			content.append(replacement);
		}
		controller.update(cardFor(content.toString()));
	}

	private static Map<String, Object> cardFor(String answer) {
		String visibleContent = answer == null || answer.isBlank() ? WAITING_TEXT : answer;

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

	private static String summarize(String value) {
		String normalized = value.replaceAll("\\s+", " ").trim();
		return normalized.length() <= SUMMARY_LIMIT
				? normalized
				: normalized.substring(0, SUMMARY_LIMIT - 1) + "…";
	}
}
