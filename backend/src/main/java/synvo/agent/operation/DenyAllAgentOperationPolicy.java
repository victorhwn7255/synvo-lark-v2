package synvo.agent.operation;

import org.springframework.stereotype.Component;

@Component
final class DenyAllAgentOperationPolicy implements AgentOperationPolicy {

	@Override
	public boolean isAllowed(
			AgentOperation operation,
			OperationCall call,
			OperationContext context) {
		return false;
	}
}
