package synvo.api;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDecision;
import synvo.workspaceagent.WorkspaceAgentEngine.GoalCommand;
import synvo.workspaceagent.WorkspaceAgentEngine.ReviewKind;
import synvo.workspaceagent.WorkspaceAgentEngine.ReviewTarget;
import synvo.workspaceagent.WorkspaceAgentEngine.RunMode;
import synvo.workspaceagent.WorkspaceAgentException;
import synvo.workspaceagent.WorkspaceAgentFacade;
import synvo.workspaceagent.WorkspaceAgentFacade.InteractionView;
import synvo.workspaceagent.WorkspaceAgentFacade.OperationView;
import synvo.workspaceagent.WorkspaceAgentFacade.TaskDetail;
import synvo.workspaceagent.WorkspaceAgentFacade.TaskView;
import synvo.workspaceagent.WorkspaceRegistry.WorkspaceSummary;

/** H5 adapter for the Synvo-owned workspace-agent application boundary. */
@RestController
@RequestMapping("/api/codex")
class WorkspaceAgentController {

	private final LarkSessionAccess sessionAccess;
	private final WorkspaceAgentFacade facade;
	private final WorkspaceAgentEventStream eventStream;

	WorkspaceAgentController(
			LarkSessionAccess sessionAccess,
			WorkspaceAgentFacade facade,
			WorkspaceAgentEventStream eventStream) {
		this.sessionAccess = sessionAccess;
		this.facade = facade;
		this.eventStream = eventStream;
	}

	@GetMapping("/status")
	WorkspaceAgentFacade.StatusView status(HttpSession session) {
		return facade.status(owner(session));
	}

	@GetMapping("/workspaces")
	List<WorkspaceSummary> workspaces(HttpSession session) {
		return facade.listWorkspaces(owner(session));
	}

	@GetMapping("/tasks")
	List<TaskView> tasks(
			@RequestParam(defaultValue = "false") boolean archived,
			@RequestParam(required = false) String search,
			HttpSession session) {
		return facade.listTasks(owner(session), archived, search);
	}

	@PostMapping("/tasks")
	@ResponseStatus(HttpStatus.CREATED)
	TaskView createTask(
			@Valid @RequestBody CreateTask body,
			HttpSession session) {
		return facade.createTask(owner(session), body.workspaceId(), body.mode(), body.title());
	}

	@GetMapping("/tasks/{taskId}")
	TaskDetail task(@PathVariable UUID taskId, HttpSession session) {
		return facade.task(owner(session), taskId);
	}

	@PostMapping("/tasks/{taskId}/fork")
	@ResponseStatus(HttpStatus.CREATED)
	TaskView fork(
			@PathVariable UUID taskId,
			@Valid @RequestBody TaskTitle body,
			HttpSession session) {
		return facade.forkTask(owner(session), taskId, body.title());
	}

	@PostMapping("/tasks/{taskId}/rename")
	TaskView rename(
			@PathVariable UUID taskId,
			@Valid @RequestBody TaskTitle body,
			HttpSession session) {
		return facade.renameTask(owner(session), taskId, body.title());
	}

	@PostMapping("/tasks/{taskId}/pin")
	TaskView pin(
			@PathVariable UUID taskId,
			@RequestBody Toggle body,
			HttpSession session) {
		return facade.pinTask(owner(session), taskId, body.enabled());
	}

	@PostMapping("/tasks/{taskId}/archive")
	TaskView archive(
			@PathVariable UUID taskId,
			@RequestBody Toggle body,
			HttpSession session) {
		return facade.archiveTask(owner(session), taskId, body.enabled());
	}

	@PostMapping("/tasks/{taskId}/mode")
	TaskView mode(
			@PathVariable UUID taskId,
			@Valid @RequestBody TaskMode body,
			HttpSession session) {
		return facade.changeMode(owner(session), taskId, body.mode());
	}

	@DeleteMapping("/tasks/{taskId}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void deleteTask(@PathVariable UUID taskId, HttpSession session) {
		facade.deleteTask(owner(session), taskId);
	}

