package synvo.workspaceagent;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.springframework.dao.DataIntegrityViolationException;
import synvo.workspaceagent.WorkspaceAgentEngine.Activity;
import synvo.workspaceagent.WorkspaceAgentEngine.ActivityBatch;
import synvo.workspaceagent.WorkspaceAgentEngine.ActivityKind;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDecision;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionDetail;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionKind;
import synvo.workspaceagent.WorkspaceAgentEngine.InteractionRequest;
import synvo.workspaceagent.WorkspaceAgentEngine.Goal;
import synvo.workspaceagent.WorkspaceAgentEngine.ReviewTarget;
import synvo.workspaceagent.WorkspaceAgentEngine.RunMode;
import synvo.workspaceagent.WorkspaceAgentEngine.TerminalStatus;
import synvo.workspaceagent.WorkspaceAgentEngine.TurnInput;
import synvo.workspaceagent.WorkspaceAgentPolicy.AuthorizedInteraction;
import synvo.workspaceagent.WorkspaceAgentRepository.DecisionDisposition;
import synvo.workspaceagent.WorkspaceAgentRepository.GoalSnapshot;
import synvo.workspaceagent.WorkspaceAgentRepository.InteractionRecord;
import synvo.workspaceagent.WorkspaceAgentRepository.OperationRecord;
import synvo.workspaceagent.WorkspaceAgentRepository.OperationType;
import synvo.workspaceagent.WorkspaceAgentRepository.SafeActivity;
import synvo.workspaceagent.WorkspaceAgentRepository.TaskRecord;
import synvo.workspaceagent.WorkspaceRegistry.WorkspaceDefinition;
import synvo.workspaceagent.WorkspaceRegistry.WorkspaceSummary;

/**
 * Application boundary for configured workspace tasks. Surface and Agent Core
 * callers never receive engine handles, runner records, paths, or credentials.
 */
public final class WorkspaceAgentFacade implements WorkspaceConversationAgent {

	private static final int TASK_LIST_LIMIT = 100;
	private static final int MAX_TRANSIENT_EVENTS = 1_000;
	private static final int MAX_RECENT_OPERATIONS = 50;
	private static final int MAX_TITLE = 160;
	private static final int MAX_TURN_TEXT = 100_000;
	private static final int MAX_STEERING_TEXT = 20_000;
	private static final Duration TASK_DELETE_STOP_TIMEOUT = Duration.ofSeconds(10);

	private final WorkspaceAgentEngine engine;
	private final WorkspaceAgentRepository repository;
	private final WorkspaceRegistry workspaces;
	private final WorkspaceAgentPolicy policy;
	private final WorkspaceAgentEventPublisher eventPublisher;
	private final boolean enabled;
	private final String defaultReasoningEffort;
	private final Duration interactionTimeout;
	private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
	private final Map<UUID, OperationRuntime> activeOperations = new ConcurrentHashMap<>();
	private final Map<UUID, OperationRuntime> recentOperations = new LinkedHashMap<>();
	private final Object recentLock = new Object();
	private final Object taskResumeLock = new Object();

	public WorkspaceAgentFacade(
			WorkspaceAgentEngine engine,
			WorkspaceAgentRepository repository,
			WorkspaceRegistry workspaces,
			WorkspaceAgentPolicy policy,
			WorkspaceAgentEventPublisher eventPublisher,
			String defaultReasoningEffort,
			Duration interactionTimeout) {
		this(
				engine, repository, workspaces, policy, eventPublisher,
				defaultReasoningEffort, interactionTimeout, true);
	}

	public WorkspaceAgentFacade(
			WorkspaceAgentEngine engine,
			WorkspaceAgentRepository repository,
			WorkspaceRegistry workspaces,
			WorkspaceAgentPolicy policy,
			WorkspaceAgentEventPublisher eventPublisher,
			String defaultReasoningEffort,
			Duration interactionTimeout,
			boolean enabled) {
		this.engine = engine;
		this.repository = repository;
		this.workspaces = workspaces;
		this.policy = policy;
		this.eventPublisher = eventPublisher;
		this.defaultReasoningEffort = defaultReasoningEffort;
		this.interactionTimeout = interactionTimeout;
		this.enabled = enabled;
	}

	@Override
	public boolean enabled() {
		return enabled;
	}

	public StatusView status(String ownerOpenId) {
		policy.requireOwner(ownerOpenId);
		WorkspaceAgentEngine.EngineStatus status = engine.status();
		if (status != WorkspaceAgentEngine.EngineStatus.READY) {
			return new StatusView(status.name(), null, null, List.of(), null);
		}
		var capabilities = engine.capabilities();
		var account = engine.account();
		WorkspaceAgentEngine.EngineStatus effectiveStatus = account.authenticationRequired()
				? WorkspaceAgentEngine.EngineStatus.AUTHENTICATION_REQUIRED
				: WorkspaceAgentEngine.EngineStatus.READY;
		return new StatusView(
				effectiveStatus.name(),
				capabilities.model(),
				capabilities.runtimeVersion(),
				capabilities.reasoningEfforts(),
				new AccountView(
						account.authentication(),
						account.authenticationRequired(),
						account.plan(),
						account.usedPercent(),
						account.resetsAt()));
	}

	public List<WorkspaceSummary> listWorkspaces(String ownerOpenId) {
		policy.requireOwner(ownerOpenId);
		return workspaces.summaries();
	}

	public TaskView createTask(
			String ownerOpenId,
			String workspaceId,
			RunMode mode,
			String requestedTitle) {
		policy.requireOwner(ownerOpenId);
		requireReady();
		workspaces.verifyMode(workspaceId, mode);
		String title = title(requestedTitle, "New Codex task");
		workspaces.require(workspaceId);
		var handle = engine.createTask(workspaces.target(workspaceId), mode);
		try {
			return view(repository.createTask(
					ownerOpenId, workspaceId, mode, title, handle.reference()));
		}
		catch (RuntimeException persistenceFailure) {
			deleteEngineTaskQuietly(handle.reference());
			throw persistenceFailure;
		}
	}

