package synvo.agent.operation;

import org.springframework.stereotype.Component;

@Component
public final class AgentOperationDispatcher {

	private final AgentOperationRegistry registry;
	private final AgentOperationPolicy policy;

	public AgentOperationDispatcher(
			AgentOperationRegistry registry,
			AgentOperationPolicy policy) {
		this.registry = registry;
		this.policy = policy;
	}

	public OperationResult dispatch(OperationCall call, OperationContext context) {
		AgentOperation operation = registry.requireRegistered(call.name());
		if (!policy.isAllowed(operation, call, context)) {
			throw new AgentOperationRejectedException("Operation is not allowed");
		}
		return operation.execute(call, context);
	}
}