	@GetMapping(value = "/operations/{operationId}/events",
			produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	SseEmitter events(
			@PathVariable UUID operationId,
			@RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
			HttpServletResponse response,
			HttpSession session) {
		String ownerOpenId = owner(session);
		long afterSequence = parseLastSequence(lastEventId);
		response.setHeader("Cache-Control", "no-store");
		response.setHeader("X-Accel-Buffering", "no");
		return eventStream.subscribe(
				operationId,
				afterSequence,
				() -> facade.activity(ownerOpenId, operationId, afterSequence));
	}

	@PostMapping("/operations/{operationId}/stop")
	StopResult stop(@PathVariable UUID operationId, HttpSession session) {
		return new StopResult(facade.stop(owner(session), operationId));
	}

	@PostMapping("/operations/{operationId}/steer")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void steer(
			@PathVariable UUID operationId,
			@Valid @RequestBody Steering body,
			HttpSession session) {
		facade.steer(owner(session), operationId, body.content());
	}

	@GetMapping("/tasks/{taskId}/interactions")
	List<InteractionView> interactions(@PathVariable UUID taskId, HttpSession session) {
		return facade.pendingInteractions(owner(session), taskId);
	}

	@GetMapping("/interactions/{interactionId}")
	ResponseEntity<InteractionView> interaction(
			@PathVariable UUID interactionId,
			HttpSession session) {
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(facade.interactionDetail(owner(session), interactionId));
	}

	@PostMapping("/interactions/{interactionId}/decision")
	ResponseEntity<InteractionView> decide(
			@PathVariable UUID interactionId,
			@Valid @RequestBody InteractionDecisionBody body,
			HttpSession session) {
		return ResponseEntity.ok()
				.cacheControl(CacheControl.noStore())
				.body(facade.decideInteraction(
						owner(session), interactionId, body.decision(), body.formValues()));
	}

	@GetMapping("/tasks/{taskId}/inventory")
	WorkspaceAgentEngineView inventory(@PathVariable UUID taskId, HttpSession session) {
		var inventory = facade.inventory(owner(session), taskId);
		return new WorkspaceAgentEngineView(inventory.skills(), inventory.mcpServers());
	}

	@GetMapping("/tasks/{taskId}/goal")
	GoalView goal(@PathVariable UUID taskId, HttpSession session) {
		return new GoalView(facade.goal(owner(session), taskId).orElse(null));
	}

	@PutMapping("/tasks/{taskId}/goal")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void setGoal(
			@PathVariable UUID taskId,
			@Valid @RequestBody GoalBody body,
			HttpSession session) {
		facade.setGoal(owner(session), taskId, body.objective(), body.command());
	}

	@DeleteMapping("/tasks/{taskId}/goal")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	void clearGoal(@PathVariable UUID taskId, HttpSession session) {
		facade.clearGoal(owner(session), taskId);
	}

	@PostMapping("/tasks/{taskId}/reviews")
	@ResponseStatus(HttpStatus.ACCEPTED)
	OperationView review(
			@PathVariable UUID taskId,
			@Valid @RequestBody ReviewBody body,
			HttpSession session) {
		return facade.startReview(
				owner(session), taskId, new ReviewTarget(body.kind(), body.value()));
	}

	private String owner(HttpSession session) {
		return sessionAccess.require(session).openId();
	}

	private static long parseLastSequence(String value) {
		if (value == null || value.isBlank()) {
			return -1;
		}
		try {
			long parsed = Long.parseLong(value);
			if (parsed < -1) {
				throw new InvalidWorkspaceAgentRequestException();
			}
			return parsed;
		}
		catch (NumberFormatException failure) {
			throw new InvalidWorkspaceAgentRequestException();
		}
	}

	@ExceptionHandler(LarkSessionAccess.UnauthorizedSessionException.class)
	@ResponseStatus(HttpStatus.UNAUTHORIZED)
	ApiError unauthorized() {
		return new ApiError("LARK_AUTHORIZATION_REQUIRED", "Open Synvo inside Lark to authorize.");
	}

	@ExceptionHandler(WorkspaceAgentException.class)
	ResponseEntity<ApiError> workspaceAgentFailure(WorkspaceAgentException failure) {
		return switch (failure.code()) {
			case NOT_FOUND -> error(HttpStatus.NOT_FOUND, "CODEX_TASK_NOT_FOUND",
					"The Codex task is unavailable.");
			case FORBIDDEN, POLICY_DENIED -> error(HttpStatus.FORBIDDEN, "CODEX_ACTION_DENIED",
					"That Codex action is not allowed.");
			case BUSY -> error(HttpStatus.CONFLICT, "CODEX_BUSY",
					"Codex is busy with another task.");
			case INTERACTION_EXPIRED -> error(HttpStatus.CONFLICT, "CODEX_INTERACTION_EXPIRED",
					"That decision request has expired.");
			case INTERACTION_CONFLICT -> error(HttpStatus.CONFLICT, "CODEX_INTERACTION_CONFLICT",
					"That decision request was already resolved differently.");
			case AUTHENTICATION_REQUIRED -> error(HttpStatus.CONFLICT,
					"CODEX_AUTHENTICATION_REQUIRED", "Codex authentication is required.");
			case INVALID_REQUEST -> error(HttpStatus.BAD_REQUEST, "INVALID_CODEX_REQUEST",
					"The Codex request is invalid.");
			case DISABLED, UNAVAILABLE, PROTOCOL_INCOMPATIBLE -> error(
					HttpStatus.SERVICE_UNAVAILABLE, "CODEX_UNAVAILABLE",
					"Codex is unavailable.");
		};
	}

	@ExceptionHandler({
			MethodArgumentNotValidException.class,
			HttpMessageNotReadableException.class,
			InvalidWorkspaceAgentRequestException.class,
			IllegalArgumentException.class
	})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	ApiError invalidRequest() {
		return new ApiError("INVALID_CODEX_REQUEST", "The Codex request is invalid.");
	}

	private static ResponseEntity<ApiError> error(
			HttpStatus status,
			String code,
			String message) {
		return ResponseEntity.status(status).body(new ApiError(code, message));
	}

	record CreateTask(
			@NotBlank @Size(max = 128) String workspaceId,
			@NotNull RunMode mode,
			@Size(max = 160) String title
	) {
	}

	record TaskTitle(@NotBlank @Size(max = 160) String title) {
	}

	record Toggle(boolean enabled) {
	}

	record TaskMode(@NotNull RunMode mode) {
	}

	record Steering(@NotBlank @Size(max = 20_000) String content) {
	}

	record InteractionDecisionBody(
			@NotNull InteractionDecision decision,
			@Size(max = 20) Map<@NotBlank @Size(max = 100) String,
					@Size(max = 2_000) String> formValues
	) {
		public InteractionDecisionBody {
			formValues = formValues == null ? Map.of() : Map.copyOf(formValues);
		}
	}

	record GoalBody(
			@NotBlank @Size(max = 10_000) String objective,
			GoalCommand command) {
		GoalBody {
			command = command == null ? GoalCommand.SAVE : command;
		}
	}

	record ReviewBody(
			@NotNull ReviewKind kind,
			@Size(max = 10_000) String value
	) {
	}

	record StopResult(boolean stopped) {
	}

	record GoalView(WorkspaceAgentEngineGoal goal) {
		GoalView(synvo.workspaceagent.WorkspaceAgentEngine.Goal goal) {
			this(goal == null ? null : new WorkspaceAgentEngineGoal(
					goal.objective(), goal.status(), goal.tokensUsed(), goal.timeUsedSeconds()));
		}
	}

	record WorkspaceAgentEngineGoal(
			String objective,
			String status,
			long tokensUsed,
			long timeUsedSeconds
	) {
	}

	record WorkspaceAgentEngineView(
			List<synvo.workspaceagent.WorkspaceAgentEngine.Skill> skills,
			List<synvo.workspaceagent.WorkspaceAgentEngine.McpServer> mcpServers
	) {
		WorkspaceAgentEngineView {
			skills = List.copyOf(skills);
			mcpServers = List.copyOf(mcpServers);
		}
	}

	record ApiError(String code, String message) {
	}

	private static final class InvalidWorkspaceAgentRequestException extends RuntimeException {
	}
}
