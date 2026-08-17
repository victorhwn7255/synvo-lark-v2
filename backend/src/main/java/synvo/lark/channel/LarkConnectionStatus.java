package synvo.lark.channel;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import synvo.configuration.LarkProperties;

@Component
public class LarkConnectionStatus {

	private final AtomicReference<Snapshot> snapshot;

	public LarkConnectionStatus(LarkProperties properties) {
		LarkConnectionState initialState = properties.enabled()
				? LarkConnectionState.CONNECTING
				: LarkConnectionState.DISABLED;
		this.snapshot = new AtomicReference<>(new Snapshot(initialState, Instant.now()));
	}

	public Snapshot snapshot() {
		return snapshot.get();
	}

	void transitionTo(LarkConnectionState state) {
		snapshot.set(new Snapshot(state, Instant.now()));
	}

	public record Snapshot(LarkConnectionState state, Instant changedAt) {
	}
}
