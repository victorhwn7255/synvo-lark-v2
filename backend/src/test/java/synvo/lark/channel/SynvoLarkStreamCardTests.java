package synvo.lark.channel;

import com.lark.oapi.channel.model.CardStreamController;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class SynvoLarkStreamCardTests {

	@Test
	void initialCardUsesACompactWaitingStateWithoutAHeader() {
		Map<String, Object> card = SynvoLarkStreamCard.initialCard();

		assertFalse(card.containsKey("header"));
		assertEquals("2.0", card.get("schema"));
		assertEquals(SynvoLarkStreamCard.WAITING_TEXT, markdownContent(card));
		assertEquals(SynvoLarkStreamCard.WAITING_TEXT, summaryContent(card));
	}

	@Test
	void firstDeltaReplacesTheWaitingStateAndFurtherDeltasUpdateTheSameCard() {
		FakeCardController controller = new FakeCardController();
		SynvoLarkStreamCard writer = new SynvoLarkStreamCard(controller);

		writer.append("Hello");
		writer.append(" Victor.");

		assertEquals(2, controller.updates.size());
		assertEquals("Hello", markdownContent(controller.updates.get(0)));
		assertEquals("Hello Victor.", markdownContent(controller.updates.get(1)));
		assertFalse(controller.updates.get(1).containsKey("header"));
	}

	@Test
	void terminalReplacementRemovesPartialContentAndKeepsTheCardHeaderless() {
		FakeCardController controller = new FakeCardController();
		SynvoLarkStreamCard writer = new SynvoLarkStreamCard(controller);

		writer.append("Partial private output");
		writer.setContent("I couldn’t complete that response. Please try again.");

		Map<String, Object> terminal = controller.updates.get(controller.updates.size() - 1);
		assertEquals("I couldn’t complete that response. Please try again.", markdownContent(terminal));
		assertFalse(terminal.containsKey("header"));
	}

	@SuppressWarnings("unchecked")
	private static String markdownContent(Map<String, Object> card) {
		Map<String, Object> body = (Map<String, Object>) card.get("body");
		List<Map<String, Object>> elements = (List<Map<String, Object>>) body.get("elements");
		return (String) elements.get(0).get("content");
	}

	@SuppressWarnings("unchecked")
	private static String summaryContent(Map<String, Object> card) {
		Map<String, Object> config = (Map<String, Object>) card.get("config");
		Map<String, Object> summary = (Map<String, Object>) config.get("summary");
		return (String) summary.get("content");
	}

	private static final class FakeCardController implements CardStreamController {

		private final List<Map<String, Object>> updates = new ArrayList<>();

		@Override
		public void update(Map<String, Object> next) {
			updates.add(next);
		}

		@Override
		public Map<String, Object> getCurrent() {
			return updates.isEmpty() ? Map.of() : updates.get(updates.size() - 1);
		}

		@Override
		public String getMessageId() {
			return "message-id";
		}
	}
}
