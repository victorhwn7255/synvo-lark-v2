package synvo.lark.channel;

import com.lark.oapi.channel.model.CardStreamController;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import synvo.agent.AgentLifecycleEvent.ActionHandoff;

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

	@Test
	void interactionHandoffShowsOnlySafeSummaryAndAnOwningH5Link() {
		FakeCardController controller = new FakeCardController();
		SynvoLarkStreamCard writer = new SynvoLarkStreamCard(controller);
		ActionHandoff handoff = new ActionHandoff(
				java.util.UUID.randomUUID(),
				java.util.UUID.randomUUID(),
				"shell command",
				"Pilot workspace",
				"Run focused tests",
				"workspace command");

		writer.showActionRequired(handoff, "https://synvo.example/h5?codexTask=safe");

		String content = markdownContent(controller.updates.getLast());
		org.junit.jupiter.api.Assertions.assertTrue(content.contains("Approval required"));
		org.junit.jupiter.api.Assertions.assertTrue(content.contains("shell command"));
		org.junit.jupiter.api.Assertions.assertTrue(content.contains("Pilot workspace"));
		org.junit.jupiter.api.Assertions.assertTrue(content.contains("Run focused tests"));
		org.junit.jupiter.api.Assertions.assertTrue(content.contains("workspace command"));
		org.junit.jupiter.api.Assertions.assertTrue(content.contains("Open in H5"));
		writer.clearActionRequired();
		org.junit.jupiter.api.Assertions.assertFalse(
				markdownContent(controller.updates.getLast()).contains("Approval required"));
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