	public TaskView forkTask(String ownerOpenId, UUID sourceTaskId, String requestedTitle) {
		policy.requireOwner(ownerOpenId);
		requireReady();
		TaskRecord source = requireTask(ownerOpenId, sourceTaskId);
		ensureIdle(source.taskId());
		String title = title(requestedTitle, "Fork of " + source.title());
		var handle = engine.forkTask(
				source.taskReference(), workspaces.target(source.workspaceId()));
		try {
			return view(repository.forkTask(source, title, handle.reference()));
		}
		catch (RuntimeException persistenceFailure) {
			deleteEngineTaskQuietly(handle.reference());
			throw persistenceFailure;
		}
	}

	public List<TaskView> listTasks(
			String ownerOpenId,
			boolean archived,
			String search) {
		policy.requireOwner(ownerOpenId);
		if (search != null && search.length() > 160) {
			throw invalid();
		}
		return repository.listOwnedTasks(ownerOpenId, archived, search, TASK_LIST_LIMIT)
				.stream()
				.filter(task -> workspaces.contains(task.workspaceId()))
				.map(this::view)
				.toList();
	}

	public TaskDetail task(String ownerOpenId, UUID taskId) {
		policy.requireOwner(ownerOpenId);
		TaskRecord task = requireTask(ownerOpenId, taskId);
		return new TaskDetail(
				view(task),
				repository.findActiveSystemOperation()
						.filter(operation -> operation.taskId().equals(taskId))
						.map(this::operationView)
						.orElse(null),
				repository.findLatestOperation(taskId)
						.map(this::operationView)
						.orElse(null),
				pendingInteractions(ownerOpenId, taskId));
	}

	public TaskView renameTask(String ownerOpenId, UUID taskId, String requestedTitle) {
		policy.requireOwner(ownerOpenId);
		TaskRecord task = requireTask(ownerOpenId, taskId);
		ensureIdle(taskId);
		String title = title(requestedTitle, null);
		engine.renameTask(task.taskReference(), title);
		return view(repository.updateTitle(ownerOpenId, taskId, title));
	}

	public TaskView pinTask(String ownerOpenId, UUID taskId, boolean pinned) {
		policy.requireOwner(ownerOpenId);
		requireTask(ownerOpenId, taskId);
		return view(repository.updatePinned(ownerOpenId, taskId, pinned));
	}

	public TaskView archiveTask(String ownerOpenId, UUID taskId, boolean archived) {
		policy.requireOwner(ownerOpenId);
		TaskRecord task = requireTask(ownerOpenId, taskId);
		ensureIdle(taskId);
		if (archived) {
			engine.archiveTask(task.taskReference());
		}
		else {
			engine.unarchiveTask(task.taskReference());
		}
		return view(repository.updateArchived(ownerOpenId, taskId, archived));
	}

	public TaskView changeMode(String ownerOpenId, UUID taskId, RunMode mode) {
		policy.requireOwner(ownerOpenId);
		TaskRecord task = requireTask(ownerOpenId, taskId);
		ensureIdle(task.taskId());
		workspaces.verifyMode(task.workspaceId(), mode);
		return view(repository.updateMode(ownerOpenId, taskId, mode));
	}

	public void deleteTask(String ownerOpenId, UUID taskId) {
		policy.requireOwner(ownerOpenId);
		TaskRecord task = requireTask(ownerOpenId, taskId);
		stopAndAwaitTaskOperation(taskId);
		engine.deleteTask(task.taskReference());
		if (!repository.deleteOwnedTask(ownerOpenId, taskId)) {
			throw notFound();
		}
	}

	@Override
	public ConversationOutcome runConversation(
			ConversationCommand command,
			ConversationObserver observer,
			BooleanSupplier cancellation) {
		Objects.requireNonNull(command, "command");
		Objects.requireNonNull(observer, "observer");
		Objects.requireNonNull(cancellation, "cancellation");
		policy.requireOwner(command.ownerOpenId());
		requireReady();
		if (command.text().isBlank() || command.text().length() > MAX_TURN_TEXT) {
			throw invalid();
		}
		PreparedTask prepared = prepareConversationTask(command);
		OperationRecord operation = startOperation(
				prepared.task(), command.conversationRunId(), command.requestId(), OperationType.TURN);
		String text = prepared.requiresContext()
				? withBoundedContext(command.context(), command.text()) : command.text();
		WorkspaceAgentEngine.OperationHandle handle;
		try {
			handle = engine.startTurn(
					prepared.task().taskReference(),
					workspaces.target(prepared.task().workspaceId()),
					prepared.task().mode(),
					new TurnInput(
							text,
							reasoningEffort(command.reasoningEffort()),
							command.skillName()));
			repository.bindOperationReference(operation.operationId(), handle.reference());
		}
		catch (RuntimeException startFailure) {
			finishStartFailure(operation, startFailure);
			throw startFailure;
		}
		OperationRuntime runtime = new OperationRuntime(
				operation, prepared.task(), handle.reference(), observer);
		activeOperations.put(operation.operationId(), runtime);
		try {
			return runToTerminal(runtime, cancellation);
		}
		finally {
			completeRuntime(runtime);
		}
	}

	public OperationView startReview(
			String ownerOpenId,
			UUID taskId,
			ReviewTarget target) {
		policy.requireOwner(ownerOpenId);
		requireReady();
		TaskRecord task = requireTask(ownerOpenId, taskId);
		ensureTaskAvailable(task);
		OperationRecord operation = startOperation(
				task, null, UUID.randomUUID().toString(), OperationType.REVIEW);
		WorkspaceAgentEngine.OperationHandle handle;
		try {
			handle = engine.startReview(
					task.taskReference(), workspaces.target(task.workspaceId()), target);
			repository.bindOperationReference(operation.operationId(), handle.reference());
		}
		catch (RuntimeException startFailure) {
			finishStartFailure(operation, startFailure);
			throw startFailure;
		}
		OperationRuntime runtime = new OperationRuntime(
				operation, task, handle.reference(), ConversationObserver.none());
		activeOperations.put(operation.operationId(), runtime);
		executor.submit(() -> {
			try {
				runToTerminal(runtime, () -> runtime.stopRequested);
			}
			catch (RuntimeException ignored) {
				// The normalized terminal state is already published and persisted.
			}
			finally {
				completeRuntime(runtime);
			}
		});
		return operationView(operation);
	}

