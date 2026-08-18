package synvo.agent.operation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class AgentOperationRegistry {

	private final Map<String, AgentOperation> operations;

	public AgentOperationRegistry(List<AgentOperation> operations) {
		Map<String, AgentOperation> indexed = new HashMap<>();
		for (AgentOperation operation : operations) {
			if (indexed.putIfAbsent(operation.name(), operation) != null) {
				throw new IllegalStateException("Duplicate agent operation registration");
			}
		}
		this.operations = Map.copyOf(indexed);
	}

	AgentOperation requireRegistered(String name) {
		AgentOperation operation = operations.get(name);
		if (operation == null) {
			throw new AgentOperationRejectedException("Operation is not registered");
		}
		return operation;
	}

	public boolean isRegistered(String name) {
		return operations.containsKey(name);
	}
}
