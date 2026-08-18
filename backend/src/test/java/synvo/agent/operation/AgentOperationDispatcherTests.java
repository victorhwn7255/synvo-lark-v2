package synvo.agent.operation;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentOperationDispatcherTests {

	@Test
	void unregisteredModelOperationCannotBypassTheRegistry() {
		AgentOperationDispatcher dispatcher = new AgentOperationDispatcher(
				new AgentOperationRegistry(List.of()),
				(operation, call, context) -> true);

		assertThrows(AgentOperationRejectedException.class, () -> dispatcher.dispatch(
				new OperationCall("invented.delete_everything", Map.of()),
				new OperationContext("ou-victor", true)));
	}

	@Test
	void policyDenialPreventsARegisteredOperationFromExecuting() {
		AtomicBoolean executed = new AtomicBoolean();
		AgentOperation operation = operation("lark.read", executed);
		AgentOperationDispatcher dispatcher = new AgentOperationDispatcher(
				new AgentOperationRegistry(List.of(operation)),
				(candidate, call, context) -> false);

		assertThrows(AgentOperationRejectedException.class, () -> dispatcher.dispatch(
				new OperationCall("lark.read", Map.of("resource", "doc")),
				new OperationContext("ou-victor", false)));
		assertFalse(executed.get());
	}

	@Test
	void onlyRegisteredAndPolicyApprovedOperationsExecute() {
		AtomicBoolean executed = new AtomicBoolean();
		AgentOperation operation = operation("lark.read", executed);
		AgentOperationRegistry registry = new AgentOperationRegistry(List.of(operation));
		AgentOperationDispatcher dispatcher = new AgentOperationDispatcher(
				registry,
				(candidate, call, context) -> true);

		dispatcher.dispatch(
				new OperationCall("lark.read", Map.of("resource", "doc")),
				new OperationContext("ou-victor", false));

		assertTrue(registry.isRegistered("lark.read"));
		assertTrue(executed.get());
	}

	private static AgentOperation operation(String name, AtomicBoolean executed) {
		return new AgentOperation() {
			@Override
			public String name() {
				return name;
			}

			@Override
			public OperationResult execute(OperationCall call, OperationContext context) {
				executed.set(true);
				return new OperationResult(Map.of("status", "ok"));
			}
		};
	}
}
