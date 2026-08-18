package synvo.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import synvo.agent.model.ModelGateway.ModelCancellation;

public final class AgentCancellation implements ModelCancellation {

	private final List<Runnable> callbacks = new ArrayList<>();
	private boolean cancelled;
	private boolean timedOut;

	@Override
	public synchronized boolean isCancelled() {
		return cancelled;
	}

	@Override
	public synchronized boolean timedOut() {
		return timedOut;
	}

	@Override
	public void onCancel(Runnable callback) {
		Objects.requireNonNull(callback, "callback");
		synchronized (this) {
			if (!cancelled) {
				callbacks.add(callback);
				return;
			}
		}
		callback.run();
	}

	public boolean cancel() {
		return cancel(false);
	}

	public boolean timeout() {
		return cancel(true);
	}

	private boolean cancel(boolean timeout) {
		List<Runnable> callbacksToRun;
		synchronized (this) {
			if (cancelled) {
				return false;
			}
			cancelled = true;
			timedOut = timeout;
			callbacksToRun = List.copyOf(callbacks);
			callbacks.clear();
		}
		for (Runnable callback : callbacksToRun) {
			callback.run();
		}
		return true;
	}
}
