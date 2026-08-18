package synvo.agent.operation;

import java.util.Map;

public record OperationResult(Map<String, Object> values) {

	public OperationResult {
		values = Map.copyOf(values);
	}
}