	public boolean stop(String ownerOpenId, UUID operationId) {
		policy.requireOwner(ownerOpenId);
		OperationRecord operation = requireOwnedOperation(ownerOpenId, operationId);
		OperationRuntime runtime = activeOperations.get(operationId);
		if (runtime == null || operation.operationReference() == null) {
			return false;
		}
		runtime.stopRequested = true;
		engine.stop(runtime.operationReference);
		return true;
	}

	@Override
	public void stopConversationRun(UUID conversationRunId) {
		repository.findActiveByConversationRun(conversationRunId).ifPresent(operation -> {
			OperationRuntime runtime = activeOperations.get(operation.operationId());
			if (runtime != null) {
				runtime.stopRequested = true;
				engine.stop(runtime.operationReference);
			}
		});
	}

	@Override
	public Optional<ConversationTaskHandoff> conversationTask(
			String ownerOpenId,
			UUID conversationId) {
		policy.requireOwner(ownerOpenId);
		return repository.findByConversation(ownerOpenId, conversationId)
				.filter(task -> workspaces.contains(task.workspaceId()))
				.map(task -> new ConversationTaskHandoff(
						task.taskId(),
						workspaces.require(task.workspaceId()).displayName(),
						task.mode()));
	}

	public void steer(String ownerOpenId, UUID operationId, String text) {
		policy.requireOwner(ownerOpenId);
		if (text == null || text.isBlank() || text.length() > MAX_STEERING_TEXT) {
			throw invalid();
		}
		requireOwnedOperation(ownerOpenId, operationId);
		OperationRuntime runtime = activeOperations.get(operationId);
		if (runtime == null) {
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.UNAVAILABLE);
		}
		engine.steer(runtime.operationReference, text);
	}

	public List<ActivityView> activity(
			String ownerOpenId,
			UUID operationId,
			long afterSequence) {
		policy.requireOwner(ownerOpenId);
		OperationRecord operation = requireOwnedOperationAnyState(ownerOpenId, operationId);
		OperationRuntime runtime = runtime(operationId);
		if (runtime != null) {
			return runtime.eventsAfter(afterSequence);
		}
		return repository.loadActivity(operation.operationId(), afterSequence).stream()
				.map(this::safeActivityView)
				.toList();
	}

	public List<InteractionView> pendingInteractions(String ownerOpenId, UUID taskId) {
		policy.requireOwner(ownerOpenId);
		requireTask(ownerOpenId, taskId);
		return repository.listPendingInteractions(ownerOpenId, taskId).stream()
				.map(record -> interactionView(record, false))
				.toList();
	}

	public InteractionView interactionDetail(String ownerOpenId, UUID interactionId) {
		policy.requireOwner(ownerOpenId);
		InteractionRecord record = repository.findOwnedInteraction(ownerOpenId, interactionId)
				.orElseThrow(WorkspaceAgentFacade::notFound);
		return interactionView(record, true);
	}

	public InteractionView decideInteraction(
			String ownerOpenId,
			UUID interactionId,
			InteractionDecision decision,
			Map<String, String> formValues) {
		policy.requireOwner(ownerOpenId);
		InteractionRecord record = repository.findOwnedInteraction(ownerOpenId, interactionId)
				.orElseThrow(WorkspaceAgentFacade::notFound);
		if (record.status() != WorkspaceAgentRepository.InteractionStatus.PENDING) {
			if (record.decision() == decision) {
				return interactionView(record, false);
			}
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.INTERACTION_CONFLICT);
		}
		OperationRuntime runtime = activeOperations.get(record.operationId());
		PendingRuntimeInteraction pending = runtime == null
				? null : runtime.interactions.get(interactionId);
		if (pending == null) {
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.UNAVAILABLE);
		}
		policy.verifyDecision(
				record.availableDecisions(), decision, formValues, record.kind(), pending.detail);
		synchronized (pending) {
			InteractionRecord latest = repository.findOwnedInteraction(ownerOpenId, interactionId)
					.orElseThrow(WorkspaceAgentFacade::notFound);
			if (latest.status() != WorkspaceAgentRepository.InteractionStatus.PENDING) {
				if (latest.decision() == decision) {
					return interactionView(latest, false);
				}
				throw new WorkspaceAgentException(WorkspaceAgentException.Code.INTERACTION_CONFLICT);
			}
			if (!Instant.now().isBefore(latest.expiresAt())) {
				repository.decideInteraction(
						ownerOpenId, interactionId, decision, decisionScope(decision), Instant.now());
				throw new WorkspaceAgentException(WorkspaceAgentException.Code.INTERACTION_EXPIRED);
			}
			engine.decideInteraction(
					runtime.operationReference,
					pending.sourceReference,
					decision,
					formValues == null ? Map.of() : Map.copyOf(formValues));
			var result = repository.decideInteraction(
					ownerOpenId, interactionId, decision, decisionScope(decision), Instant.now());
			if (result.disposition() == DecisionDisposition.CONFLICT) {
				throw new WorkspaceAgentException(WorkspaceAgentException.Code.INTERACTION_CONFLICT);
			}
			if (result.disposition() == DecisionDisposition.EXPIRED) {
				throw new WorkspaceAgentException(WorkspaceAgentException.Code.INTERACTION_EXPIRED);
			}
			runtime.interactions.remove(interactionId);
			return interactionView(result.interaction(), false);
		}
	}

	public WorkspaceAgentEngine.Inventory inventory(String ownerOpenId, UUID taskId) {
		policy.requireOwner(ownerOpenId);
		TaskRecord task = requireTask(ownerOpenId, taskId);
		resumePersistedTask(task);
		return engine.inventory(task.taskReference(), workspaces.target(task.workspaceId()));
	}

	public Optional<Goal> goal(String ownerOpenId, UUID taskId) {
		policy.requireOwner(ownerOpenId);
		TaskRecord task = requireTask(ownerOpenId, taskId);
		resumePersistedTask(task);
		Optional<GoalSnapshot> saved = repository.findGoalSnapshot(taskId);
		Optional<Goal> current = engine.goal(task.taskReference());
		if (current.isPresent()) {
			Goal runtime = current.orElseThrow();
			String objective = saved.map(GoalSnapshot::objective)
					.orElse(runtime.objective());
			Goal presented = withObjective(objective, runtime);
			repository.saveGoalSnapshot(taskId, snapshot(presented));
			return Optional.of(presented);
		}
		return saved.map(WorkspaceAgentFacade::goal);
	}

	public void setGoal(
			String ownerOpenId,
			UUID taskId,
			String objective,
			WorkspaceAgentEngine.GoalCommand command) {
		policy.requireOwner(ownerOpenId);
		if (objective == null || objective.isBlank() || objective.length() > 10_000) {
			throw invalid();
		}
		TaskRecord task = requireTask(ownerOpenId, taskId);
		Optional<GoalSnapshot> previous = repository.findGoalSnapshot(taskId);
		WorkspaceAgentEngine.GoalCommand effectiveCommand = Objects.requireNonNullElse(
				command, WorkspaceAgentEngine.GoalCommand.SAVE);
		engine.setGoal(
				task.taskReference(),
				objective,
				effectiveCommand);
		Goal updated = engine.goal(task.taskReference())
				.map(value -> withObjective(objective, value))
				.orElseGet(() -> fallbackGoal(
						objective,
						effectiveCommand,
						previous.orElse(null)));
		repository.saveGoalSnapshot(taskId, snapshot(updated));
	}

	public void clearGoal(String ownerOpenId, UUID taskId) {
		policy.requireOwner(ownerOpenId);
		TaskRecord task = requireTask(ownerOpenId, taskId);
		engine.clearGoal(task.taskReference());
		repository.clearGoalSnapshot(taskId);
	}

	private static Goal fallbackGoal(
			String objective,
			WorkspaceAgentEngine.GoalCommand command,
			GoalSnapshot previous) {
		boolean sameObjective = previous != null && previous.objective().equals(objective);
		String status = switch (command) {
			case RESUME -> "active";
			case PAUSE -> "paused";
			case SAVE -> sameObjective ? previous.status() : "active";
		};
		return new Goal(
				objective,
				status,
				sameObjective ? previous.tokensUsed() : 0,
				sameObjective ? previous.timeUsedSeconds() : 0);
	}

	private static Goal withObjective(String objective, Goal runtime) {
		return new Goal(
				objective,
				runtime.status(),
				runtime.tokensUsed(),
				runtime.timeUsedSeconds());
	}

	private static GoalSnapshot snapshot(Goal goal) {
		return new GoalSnapshot(
				goal.objective(), goal.status(), goal.tokensUsed(),
				goal.timeUsedSeconds(), Instant.now());
	}

	private static Goal goal(GoalSnapshot snapshot) {
		return new Goal(
				snapshot.objective(), snapshot.status(), snapshot.tokensUsed(),
				snapshot.timeUsedSeconds());
	}

	@PreDestroy
	void close() {
		for (OperationRuntime runtime : activeOperations.values()) {
			try {
				engine.stop(runtime.operationReference);
			}
			catch (RuntimeException ignored) {
			}
		}
		executor.shutdownNow();
	}

	private PreparedTask prepareConversationTask(ConversationCommand command) {
		Optional<TaskRecord> existing = repository.findByConversation(
				command.ownerOpenId(), command.conversationId());
		if (existing.isEmpty()) {
			WorkspaceDefinition workspace = workspaces.requireNativeChatDefault();
			var handle = engine.createTask(
					workspaces.target(workspace.id()), RunMode.READ_ONLY);
			try {
				TaskRecord attached = repository.attachTask(
						command.ownerOpenId(), command.conversationId(), workspace.id(),
						RunMode.READ_ONLY, handle.reference());
				return new PreparedTask(attached, command.context().size() > 1);
			}
			catch (RuntimeException persistenceFailure) {
				deleteEngineTaskQuietly(handle.reference());
				throw persistenceFailure;
			}
		}
		TaskRecord task = existing.get();
		ensureTaskAvailable(task);
		boolean hasPriorTurn = repository.hasTerminalTurn(task.taskId());
		try {
			engine.resumeTask(task.taskReference(), workspaces.target(task.workspaceId()));
			return new PreparedTask(task, !hasPriorTurn && command.context().size() > 1);
		}
		catch (WorkspaceAgentException failure) {
			if (failure.code() != WorkspaceAgentException.Code.NOT_FOUND) {
				throw failure;
			}
			var replacement = engine.createTask(
					workspaces.target(task.workspaceId()), task.mode());
			try {
				TaskRecord replaced = repository.replaceTaskReference(
						command.ownerOpenId(), task.taskId(), task.taskReference(),
						replacement.reference());
				return new PreparedTask(replaced, true);
			}
			catch (RuntimeException persistenceFailure) {
				deleteEngineTaskQuietly(replacement.reference());
				throw persistenceFailure;
			}
		}
	}

	private ConversationOutcome runToTerminal(
			OperationRuntime runtime,
			BooleanSupplier cancellation) {
		try {
			TerminalStatus terminalStatus = null;
			while (terminalStatus == null) {
				if (cancellation.getAsBoolean() && !runtime.stopRequested) {
					runtime.stopRequested = true;
					engine.stop(runtime.operationReference);
				}
				ActivityBatch batch = engine.waitForActivity(
						runtime.operationReference, runtime.lastSequence);
				for (Activity received : batch.activities()) {
					if (received.sequence() <= runtime.lastSequence) {
						continue;
					}
					if (received.sequence() != runtime.lastSequence + 1) {
						throw new WorkspaceAgentException(
								WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
					}
					Activity activity = normalizeTerminal(runtime, received);
					runtime.lastSequence = activity.sequence();
					applyMessageActivity(runtime, activity);
					ActivityView view = activityView(activity);
					runtime.add(view);
					persistSafeActivity(runtime.operation.operationId(), activity, view);
					eventPublisher.publish(runtime.operation.operationId(), view);
					runtime.observer.onActivity(view);
					if (activity.kind() == ActivityKind.TURN_COMPLETED) {
						terminalStatus = activity.terminalStatus();
					}
				}
				synchronizeInteractions(runtime);
				if (batch.terminal() && terminalStatus == null) {
					throw new WorkspaceAgentException(
							WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
				}
			}
			return finishRuntime(runtime, terminalStatus);
		}
		catch (WorkspaceAgentException failure) {
			return failRuntime(runtime, terminalFor(failure.code()));
		}
		catch (RuntimeException failure) {
			return failRuntime(runtime, TerminalStatus.ENGINE_UNAVAILABLE);
		}
	}

	private Activity normalizeTerminal(OperationRuntime runtime, Activity activity) {
		if (activity.kind() == ActivityKind.TURN_COMPLETED
				&& activity.terminalStatus() == TerminalStatus.COMPLETED
				&& runtime.operation.type() == OperationType.TURN
				&& runtime.answer.isEmpty()) {
			return new Activity(
					activity.sequence(),
					activity.kind(),
					activity.text(),
					activity.truncated(),
					activity.activityReference(),
					TerminalStatus.PROTOCOL_INCOMPATIBLE);
		}
		return activity;
	}

	private ConversationOutcome failRuntime(
			OperationRuntime runtime,
			TerminalStatus terminalStatus) {
		try {
			engine.stop(runtime.operationReference);
		}
		catch (RuntimeException ignored) {
		}
		Activity terminal = new Activity(
				runtime.lastSequence + 1,
				ActivityKind.TURN_COMPLETED,
				null,
				false,
				null,
				terminalStatus);
		runtime.lastSequence = terminal.sequence();
		ActivityView view = activityView(terminal);
		runtime.add(view);
		persistSafeActivity(runtime.operation.operationId(), terminal, view);
		eventPublisher.publish(runtime.operation.operationId(), view);
		runtime.observer.onActivity(view);
		return finishRuntime(runtime, terminalStatus);
	}

	private ConversationOutcome finishRuntime(
			OperationRuntime runtime,
			TerminalStatus terminalStatus) {
		String safeTerminalMessage = terminalMessage(terminalStatus);
		repository.finishOperation(
				runtime.operation.operationId(), terminalStatus, safeTerminalMessage);
		return new ConversationOutcome(
				runtime.task.taskId(),
				runtime.operation.operationId(),
				terminalStatus,
				runtime.answer.toString(),
				safeTerminalMessage);
	}

	private static TerminalStatus terminalFor(WorkspaceAgentException.Code code) {
		return switch (code) {
			case AUTHENTICATION_REQUIRED -> TerminalStatus.AUTHENTICATION_REQUIRED;
			case PROTOCOL_INCOMPATIBLE -> TerminalStatus.PROTOCOL_INCOMPATIBLE;
			case BUSY, DISABLED, UNAVAILABLE, NOT_FOUND, FORBIDDEN,
					INVALID_REQUEST, POLICY_DENIED, INTERACTION_EXPIRED,
					INTERACTION_CONFLICT -> TerminalStatus.ENGINE_UNAVAILABLE;
		};
	}

	private void synchronizeInteractions(OperationRuntime runtime) {
		for (InteractionRequest request : engine.pendingInteractions(runtime.operationReference)) {
			if (!runtime.task.workspaceId().equals(request.workspaceId())) {
				engine.decideInteraction(
						runtime.operationReference,
						request.reference(),
						InteractionDecision.DECLINE,
						Map.of());
				continue;
			}
			if (runtime.hasSourceInteraction(request.reference())) {
				continue;
			}
			AuthorizedInteraction authorized = policy.authorize(runtime.task, request);
			Instant expiresAt = request.expiresAt().isBefore(Instant.now().plus(interactionTimeout))
					? request.expiresAt() : Instant.now().plus(interactionTimeout);
			InteractionRecord stored = repository.recordInteraction(
					runtime.operation,
					request.reference(),
					request.kind(),
					interactionCategory(request.kind()),
					interactionReason(request.kind()),
					authorized.permissionScope(),
					authorized.decisions(),
					expiresAt);
			PendingRuntimeInteraction pending = new PendingRuntimeInteraction(
					request.reference(), request.detail());
			runtime.interactions.put(stored.interactionId(), pending);
			if (authorized.approvalCategoricallyDenied()) {
				engine.decideInteraction(
						runtime.operationReference,
						request.reference(),
						InteractionDecision.DECLINE,
						Map.of());
				repository.decideInteraction(
						runtime.task.ownerOpenId(),
						stored.interactionId(),
						InteractionDecision.DECLINE,
						"policy",
						Instant.now());
				runtime.interactions.remove(stored.interactionId());
				continue;
			}
			InteractionView view = interactionView(stored, true);
			eventPublisher.interactionRequired(
					runtime.operation.operationId(), view);
			runtime.observer.onInteraction(view);
		}
	}

	private void applyMessageActivity(OperationRuntime runtime, Activity activity) {
		if (startsToolWork(activity.kind()) && runtime.completedMessageVisible) {
			runtime.answer.setLength(0);
			runtime.currentMessageSawDelta = false;
			runtime.messageBoundaryPending = false;
			runtime.completedMessageVisible = false;
			runtime.observer.onMessageReset();
		}
		if (activity.kind() == ActivityKind.MESSAGE_DELTA && activity.text() != null) {
			runtime.currentMessageSawDelta = true;
			appendMessageText(runtime, activity.text());
		}
		else if (activity.kind() == ActivityKind.MESSAGE_COMPLETED) {
			if (!runtime.currentMessageSawDelta && activity.text() != null) {
				appendMessageText(runtime, activity.text());
			}
			runtime.currentMessageSawDelta = false;
			runtime.messageBoundaryPending = !runtime.answer.isEmpty();
			runtime.completedMessageVisible = !runtime.answer.isEmpty();
		}
	}

	private static boolean startsToolWork(ActivityKind kind) {
		return kind == ActivityKind.COMMAND_STARTED
				|| kind == ActivityKind.FILE_CHANGE_STARTED
				|| kind == ActivityKind.MCP_STARTED
				|| kind == ActivityKind.NESTED_ACTIVITY_STARTED
				|| kind == ActivityKind.REVIEW_ENTERED;
	}

	private void appendMessageText(OperationRuntime runtime, String text) {
		String visibleText = text;
		if (runtime.messageBoundaryPending && !runtime.answer.isEmpty()) {
			int existingLineFeeds = trailingLineFeeds(runtime.answer) + leadingLineFeeds(text);
			visibleText = "\n".repeat(Math.max(0, 2 - existingLineFeeds)) + text;
		}
		runtime.messageBoundaryPending = false;
		runtime.answer.append(visibleText);
		runtime.observer.onMessageDelta(visibleText);
	}

	private static int trailingLineFeeds(StringBuilder text) {
		int count = 0;
		for (int index = text.length() - 1; index >= 0 && text.charAt(index) == '\n'; index--) {
			count++;
		}
		return count;
	}

	private static int leadingLineFeeds(String text) {
		int count = 0;
		while (count < text.length() && text.charAt(count) == '\n') {
			count++;
		}
		return count;
	}

	private void persistSafeActivity(UUID operationId, Activity activity, ActivityView view) {
		String summary = switch (activity.kind()) {
			case MESSAGE_DELTA, MESSAGE_COMPLETED, PLAN_DELTA, REASONING_DELTA,
					COMMAND_OUTPUT, FILE_OUTPUT, DIFF -> null;
			default -> view.label();
		};
		repository.appendActivity(operationId, new SafeActivity(
				activity.sequence(),
				activity.kind(),
				summary,
				activity.kind() == ActivityKind.TURN_COMPLETED,
				activity.terminalStatus(),
				Instant.now()));
	}

	private ActivityView activityView(Activity activity) {
		return new ActivityView(
				activity.sequence(),
				activity.kind(),
				activityLabel(activity.kind()),
				activity.text(),
				activity.truncated(),
				activity.terminalStatus());
	}

	private ActivityView safeActivityView(SafeActivity activity) {
		return new ActivityView(
				activity.sequence(),
				activity.kind(),
				activity.safeSummary() == null
						? activityLabel(activity.kind()) : activity.safeSummary(),
				null,
				false,
				activity.terminalStatus());
	}

	private InteractionView interactionView(InteractionRecord record, boolean includeDetail) {
		InteractionDetail detail = null;
		if (includeDetail) {
			OperationRuntime runtime = runtime(record.operationId());
			PendingRuntimeInteraction pending = runtime == null
					? null : runtime.interactions.get(record.interactionId());
			detail = pending == null ? null : pending.detail;
		}
		return new InteractionView(
				record.interactionId(),
				record.taskId(),
				record.operationId(),
				record.workspaceId(),
				workspaces.require(record.workspaceId()).displayName(),
				record.kind(),
				record.category(),
				record.reason(),
				record.permissionScope(),
				record.availableDecisions(),
				record.status().name(),
				record.decision(),
				record.expiresAt(),
				detail == null ? null : new InteractionDetailView(
						detail.command(),
						detail.workingDirectory(),
						detail.affectedPaths(),
						detail.mcpServer(),
						detail.mcpTool(),
						detail.message(),
						detail.inputMode(),
						detail.elicitationUrl(),
						detail.fields()));
	}

	private OperationRecord startOperation(
			TaskRecord task,
			UUID conversationRunId,
			String requestKey,
			OperationType type) {
		try {
			return repository.startOperation(task, conversationRunId, requestKey, type);
		}
		catch (DataIntegrityViolationException busy) {
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.BUSY);
		}
	}

	private void ensureTaskAvailable(TaskRecord task) {
		if (task.archived()) {
			throw invalid();
		}
		workspaces.require(task.workspaceId());
		workspaces.verifyMode(task.workspaceId(), task.mode());
	}

	private void ensureIdle(UUID taskId) {
		repository.findActiveSystemOperation()
				.filter(operation -> operation.taskId().equals(taskId))
				.ifPresent(operation -> {
					throw new WorkspaceAgentException(WorkspaceAgentException.Code.BUSY);
				});
	}

	private void stopAndAwaitTaskOperation(UUID taskId) {
		Optional<OperationRecord> active = repository.findActiveSystemOperation()
				.filter(operation -> operation.taskId().equals(taskId));
		if (active.isEmpty()) {
			return;
		}
		OperationRuntime runtime = activeOperations.get(active.orElseThrow().operationId());
		if (runtime == null) {
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.BUSY);
		}
		runtime.stopRequested = true;
		engine.stop(runtime.operationReference);
		try {
			if (!runtime.terminal.await(
					TASK_DELETE_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
				throw new WorkspaceAgentException(WorkspaceAgentException.Code.UNAVAILABLE);
			}
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new WorkspaceAgentException(WorkspaceAgentException.Code.UNAVAILABLE);
		}
	}

	private OperationRecord requireOwnedOperation(String ownerOpenId, UUID operationId) {
		return repository.findOperation(operationId)
				.filter(operation -> operation.ownerOpenId().equals(ownerOpenId))
				.orElseThrow(WorkspaceAgentFacade::notFound);
	}

	private OperationRecord requireOwnedOperationAnyState(String ownerOpenId, UUID operationId) {
		OperationRuntime runtime = runtime(operationId);
		if (runtime != null && runtime.operation.ownerOpenId().equals(ownerOpenId)) {
			return runtime.operation;
		}
		return repository.findOperation(operationId)
				.filter(operation -> operation.ownerOpenId().equals(ownerOpenId))
				.orElseThrow(WorkspaceAgentFacade::notFound);
	}

	private TaskRecord requireTask(String ownerOpenId, UUID taskId) {
		return repository.findOwnedTask(ownerOpenId, taskId)
				.orElseThrow(WorkspaceAgentFacade::notFound);
	}

	private void resumePersistedTask(TaskRecord task) {
		synchronized (taskResumeLock) {
			engine.resumeTask(task.taskReference(), workspaces.target(task.workspaceId()));
		}
	}

	private TaskView view(TaskRecord task) {
		WorkspaceDefinition workspace = workspaces.require(task.workspaceId());
		return new TaskView(
				task.taskId(),
				task.conversationId(),
				task.title(),
				task.workspaceId(),
				workspace.displayName(),
				task.mode(),
				task.pinned(),
				task.archived(),
				task.createdAt(),
				task.updatedAt());
	}

	private OperationView operationView(OperationRecord operation) {
		return new OperationView(
				operation.operationId(),
				operation.taskId(),
				operation.type(),
				operation.status(),
				operation.createdAt(),
				operation.updatedAt());
	}

	private OperationRuntime runtime(UUID operationId) {
		OperationRuntime active = activeOperations.get(operationId);
		if (active != null) {
			return active;
		}
		synchronized (recentLock) {
			return recentOperations.get(operationId);
		}
	}

	private void remember(OperationRuntime runtime) {
		activeOperations.remove(runtime.operation.operationId(), runtime);
		synchronized (recentLock) {
			recentOperations.put(runtime.operation.operationId(), runtime);
			while (recentOperations.size() > MAX_RECENT_OPERATIONS) {
				UUID eldest = recentOperations.keySet().iterator().next();
				recentOperations.remove(eldest);
			}
		}
	}

	private void completeRuntime(OperationRuntime runtime) {
		try {
			remember(runtime);
		}
		finally {
			runtime.terminal.countDown();
		}
	}

	private void finishStartFailure(OperationRecord operation, RuntimeException failure) {
		TerminalStatus status = failure instanceof WorkspaceAgentException workspaceFailure
				&& workspaceFailure.code() == WorkspaceAgentException.Code.BUSY
				? TerminalStatus.FAILED : TerminalStatus.ENGINE_UNAVAILABLE;
		repository.appendActivity(operation.operationId(), new SafeActivity(
				0,
				ActivityKind.TURN_COMPLETED,
				activityLabel(ActivityKind.TURN_COMPLETED),
				true,
				status,
				Instant.now()));
		repository.finishOperation(operation.operationId(), status, terminalMessage(status));
	}

	private void deleteEngineTaskQuietly(String taskReference) {
		try {
			engine.deleteTask(taskReference);
		}
		catch (RuntimeException ignored) {
		}
	}

	private void requireReady() {
		switch (engine.status()) {
			case READY -> {
				if (engine.account().authenticationRequired()) {
					throw new WorkspaceAgentException(
							WorkspaceAgentException.Code.AUTHENTICATION_REQUIRED);
				}
			}
			case RECOVERING -> throw new WorkspaceAgentException(
					WorkspaceAgentException.Code.UNAVAILABLE);
			case DISABLED -> throw new WorkspaceAgentException(WorkspaceAgentException.Code.DISABLED);
			case AUTHENTICATION_REQUIRED -> throw new WorkspaceAgentException(
					WorkspaceAgentException.Code.AUTHENTICATION_REQUIRED);
			case PROTOCOL_INCOMPATIBLE -> throw new WorkspaceAgentException(
					WorkspaceAgentException.Code.PROTOCOL_INCOMPATIBLE);
			case UNAVAILABLE -> throw new WorkspaceAgentException(
					WorkspaceAgentException.Code.UNAVAILABLE);
		}
	}

	private String reasoningEffort(String requested) {
		String effort = requested == null || requested.isBlank()
				? defaultReasoningEffort : requested;
		if (!engine.capabilities().reasoningEfforts().contains(effort)) {
			throw invalid();
		}
		return effort;
	}

	private static String withBoundedContext(List<VisibleMessage> context, String currentText) {
		StringBuilder prompt = new StringBuilder(
				"Continue this existing Synvo conversation. Prior visible messages follow:\n\n");
		for (VisibleMessage message : context) {
			prompt.append(message.role()).append(": ").append(message.content()).append("\n\n");
		}
		if (context.isEmpty() || !context.getLast().content().equals(currentText)) {
			prompt.append("USER: ").append(currentText);
		}
		return prompt.toString();
	}

	private static String title(String value, String fallback) {
		String result = value == null ? "" : value.replaceAll("\\s+", " ").strip();
		if (result.isEmpty()) {
			if (fallback == null) {
				throw invalid();
			}
			result = fallback;
		}
		if (result.length() > MAX_TITLE) {
			throw invalid();
		}
		return result;
	}

	private static String decisionScope(InteractionDecision decision) {
		return switch (decision) {
			case APPROVE_ONCE -> "once";
			case DECLINE -> "decline";
			case CANCEL -> "cancel";
		};
	}

	private static String interactionCategory(InteractionKind kind) {
		return switch (kind) {
			case COMMAND_APPROVAL -> "shell command";
			case FILE_CHANGE_APPROVAL -> "file change";
			case MCP_TOOL_APPROVAL -> "MCP tool";
			case MCP_ELICITATION -> "MCP request";
		};
	}

	private static String interactionReason(InteractionKind kind) {
		return switch (kind) {
			case COMMAND_APPROVAL ->
					"Codex requests permission to run a workspace command.";
			case FILE_CHANGE_APPROVAL ->
					"Codex requests permission to change workspace files.";
			case MCP_TOOL_APPROVAL ->
					"Codex requests permission to use an allowlisted MCP tool.";
			case MCP_ELICITATION ->
					"Codex requests your input for an allowlisted MCP request.";
		};
	}

	private static String activityLabel(ActivityKind kind) {
		return switch (kind) {
			case TURN_STARTED -> "Codex started";
			case MESSAGE_DELTA, MESSAGE_COMPLETED -> "Writing the result";
			case PLAN_STARTED, PLAN_DELTA, PLAN_UPDATED -> "Planning the task";
			case PLAN_COMPLETED -> "Plan completed";
			case REASONING_STARTED, REASONING_DELTA -> "Reasoning about the task";
			case REASONING_COMPLETED -> "Reasoning completed";
			case COMMAND_STARTED -> "Running a workspace command";
			case COMMAND_OUTPUT -> "Command produced output";
			case COMMAND_COMPLETED -> "Workspace command completed";
			case FILE_CHANGE_STARTED -> "Preparing workspace changes";
			case FILE_OUTPUT -> "File operation produced output";
			case DIFF -> "Workspace diff updated";
			case FILE_CHANGE_COMPLETED -> "Workspace change completed";
			case MCP_STARTED -> "Using an approved MCP tool";
			case MCP_PROGRESS -> "MCP tool is running";
			case MCP_COMPLETED -> "MCP tool completed";
			case NESTED_ACTIVITY_STARTED -> "Codex started nested work";
			case NESTED_ACTIVITY_COMPLETED -> "Nested work completed";
			case REVIEW_ENTERED -> "Code review started";
			case REVIEW_EXITED -> "Code review completed";
			case COMPACTED -> "Task context compacted";
			case USAGE_UPDATED -> "Usage updated";
			case INTERACTION_RESOLVED -> "Decision applied";
			case WAIT_STARTED -> "Codex is waiting";
			case WAIT_COMPLETED -> "Codex resumed";
			case TURN_COMPLETED -> "Codex task finished";
		};
	}

	private static String terminalMessage(TerminalStatus status) {
		return switch (status) {
			case COMPLETED -> "Codex completed the task.";
			case STOPPED -> "Codex task stopped.";
			case TIMEOUT -> "Codex timed out. Please retry the task.";
			case USAGE_LIMITED -> "Codex usage is currently limited. Please retry later.";
			case AUTHENTICATION_REQUIRED -> "Codex authentication is required.";
			case PROTOCOL_INCOMPATIBLE -> "The Codex runtime is incompatible with Synvo.";
			case ENGINE_UNAVAILABLE -> "Codex is unavailable. Please retry the task.";
			case FAILED -> "Codex could not complete the task.";
		};
	}

	private static WorkspaceAgentException invalid() {
		return new WorkspaceAgentException(WorkspaceAgentException.Code.INVALID_REQUEST);
	}

	private static WorkspaceAgentException notFound() {
		return new WorkspaceAgentException(WorkspaceAgentException.Code.NOT_FOUND);
	}

	public record StatusView(
			String state,
			String model,
			String runtimeVersion,
			List<String> reasoningEfforts,
			AccountView account
	) {
		public StatusView {
			reasoningEfforts = List.copyOf(reasoningEfforts);
		}
	}

	public record AccountView(
			String authentication,
			boolean authenticationRequired,
			String plan,
			Double usedPercent,
			Instant resetsAt
	) {
	}

	public record TaskView(
			UUID taskId,
			UUID conversationId,
			String title,
			String workspaceId,
			String workspaceName,
			RunMode mode,
			boolean pinned,
			boolean archived,
			Instant createdAt,
			Instant updatedAt
	) {
	}

	public record TaskDetail(
			TaskView task,
			OperationView activeOperation,
			OperationView latestOperation,
			List<InteractionView> pendingInteractions
	) {
		public TaskDetail {
			pendingInteractions = List.copyOf(pendingInteractions);
		}
	}

	public record OperationView(
			UUID operationId,
			UUID taskId,
			OperationType type,
			WorkspaceAgentRepository.OperationStatus status,
			Instant createdAt,
			Instant updatedAt
	) {
	}

	public record ActivityView(
			long sequence,
			ActivityKind kind,
			String label,
			String transientText,
			boolean truncated,
			TerminalStatus terminalStatus
	) {
	}

	public record InteractionView(
			UUID interactionId,
			UUID taskId,
			UUID operationId,
			String workspaceId,
			String workspaceName,
			InteractionKind kind,
			String category,
			String reason,
			String permissionScope,
			List<InteractionDecision> availableDecisions,
			String status,
			InteractionDecision decision,
			Instant expiresAt,
			InteractionDetailView detail
	) {
		public InteractionView {
			availableDecisions = List.copyOf(availableDecisions);
		}
	}

	public record InteractionDetailView(
			String command,
			String workingDirectory,
			List<String> affectedPaths,
			String mcpServer,
			String mcpTool,
			String message,
			String inputMode,
			String elicitationUrl,
			List<WorkspaceAgentEngine.InteractionField> fields
	) {
		public InteractionDetailView {
			affectedPaths = List.copyOf(affectedPaths);
			fields = List.copyOf(fields);
		}
	}

	public record ConversationCommand(
			String ownerOpenId,
			UUID conversationId,
			UUID conversationRunId,
			String requestId,
			String text,
			List<VisibleMessage> context,
			String reasoningEffort,
			String skillName
	) {
		public ConversationCommand {
			context = List.copyOf(context);
		}
	}

	public record VisibleMessage(String role, String content) {
	}

	public record ConversationOutcome(
			UUID taskId,
			UUID operationId,
			TerminalStatus status,
			String response,
			String safeMessage
	) {
	}

	public interface ConversationObserver {

		void onActivity(ActivityView activity);

		void onMessageDelta(String delta);

		void onMessageReset();

		default void onInteraction(InteractionView interaction) {
		}

		static ConversationObserver none() {
			return new ConversationObserver() {
				@Override
				public void onActivity(ActivityView activity) {
				}

				@Override
				public void onMessageDelta(String delta) {
				}

				@Override
				public void onMessageReset() {
				}
			};
		}
	}

	private record PreparedTask(TaskRecord task, boolean requiresContext) {
	}

	private static final class PendingRuntimeInteraction {

		private final String sourceReference;
		private final InteractionDetail detail;

		private PendingRuntimeInteraction(String sourceReference, InteractionDetail detail) {
			this.sourceReference = sourceReference;
			this.detail = detail;
		}
	}

	private static final class OperationRuntime {

		private final OperationRecord operation;
		private final TaskRecord task;
		private final String operationReference;
		private final ConversationObserver observer;
		private final Deque<ActivityView> events = new ArrayDeque<>();
		private final Map<UUID, PendingRuntimeInteraction> interactions = new HashMap<>();
		private final StringBuilder answer = new StringBuilder();
		private final CountDownLatch terminal = new CountDownLatch(1);
		private long lastSequence = -1;
		private boolean currentMessageSawDelta;
		private boolean messageBoundaryPending;
		private boolean completedMessageVisible;
		private volatile boolean stopRequested;

		private OperationRuntime(
				OperationRecord operation,
				TaskRecord task,
				String operationReference,
				ConversationObserver observer) {
			this.operation = operation;
			this.task = task;
			this.operationReference = operationReference;
			this.observer = observer;
		}

		private synchronized void add(ActivityView event) {
			events.addLast(event);
			while (events.size() > MAX_TRANSIENT_EVENTS) {
				events.removeFirst();
			}
		}

		private synchronized List<ActivityView> eventsAfter(long sequence) {
			return events.stream().filter(event -> event.sequence() > sequence).toList();
		}

		private synchronized boolean hasSourceInteraction(String sourceReference) {
			return interactions.values().stream()
					.anyMatch(interaction -> interaction.sourceReference.equals(sourceReference));
		}
	}
}
