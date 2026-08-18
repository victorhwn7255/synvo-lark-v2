package synvo.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class ConversationRunRecovery implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(ConversationRunRecovery.class);
	private static final String SAFE_RESTART_RESPONSE =
			"The response was interrupted by a restart. Please retry.";

	private final JdbcConversationStore conversationStore;

	ConversationRunRecovery(JdbcConversationStore conversationStore) {
		this.conversationStore = conversationStore;
	}

	@Override
	public void run(ApplicationArguments args) {
		int recovered = conversationStore.recoverInterruptedRuns(SAFE_RESTART_RESPONSE);
		if (recovered > 0) {
			log.info("Recovered {} interrupted agent run(s) into a safe terminal state", recovered);
		}
	}
}
