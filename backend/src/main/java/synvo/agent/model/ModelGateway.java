package synvo.agent.model;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.util.StringUtils;

public interface ModelGateway {

	ModelResponse generate(ModelRequest request);

	default ModelResponse stream(
			ModelRequest request,
			Consumer<String> onDelta,
			ModelCancellation cancellation) {
		Objects.requireNonNull(onDelta, "onDelta");
		Objects.requireNonNull(cancellation, "cancellation");
		throwIfCancelled(cancellation);
		ModelResponse response = generate(request);
		throwIfCancelled(cancellation);
		onDelta.accept(response.content());
		return response;
	}

	private static void throwIfCancelled(ModelCancellation cancellation) {
		if (cancellation.isCancelled()) {
			throw new ModelGatewayException(cancellation.timedOut()
					? ModelGatewayException.Code.TIMEOUT
					: ModelGatewayException.Code.CANCELLED);
		}
	}

	interface ModelCancellation {

		boolean isCancelled();

		boolean timedOut();

		void onCancel(Runnable callback);

		static ModelCancellation none() {
			return NoCancellation.INSTANCE;
		}
	}

	enum NoCancellation implements ModelCancellation {
		INSTANCE;

		@Override
		public boolean isCancelled() {
			return false;
		}

		@Override
		public boolean timedOut() {
			return false;
		}

		@Override
		public void onCancel(Runnable callback) {
			Objects.requireNonNull(callback, "callback");
		}
	}

	record ModelRequest(List<ModelMessage> messages) {

		public ModelRequest {
			messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
			if (messages.isEmpty()) {
				throw new IllegalArgumentException("At least one model message is required");
			}
		}
	}

	record ModelMessage(Role role, String content) {

		public ModelMessage {
			Objects.requireNonNull(role, "role");
			if (!StringUtils.hasText(content)) {
				throw new IllegalArgumentException("Model message content is required");
			}
		}
	}

	record ModelResponse(String content) {

		public ModelResponse {
			if (!StringUtils.hasText(content)) {
				throw new IllegalArgumentException("Model response content is required");
			}
		}
	}

	enum Role {
		SYSTEM,
		USER,
		ASSISTANT
	}
}
