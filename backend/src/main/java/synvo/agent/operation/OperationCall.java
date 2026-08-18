package synvo.agent.operation;

import java.util.Map;
import org.springframework.util.StringUtils;

public record OperationCall(String name, Map<String, Object> arguments) {

	public OperationCall {
		if (!StringUtils.hasText(name)) {
			throw new IllegalArgumentException("Operation name is required");
		}
		arguments = Map.copyOf(arguments);
	}
}
