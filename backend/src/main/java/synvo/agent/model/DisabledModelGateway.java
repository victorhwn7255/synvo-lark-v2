package synvo.agent.model;

public final class DisabledModelGateway implements ModelGateway {

	@Override
	public ModelResponse generate(ModelRequest request) {
		throw new ModelGatewayException(ModelGatewayException.Code.UNAVAILABLE);
	}
}
