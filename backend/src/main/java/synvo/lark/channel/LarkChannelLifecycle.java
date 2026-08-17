package synvo.lark.channel;

import jakarta.annotation.PreDestroy;
import java.util.concurrent.CompletionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "synvo.lark", name = "enabled", havingValue = "true")
final class LarkChannelLifecycle {

	private static final Logger log = LoggerFactory.getLogger(LarkChannelLifecycle.class);

	private final LarkChannelClient channelClient;
	private final LarkDirectMessageHandler messageHandler;
	private final LarkConnectionStatus connectionStatus;

	private volatile boolean shuttingDown;

	LarkChannelLifecycle(
			LarkChannelClient channelClient,
			LarkDirectMessageHandler messageHandler,
			LarkConnectionStatus connectionStatus) {
		this.channelClient = channelClient;
		this.messageHandler = messageHandler;
		this.connectionStatus = connectionStatus;
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
				return;
			}
			messageHandler.setBotOpenId(botProfile.openId());
			transition(LarkConnectionState.CONNECTED);
		});
	}

	@PreDestroy
	void disconnect() {
		shuttingDown = true;
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
			case RECONNECTING -> transition(LarkConnectionState.RECONNECTING);
			case RECONNECTED -> transition(LarkConnectionState.CONNECTED);
			case ERROR -> {
				transition(LarkConnectionState.FAILED);
				log.warn("The Lark channel reported an error");
			}
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
}
