package synvo.agent.operation;

public interface AgentOperationPolicy {

	boolean isAllowed(AgentOperation operation, OperationCall call, OperationContext context);
}
