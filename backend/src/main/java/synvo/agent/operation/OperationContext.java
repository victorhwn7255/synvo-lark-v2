package synvo.agent.operation;

import org.springframework.util.StringUtils;

public record OperationContext(String userOpenId, boolean confirmed) {

	public OperationContext {
		if (!StringUtils.hasText(userOpenId)) {
			throw new IllegalArgumentException("Operation user identity is required");
		}
	}
}
