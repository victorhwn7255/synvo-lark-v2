package synvo.agent;

import java.util.UUID;

@FunctionalInterface
public interface AgentEventPublisher {

	void publish(UUID runId, AgentLifecycleEvent event);
}
