package synvo.api;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import synvo.agent.ConversationAlreadyRunningException;
import synvo.agent.ConversationQueries;
import synvo.agent.ConversationQueries.ConversationDetail;
import synvo.agent.ConversationQueries.ConversationSummary;
import synvo.agent.ConversationQueries.RunDescriptor;
import synvo.agent.ConversationRequest;
import synvo.agent.ConversationRunCoordinator;

@RestController
@RequestMapping("/api/conversations")
class ConversationController {

	private final LarkSessionAccess sessionAccess;
	private final ConversationRunCoordinator runCoordinator;
	private final ConversationEventStream eventStream;
	private final ConversationQueries queries;

	ConversationController(
			LarkSessionAccess sessionAccess,
			ConversationRunCoordinator runCoordinator,
			ConversationEventStream eventStream,
			ConversationQueries queries) {
		this.sessionAccess = sessionAccess;
		this.runCoordinator = runCoordinator;
		this.eventStream = eventStream;
		this.queries = queries;
	}

	@GetMapping
	List<ConversationSummary> list(HttpSession session) {
		String ownerOpenId = sessionAccess.require(session).openId();
		return queries.listRecent(ownerOpenId);
	}

	@GetMapping("/{conversationId}")
	ConversationDetail detail(@PathVariable UUID conversationId, HttpSession session) {
		String ownerOpenId = sessionAccess.require(session).openId();
		return queries.findConversation(ownerOpenId, conversationId)
				.orElseThrow(ResourceNotFoundException::new);
	}

	@DeleteMapping("/{conversationId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void delete(@PathVariable UUID conversationId, HttpSession session) {
		String ownerOpenId = sessionAccess.require(session).openId();
		switch (queries.deleteOwnedConversation(ownerOpenId, conversationId)) {
			case DELETED -> {
			}
			case ACTIVE_RUN -> throw new ConversationActiveException();
			case NOT_FOUND -> throw new ResourceNotFoundException();
		}
	}

	@PostMapping("/turns")
	@ResponseStatus(HttpStatus.ACCEPTED)
	ConversationRunCoordinator.Submission submit(
			@Valid @RequestBody TurnSubmission body,
			HttpSession session) {
		String ownerOpenId = sessionAccess.require(session).openId();
		return runCoordinator.submit(new ConversationRequest(
				body.requestId(), body.conversationId(), ownerOpenId, body.content(),
				body.replaceFailedAssistantTurnId(), body.reasoningEffort(), body.skillName()));
	}

	@GetMapping(value = "/runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter events(
			@PathVariable UUID runId,
			@RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
			HttpSession session) {
		String ownerOpenId = sessionAccess.require(session).openId();
		queries.findOwnedRun(ownerOpenId, runId)
				.orElseThrow(ResourceNotFoundException::new);
		return eventStream.subscribe(runId, parseLastSequence(lastEventId));
	}

	@PostMapping("/runs/{runId}/stop")
	StopResponse stop(@PathVariable UUID runId, HttpSession session) {
		String ownerOpenId = sessionAccess.require(session).openId();
		RunDescriptor run = queries.findOwnedRun(ownerOpenId, runId)
				.orElseThrow(ResourceNotFoundException::new);
		return new StopResponse(runCoordinator.stop(runId), run.status().name());
	}

	private static int parseLastSequence(String lastEventId) {
		if (lastEventId == null || lastEventId.isBlank()) {
			return 0;
		}
		try {
			int parsed = Integer.parseInt(lastEventId);
			if (parsed < 0) {
				throw new InvalidConversationRequestException();
			}
			return parsed;
		}
		catch (NumberFormatException invalid) {
			throw new InvalidConversationRequestException();
		}
	}

	@ExceptionHandler(LarkSessionAccess.UnauthorizedSessionException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	ApiError unauthorized() {
		return new ApiError("LARK_AUTHORIZATION_REQUIRED", "Open Synvo inside Lark to authorize.");
	}

	@ExceptionHandler(ResourceNotFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	ApiError notFound() {
		return new ApiError("CONVERSATION_NOT_FOUND", "The conversation is unavailable.");
	}

	@ExceptionHandler(ConversationAlreadyRunningException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	ApiError alreadyRunning() {
		return new ApiError("CONVERSATION_ALREADY_RUNNING", "That request is already running.");
	}

	@ExceptionHandler(ConversationActiveException.class)
	@ResponseStatus(HttpStatus.CONFLICT)
	ApiError activeConversation() {
		return new ApiError("CONVERSATION_ACTIVE", "Stop the active response before deleting this chat.");
	}

	@ExceptionHandler({IllegalArgumentException.class, InvalidConversationRequestException.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	ApiError invalidRequest() {
		return new ApiError("INVALID_CONVERSATION_REQUEST", "The conversation request is invalid.");
	}

	@ExceptionHandler({MethodArgumentNotValidException.class, HttpMessageNotReadableException.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	ApiError invalidPayload() {
		return new ApiError("INVALID_CONVERSATION_REQUEST", "The conversation request is invalid.");
	}

	record TurnSubmission(
			@NotBlank @Size(max = 128) String requestId,
			UUID conversationId,
			@NotBlank @Size(max = 20_000) String content,
			UUID replaceFailedAssistantTurnId,
			@Size(max = 64) String reasoningEffort,
			@Size(max = 200) String skillName
	) {
	}

	record StopResponse(boolean stopped, String status) {
	}

	record ApiError(String code, String message) {
	}

	private static final class ResourceNotFoundException extends RuntimeException {
	}

	private static final class ConversationActiveException extends RuntimeException {
	}

	private static final class InvalidConversationRequestException extends RuntimeException {
	}
}
