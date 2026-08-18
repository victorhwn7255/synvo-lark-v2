package synvo.agent.model;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.openai.OpenAiChatModel;
import reactor.core.publisher.Flux;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NemotronModelGatewayTests {

	@Test
	void outputConsumerFailureIsNotMisclassifiedAsProviderFailure() {
		OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
		when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.just(response("model delta")));
		NemotronModelGateway gateway = new NemotronModelGateway(chatModel);
		IllegalStateException storageFailure = new IllegalStateException("storage unavailable");

		IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> gateway.stream(
				request(),
				delta -> {
					throw storageFailure;
				},
				ModelGateway.ModelCancellation.none()));

		assertSame(storageFailure, thrown);
	}

	@Test
	void providerStreamFailureRemainsAProviderFailure() {
		OpenAiChatModel chatModel = mock(OpenAiChatModel.class);
		IllegalStateException transportFailure = new IllegalStateException("stream closed");
		when(chatModel.stream(any(Prompt.class))).thenReturn(Flux.error(transportFailure));
		NemotronModelGateway gateway = new NemotronModelGateway(chatModel);

		ModelGatewayException thrown = assertThrows(ModelGatewayException.class, () -> gateway.stream(
				request(), ignored -> { }, ModelGateway.ModelCancellation.none()));

		assertEquals(ModelGatewayException.Code.PROVIDER_FAILURE, thrown.code());
		assertSame(transportFailure, thrown.getCause());
	}

	private static ModelGateway.ModelRequest request() {
		return new ModelGateway.ModelRequest(List.of(
				new ModelGateway.ModelMessage(ModelGateway.Role.USER, "Safe test prompt")));
	}

	private static ChatResponse response(String content) {
		return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
	}
}
