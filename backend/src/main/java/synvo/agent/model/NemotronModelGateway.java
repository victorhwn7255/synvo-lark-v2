package synvo.agent.model;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.util.StringUtils;
import reactor.core.publisher.Sinks;

public final class NemotronModelGateway implements ModelGateway {

	private static final Logger log = LoggerFactory.getLogger(NemotronModelGateway.class);

	private final OpenAiChatModel chatModel;

	public NemotronModelGateway(OpenAiChatModel chatModel) {
		this.chatModel = chatModel;
	}

	@Override
	public ModelResponse generate(ModelRequest request) {
		try {
			List<Message> messages = request.messages().stream()
					.map(NemotronModelGateway::toSpringMessage)
					.toList();
			ChatResponse response = chatModel.call(new Prompt(messages));
			String content = response.getResult().getOutput().getText();
			if (!StringUtils.hasText(content)) {
				throw new ModelGatewayException(ModelGatewayException.Code.INVALID_RESPONSE);
			}
			return new ModelResponse(content.strip());
		}
		catch (ModelGatewayException failure) {
			throw failure;
		}
		catch (RuntimeException failure) {
			log.warn("Nemotron model request failed ({})", failureTypes(failure));
			throw new ModelGatewayException(ModelGatewayException.Code.PROVIDER_FAILURE, failure);
		}
	}

	@Override
	public ModelResponse stream(
			ModelRequest request,
			Consumer<String> onDelta,
			ModelCancellation cancellation) {
		Objects.requireNonNull(onDelta, "onDelta");
		Objects.requireNonNull(cancellation, "cancellation");
		throwIfCancelled(cancellation);

		List<Message> messages = request.messages().stream()
				.map(NemotronModelGateway::toSpringMessage)
				.toList();
		StringBuilder accumulated = new StringBuilder();
		AtomicBoolean completed = new AtomicBoolean();
		Sinks.One<Void> cancellationSignal = Sinks.one();
		cancellation.onCancel(() -> cancellationSignal.tryEmitEmpty());

		try {
			chatModel.stream(new Prompt(messages))
					.takeUntilOther(cancellationSignal.asMono())
					.doOnNext(response -> appendResponseDelta(response, accumulated, onDelta))
					.doOnComplete(() -> completed.set(true))
					.then()
					.block();
			throwIfCancelled(cancellation);
			if (!completed.get() || !StringUtils.hasText(accumulated)) {
				throw new ModelGatewayException(ModelGatewayException.Code.INVALID_RESPONSE);
			}
			return new ModelResponse(accumulated.toString().strip());
		}
		catch (ModelGatewayException failure) {
			throw failure;
		}
		catch (RuntimeException failure) {
			throwIfCancelled(cancellation);
			RuntimeException consumerFailure = findOutputConsumerFailure(failure);
			if (consumerFailure != null) {
				throw consumerFailure;
			}
			log.warn("Nemotron model stream failed ({})", failureTypes(failure));
			throw new ModelGatewayException(ModelGatewayException.Code.PROVIDER_FAILURE, failure);
		}
	}

	private static void appendResponseDelta(
			ChatResponse response,
			StringBuilder accumulated,
			Consumer<String> onDelta) {
		if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
			return;
		}
		String next = response.getResult().getOutput().getText();
		if (!StringUtils.hasLength(next)) {
			return;
		}
		String delta = next.startsWith(accumulated.toString())
				? next.substring(accumulated.length())
				: next;
		if (!delta.isEmpty()) {
			accumulated.append(delta);
			try {
				onDelta.accept(delta);
			}
			catch (RuntimeException failure) {
				throw new OutputConsumerFailure(failure);
			}
		}
	}

	private static RuntimeException findOutputConsumerFailure(Throwable failure) {
		Throwable current = failure;
		while (current != null) {
			if (current instanceof OutputConsumerFailure consumerFailure) {
				return consumerFailure.originalFailure();
			}
			current = current.getCause();
		}
		return null;
	}

	private static String failureTypes(Throwable failure) {
		StringBuilder types = new StringBuilder();
		Throwable current = failure;
		for (int depth = 0; current != null && depth < 6; depth++) {
			if (!types.isEmpty()) {
				types.append('>');
			}
			types.append(current.getClass().getSimpleName());
			current = current.getCause();
		}
		return types.toString();
	}

	private static void throwIfCancelled(ModelCancellation cancellation) {
		if (cancellation.isCancelled()) {
			throw new ModelGatewayException(cancellation.timedOut()
					? ModelGatewayException.Code.TIMEOUT
					: ModelGatewayException.Code.CANCELLED);
		}
	}

	private static Message toSpringMessage(ModelMessage message) {
		return switch (message.role()) {
			case SYSTEM -> new SystemMessage(message.content());
			case USER -> new UserMessage(message.content());
			case ASSISTANT -> new AssistantMessage(message.content());
		};
	}

	private static final class OutputConsumerFailure extends RuntimeException {

		private final RuntimeException originalFailure;

		private OutputConsumerFailure(RuntimeException originalFailure) {
			super(originalFailure);
			this.originalFailure = originalFailure;
		}

		private RuntimeException originalFailure() {
			return originalFailure;
		}
	}
}
