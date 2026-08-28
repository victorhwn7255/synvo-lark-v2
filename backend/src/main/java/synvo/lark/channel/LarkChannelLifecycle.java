package synvo.lark.channel;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "synvo.lark", name = "enabled", havingValue = "true")
final class LarkChannelLifecycle {

	private static final Logger log = LoggerFactory.getLogger(LarkChannelLifecycle.class);
	private static final Duration RECONNECT_STALL_TIMEOUT = Duration.ofSeconds(30);
	private static final Duration RECOVERY_INITIAL_DELAY = Duration.ofSeconds(5);
	private static final Duration RECOVERY_MAX_DELAY = Duration.ofMinutes(1);

	private final LarkChannelClient channelClient;
	private final LarkDirectMessageHandler messageHandler;
	private final LarkConnectionStatus connectionStatus;
	private final ScheduledExecutorService recoveryScheduler;
	private final Duration reconnectStallTimeout;
	private final Duration recoveryInitialDelay;
	private final Duration recoveryMaxDelay;
	private final Object recoveryMonitor = new Object();

	private volatile boolean shuttingDown;
	private ScheduledFuture<?> recoveryTask;
	private boolean recovering;
	private int recoveryAttempt;

	@Autowired
	LarkChannelLifecycle(
			LarkChannelClient channelClient,
			LarkDirectMessageHandler messageHandler,
			LarkConnectionStatus connectionStatus) {
		this(
				channelClient,
				messageHandler,
				connectionStatus,
				newRecoveryScheduler(),
				RECONNECT_STALL_TIMEOUT,
				RECOVERY_INITIAL_DELAY,
				RECOVERY_MAX_DELAY);
	}

	LarkChannelLifecycle(
			LarkChannelClient channelClient,
			LarkDirectMessageHandler messageHandler,
			LarkConnectionStatus connectionStatus,
			ScheduledExecutorService recoveryScheduler,
			Duration reconnectStallTimeout) {
		this(
				channelClient,
				messageHandler,
				connectionStatus,
				recoveryScheduler,
				reconnectStallTimeout,
				RECOVERY_INITIAL_DELAY,
				RECOVERY_MAX_DELAY);
	}

	LarkChannelLifecycle(
			LarkChannelClient channelClient,
			LarkDirectMessageHandler messageHandler,
			LarkConnectionStatus connectionStatus,
			ScheduledExecutorService recoveryScheduler,
			Duration reconnectStallTimeout,
			Duration recoveryInitialDelay,
			Duration recoveryMaxDelay) {
		this.channelClient = channelClient;
		this.messageHandler = messageHandler;
		this.connectionStatus = connectionStatus;
		this.recoveryScheduler = recoveryScheduler;
		this.reconnectStallTimeout = reconnectStallTimeout;
		this.recoveryInitialDelay = recoveryInitialDelay;
		this.recoveryMaxDelay = recoveryMaxDelay;
	}

	@EventListener(ApplicationReadyEvent.class)
	void connect() {
		channelClient.onMessage(messageHandler::handle);
		channelClient.onSignal(this::handleSignal);
		transition(LarkConnectionState.CONNECTING);
		channelClient.connect().whenComplete((botProfile, failure) -> {
			if (shuttingDown) {
				return;
			}
			if (failure != null) {
				transition(LarkConnectionState.FAILED);
				log.warn("Lark WebSocket connection failed ({})", failureType(failure));
				scheduleFailedRecovery();
				return;
			}
			messageHandler.setBotOpenId(botProfile.openId());
			resetRecovery();
			transition(LarkConnectionState.CONNECTED);
		});
	}

	@PreDestroy
	void disconnect() {
		shuttingDown = true;
		cancelRecovery();
		recoveryScheduler.shutdownNow();
		try {
			channelClient.disconnect().join();
		}
		catch (CompletionException exception) {
			log.warn("Lark WebSocket shutdown did not complete cleanly ({})", failureType(exception));
		}
		finally {
			transition(LarkConnectionState.DISABLED);
		}
	}

	private void handleSignal(LarkChannelClient.Signal signal) {
		switch (signal) {
			case RECONNECTING -> {
				transition(LarkConnectionState.RECONNECTING);
				scheduleRecovery();
			}
			case RECONNECTED -> {
				resetRecovery();
				transition(LarkConnectionState.CONNECTED);
			}
			case ERROR -> {
				cancelRecovery();
				transition(LarkConnectionState.FAILED);
				log.warn("The Lark channel reported an error");
				scheduleFailedRecovery();
			}
		}
	}

	private void scheduleRecovery() {
		scheduleRecovery(reconnectStallTimeout);
	}

	private void scheduleRecovery(Duration delay) {
		synchronized (recoveryMonitor) {
			if (shuttingDown || recovering || recoveryTask != null) {
				return;
			}
			recoveryTask = recoveryScheduler.schedule(
					this::recoverIfStalled,
					delay.toMillis(),
					TimeUnit.MILLISECONDS);
		}
	}

	private void scheduleFailedRecovery() {
		synchronized (recoveryMonitor) {
			if (shuttingDown || recovering || recoveryTask != null) {
				return;
			}
			long multiplier = 1L << Math.min(recoveryAttempt, 6);
			long delayMillis = Math.min(
					recoveryInitialDelay.toMillis() * multiplier,
					recoveryMaxDelay.toMillis());
			recoveryAttempt++;
			recoveryTask = recoveryScheduler.schedule(
					this::recoverIfStalled,
					delayMillis,
					TimeUnit.MILLISECONDS);
		}
	}

	void recoverIfStalled() {
		synchronized (recoveryMonitor) {
			recoveryTask = null;
			if (shuttingDown
					|| recovering
					|| (connectionStatus.snapshot().state() != LarkConnectionState.RECONNECTING
							&& connectionStatus.snapshot().state() != LarkConnectionState.FAILED)) {
				return;
			}
			recovering = true;
		}

		log.warn("Lark WebSocket reconnect stalled; replacing the channel transport");
		transition(LarkConnectionState.RECONNECTING);
		channelClient.restart().whenComplete((botProfile, failure) -> {
			synchronized (recoveryMonitor) {
				recovering = false;
			}
			if (shuttingDown) {
				return;
			}
			if (failure != null) {
				transition(LarkConnectionState.FAILED);
				log.warn("Lark WebSocket recovery failed ({})", failureType(failure));
				scheduleFailedRecovery();
				return;
			}
			messageHandler.setBotOpenId(botProfile.openId());
			resetRecovery();
			transition(LarkConnectionState.CONNECTED);
		});
	}

	private void cancelRecovery() {
		synchronized (recoveryMonitor) {
			if (recoveryTask != null) {
				recoveryTask.cancel(false);
				recoveryTask = null;
			}
		}
	}

	private void resetRecovery() {
		synchronized (recoveryMonitor) {
			cancelRecovery();
			recoveryAttempt = 0;
		}
	}

	private void transition(LarkConnectionState state) {
		connectionStatus.transitionTo(state);
		log.info("Lark connection state changed to {}", state);
	}

	private static String failureType(Throwable failure) {
		Throwable cause = failure instanceof CompletionException && failure.getCause() != null
				? failure.getCause()
				: failure;
		return cause.getClass().getSimpleName();
	}

	private static ScheduledExecutorService newRecoveryScheduler() {
		return Executors.newSingleThreadScheduledExecutor(runnable -> {
			Thread thread = new Thread(runnable, "lark-channel-recovery");
			thread.setDaemon(true);
			return thread;
		});
	}
}
