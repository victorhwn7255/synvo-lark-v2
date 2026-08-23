package synvo.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import synvo.workspaceagent.WorkspaceAgentRepository;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
final class WorkspaceAgentRecovery implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(WorkspaceAgentRecovery.class);

	private final WorkspaceAgentRepository repository;

	WorkspaceAgentRecovery(WorkspaceAgentRepository repository) {
		this.repository = repository;
	}

	@Override
	public void run(ApplicationArguments args) {
		int recovered = repository.recoverInterruptedOperations();
		if (recovered > 0) {
			log.info("Recovered {} interrupted workspace-agent operation(s)", recovered);
		}
	}
}
