package synvo.agent.operation;

public interface AgentOperation {

	String name();

	OperationResult execute(OperationCall call, OperationContext context);
}
