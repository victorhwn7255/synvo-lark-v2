package synvo.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import synvo.agent.model.ModelGateway;
import synvo.agent.model.ModelGatewayException;

final class FakeModelGateway implements ModelGateway {

	private final String response;
	private final List<String> deltas;
	private final ModelGatewayException failure;
	private final List<ModelRequest> requests = new ArrayList<>();

	private FakeModelGateway(String response, List<String> deltas, ModelGatewayException failure) {
		this.response = response;
		this.deltas = deltas;
		this.failure = failure;
	}

	static FakeModelGateway responding(String response) {
		return new FakeModelGateway(response, List.of(response), null);
	}

	static FakeModelGateway streaming(String... deltas) {
		return new FakeModelGateway(String.join("", deltas), List.of(deltas), null);
	}

	static FakeModelGateway failing(ModelGatewayException.Code code) {
		return new FakeModelGateway(null, List.of(), new ModelGatewayException(code));
	}

	@Override
	public ModelResponse generate(ModelRequest request) {
		requests.add(request);
		if (failure != null) {
			throw failure;
		}
		return new ModelResponse(response);
	}

	@Override
	public ModelResponse stream(
			ModelRequest request,
			Consumer<String> onDelta,
			ModelCancellation cancellation) {
		requests.add(request);
		if (failure != null) {
			throw failure;
		}
		for (String delta : deltas) {
			if (cancellation.isCancelled()) {
				throw new ModelGatewayException(cancellation.timedOut()
						? ModelGatewayException.Code.TIMEOUT
						: ModelGatewayException.Code.CANCELLED);
			}
			onDelta.accept(delta);
		}
		return new ModelResponse(response);
	}

	List<ModelRequest> requests() {
		return List.copyOf(requests);
	}
}
