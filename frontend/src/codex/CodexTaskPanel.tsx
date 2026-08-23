import {
  useEffect,
  useState,
  type CSSProperties,
  type FormEvent,
  type KeyboardEvent,
  type PointerEvent as ReactPointerEvent,
} from 'react'
import type {
  CodexActivity,
  CodexGoal,
  CodexGoalCommand,
  CodexGoalStatus,
  CodexInventory,
  CodexReviewKind,
  CodexRunMode,
  CodexStatus,
  CodexTaskDetail,
} from '../api/codex'
import { CheckIcon, CloseIcon } from '../workspace/visuals'

const DEFAULT_PANEL_WIDTH = 480
const MIN_PANEL_WIDTH = 384
const MAX_PANEL_WIDTH = 640
const KEYBOARD_RESIZE_STEP = 24

export function CodexTaskPanel({
  status,
  taskDetail,
  activity,
  inventory,
  goal,
  reconnecting,
  submitting,
  error,
  onClose,
  onRename,
  onPin,
  onArchive,
  onModeChange,
  onFork,
  onDelete,
  onSteer,
  onStopOperation,
  onUpdateGoal,
  onClearGoal,
  onStartReview,
}: {
  status: CodexStatus | null
  taskDetail: CodexTaskDetail
  activity: CodexActivity[]
  inventory: CodexInventory
  goal: CodexGoal | null
  reconnecting: boolean
  submitting: string | null
  error: string | null
  onClose: () => void
  onRename: (title: string) => Promise<void>
  onPin: (enabled: boolean) => Promise<void>
  onArchive: (enabled: boolean) => Promise<void>
  onModeChange: (mode: CodexRunMode) => Promise<void>
  onFork: (title: string) => Promise<void>
  onDelete: () => Promise<void>
  onSteer: (content: string) => Promise<void>
  onStopOperation: () => Promise<void>
  onUpdateGoal: (objective: string, command: CodexGoalCommand) => Promise<void>
  onClearGoal: () => Promise<void>
  onStartReview: (kind: CodexReviewKind, value: string | null) => Promise<void>
}) {
  const task = taskDetail.task
  const operation = taskDetail.activeOperation ?? taskDetail.latestOperation
  const [title, setTitle] = useState(task.title)
  const [steering, setSteering] = useState('')
  const [objective, setObjective] = useState(goal?.objective ?? '')
  const [reviewKind, setReviewKind] = useState<CodexReviewKind>('UNCOMMITTED_CHANGES')
  const [reviewValue, setReviewValue] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [goalFeedback, setGoalFeedback] = useState<'saved' | 'resumed' | 'paused' | 'cleared' | null>(null)
  const [panelWidth, setPanelWidth] = useState(DEFAULT_PANEL_WIDTH)
  const [resizeStart, setResizeStart] = useState<{ pointerX: number; width: number } | null>(null)
  const busy = submitting !== null
  const goalObjective = goal?.objective.trim() ?? ''
  const objectiveValue = objective.trim()
  const goalDirty = objectiveValue !== goalObjective
  const goalPresentation = goal ? describeGoal(goal.status) : null
  const presentedActivity = activity
    .filter(({ type }) => type !== 'MESSAGE_DELTA' && type !== 'USAGE_UPDATED')
    .map((item) => item.type === 'MESSAGE_COMPLETED' ? { ...item, text: null } : item)

  useEffect(() => setTitle(task.title), [task.title])
  useEffect(() => setObjective(goal?.objective ?? ''), [goal?.objective])
  useEffect(() => setGoalFeedback(null), [task.taskId])
  useEffect(() => setGoalFeedback(null), [operation?.operationId])
  useEffect(() => {
    if (!resizeStart) return
    const previousCursor = document.body.style.cursor
    const previousUserSelect = document.body.style.userSelect
    const resize = (event: PointerEvent) => {
      setPanelWidth(clampPanelWidth(resizeStart.width + resizeStart.pointerX - event.clientX))
    }
    const finish = () => setResizeStart(null)
    document.body.style.cursor = 'col-resize'
    document.body.style.userSelect = 'none'
    window.addEventListener('pointermove', resize)
    window.addEventListener('pointerup', finish, { once: true })
    window.addEventListener('pointercancel', finish, { once: true })
    return () => {
      document.body.style.cursor = previousCursor
      document.body.style.userSelect = previousUserSelect
      window.removeEventListener('pointermove', resize)
      window.removeEventListener('pointerup', finish)
      window.removeEventListener('pointercancel', finish)
    }
  }, [resizeStart])

  const submitSteering = (event: FormEvent) => {
    event.preventDefault()
    if (!steering.trim()) return
    void onSteer(steering.trim()).then(() => setSteering(''))
  }

  const submitReview = (event: FormEvent) => {
    event.preventDefault()
    const needsValue = reviewKind !== 'UNCOMMITTED_CHANGES'
    if (needsValue && !reviewValue.trim()) return
    void onStartReview(reviewKind, needsValue ? reviewValue.trim() : null)
  }

  const updateGoal = async (command: CodexGoalCommand) => {
    if (!objectiveValue) return
    setGoalFeedback(null)
    try {
      await onUpdateGoal(objectiveValue, command)
      setGoalFeedback(command === 'RESUME' ? 'resumed' : command === 'PAUSE' ? 'paused' : 'saved')
    } catch {
      // The owning workspace presents the normalized mutation error.
    }
  }

  const clearGoal = async () => {
    setGoalFeedback(null)
    try {
      await onClearGoal()
      setObjective('')
      setGoalFeedback('cleared')
    } catch {
      // The owning workspace presents the normalized mutation error.
    }
  }

  const beginPanelResize = (event: ReactPointerEvent<HTMLDivElement>) => {
    if (event.button !== 0) return
    event.preventDefault()
    setResizeStart({ pointerX: event.clientX, width: panelWidth })
  }

  const resizePanelWithKeyboard = (event: KeyboardEvent<HTMLDivElement>) => {
    const nextWidth = event.key === 'ArrowLeft'
      ? panelWidth + KEYBOARD_RESIZE_STEP
      : event.key === 'ArrowRight'
        ? panelWidth - KEYBOARD_RESIZE_STEP
        : event.key === 'Home'
          ? MIN_PANEL_WIDTH
          : event.key === 'End'
            ? MAX_PANEL_WIDTH
            : null
    if (nextWidth === null) return
    event.preventDefault()
    setPanelWidth(clampPanelWidth(nextWidth))
  }

  return (
    <aside
      id="codex-task-panel"
      className="workspace-artifact-panel codex-task-panel"
      aria-label="Codex task details"
      data-resizing={resizeStart !== null}
      style={{ '--codex-task-panel-width': `${panelWidth}px` } as CSSProperties}
    >
      <div
        className="codex-task-panel__resize-handle"
        role="separator"
        aria-label="Resize Task details"
        aria-orientation="vertical"
        aria-valuemin={MIN_PANEL_WIDTH}
        aria-valuemax={MAX_PANEL_WIDTH}
        aria-valuenow={panelWidth}
        aria-valuetext={`${panelWidth} pixels wide`}
        tabIndex={0}
        title="Drag to resize. Double-click to reset."
        onPointerDown={beginPanelResize}
        onDoubleClick={() => setPanelWidth(DEFAULT_PANEL_WIDTH)}
        onKeyDown={resizePanelWithKeyboard}
      />
      <div className="workspace-artifact-panel__header">
        <div>
          <p>{task.workspaceName}</p>
          <h2>Task details</h2>
        </div>
        <button className="workspace-icon-button codex-task-panel__close" type="button" aria-label="Close task details" onClick={onClose}>
          <CloseIcon />
        </button>
      </div>

      <div className="codex-task-panel__scroll workspace-themed-scrollbar">
        {error && <div className="codex-inline-error" role="alert">{error}</div>}

        <section className="codex-panel-section" aria-labelledby="codex-account-title">
          <h3 id="codex-account-title">Runtime</h3>
          <dl>
            <div><dt>Status</dt><dd>{status?.state ?? 'Checking'}</dd></div>
            <div><dt>Model</dt><dd>{status?.model ?? '—'}</dd></div>
            <div><dt>Account</dt><dd>{status?.account?.plan ?? status?.account?.authentication ?? '—'}</dd></div>
            {status?.account?.usedPercent !== null && status?.account?.usedPercent !== undefined && (
              <div><dt>Usage</dt><dd>{status.account.usedPercent}%</dd></div>
            )}
          </dl>
        </section>

        <section className="codex-panel-section" aria-labelledby="codex-task-management-title">
          <h3 id="codex-task-management-title">Task management</h3>
          <div className="codex-field-action">
            <label className="codex-panel-field">
              <span>Title</span>
              <input value={title} maxLength={160} disabled={busy || Boolean(taskDetail.activeOperation)} onChange={(event) => setTitle(event.target.value)} />
            </label>
            <button type="button" disabled={busy || !title.trim() || title.trim() === task.title || Boolean(taskDetail.activeOperation)} onClick={() => void onRename(title.trim())}>Rename</button>
          </div>
          <label className="codex-panel-field">
            <span>Access mode</span>
            <span className="codex-panel-select-wrap">
              <select className="codex-panel-select" value={task.mode} disabled={busy || Boolean(taskDetail.activeOperation)} onChange={(event) => void onModeChange(event.target.value as CodexRunMode)}>
                <option value="READ_ONLY">Read Only</option>
                <option value="WORKSPACE_WRITE">Full Edit</option>
              </select>
              <SelectChevronIcon />
            </span>
          </label>
          <div className="codex-button-row codex-button-row--task-actions" role="group" aria-label="Task actions">
            <button type="button" disabled={busy} onClick={() => void onPin(!task.pinned)}>{task.pinned ? 'Unpin' : 'Pin'}</button>
            <button type="button" disabled={busy || Boolean(taskDetail.activeOperation)} onClick={() => void onArchive(!task.archived)}>{task.archived ? 'Unarchive' : 'Archive'}</button>
            <button type="button" disabled={busy || Boolean(taskDetail.activeOperation)} onClick={() => void onFork(`Fork of ${task.title}`)}>Fork</button>
          </div>
          {confirmDelete ? (
            <div className="codex-delete-confirm" role="alert">
              <span>Delete this task permanently?</span>
              <button type="button" disabled={busy} onClick={() => void onDelete()}>Delete task</button>
              <button type="button" disabled={busy} onClick={() => setConfirmDelete(false)}>Keep task</button>
            </div>
          ) : (
            <button className="codex-danger-action" type="button" disabled={busy || Boolean(taskDetail.activeOperation)} onClick={() => setConfirmDelete(true)}>Delete task…</button>
          )}
        </section>

        <section className="codex-panel-section codex-goal" aria-labelledby="codex-goal-title">
          <div className="codex-goal__header">
            <div>
              <h3 id="codex-goal-title">Task goal</h3>
              <p>A persistent outcome Codex carries across future messages in this task.</p>
            </div>
            <span className="codex-goal__status" data-state={goalDirty ? 'dirty' : goalPresentation?.tone ?? 'none'}>
              {goalDirty && goal ? 'Unsaved changes' : goalPresentation?.label ?? 'Not set'}
            </span>
          </div>
          {goal && !goalDirty && (
            <div className="codex-goal__state-card" data-state={goalPresentation?.tone} role="status">
              <span className="codex-goal__state-icon" aria-hidden="true">
                {goal.status === 'active' || goal.status === 'complete' ? <CheckIcon /> : goalPresentation?.symbol}
              </span>
              <div><strong>{goalPresentation?.title}</strong><p>{goalPresentation?.description}</p></div>
            </div>
          )}
          <label className="codex-panel-field">
            <span>Objective and completion criteria</span>
            <textarea
              value={objective}
              rows={4}
              maxLength={10_000}
              disabled={busy}
              aria-describedby="codex-goal-guidance"
              placeholder="Describe the outcome, constraints, and how Codex should know it is complete."
              onChange={(event) => {
                setObjective(event.target.value)
                setGoalFeedback(null)
              }}
            />
          </label>
          <p id="codex-goal-guidance" className="codex-goal__guidance">
            {goalDirty && goal
              ? 'Save these changes before pausing or resuming the goal. Saving the objective does not start work or modify files.'
              : 'Saving the objective does not start work or modify files.'}
          </p>
          <div className="codex-button-row codex-goal__actions">
            <button className="codex-goal__save" type="button" disabled={busy || !objectiveValue || !goalDirty} onClick={() => void updateGoal('SAVE')}>
              {submitting === 'set-goal' ? 'Saving…' : goal ? goalDirty ? 'Save changes' : 'Saved' : 'Set goal'}
            </button>
            {goal && <button type="button" disabled={busy} onClick={() => void clearGoal()}>{submitting === 'clear-goal' ? 'Clearing…' : 'Clear goal'}</button>}
          </div>
          {goal && goalPresentation?.action && (
            <div className="codex-goal__lifecycle">
              <button
                className="codex-goal__lifecycle-action"
                type="button"
                disabled={busy || goalDirty}
                onClick={() => void updateGoal(goalPresentation.action!.command)}
              >
                {submitting === 'set-goal' ? goalPresentation.action.pendingLabel : goalPresentation.action.label}
              </button>
              <p>{goalDirty ? 'Save your objective changes to enable this action.' : goalPresentation.action.explanation}</p>
            </div>
          )}
          {goalFeedback && (
            <p className="codex-goal__feedback" role="status">
              {goalFeedbackMessage(goalFeedback)}
            </p>
          )}
          {goal && (
            <dl className="codex-goal__metrics" aria-label="Goal progress">
              <div><dt>Tracked usage</dt><dd>{goal.tokensUsed.toLocaleString()} tokens</dd></div>
              <div><dt>Active time</dt><dd>{duration(goal.timeUsedSeconds)}</dd></div>
            </dl>
          )}
          <div className="codex-goal__next-step">
            <strong>What happens next?</strong>
            <p>{goalPresentation?.nextStep ?? 'Set a goal when this task needs a durable outcome across multiple messages.'}</p>
          </div>
        </section>

        <section className="codex-panel-section" aria-labelledby="codex-operation-title">
          <h3 id="codex-operation-title">Current operation</h3>
          <p className="codex-operation-status" data-status={operation?.status.toLowerCase() ?? 'idle'}>
            {reconnecting ? 'Reconnecting to activity…' : operation ? `${operation.type.toLowerCase()} · ${operation.status.toLowerCase().replaceAll('_', ' ')}` : 'No operation yet'}
          </p>
          {taskDetail.activeOperation && (
            <>
              <form className="codex-inline-form" onSubmit={submitSteering}>
                <label>
                  <span>Steer active work</span>
                  <textarea value={steering} rows={2} maxLength={20_000} disabled={busy} onChange={(event) => setSteering(event.target.value)} />
                </label>
                <button type="submit" disabled={busy || !steering.trim()}>Send steering</button>
              </form>
              <button type="button" disabled={busy} onClick={() => void onStopOperation()}>Stop operation</button>
            </>
          )}
        </section>

        <details className="codex-panel-section codex-technical-activity">
          <summary>
            <span>
              <strong>Technical activity</strong>
              <small>{presentedActivity.length} {presentedActivity.length === 1 ? 'event' : 'events'}</small>
            </span>
            <SelectChevronIcon />
          </summary>
          <p className="codex-technical-activity__intro">Safe normalized protocol detail for diagnostics.</p>
          {presentedActivity.length === 0 ? <p className="codex-panel-empty">No activity to display.</p> : (
            <ol className="codex-activity-list">
              {presentedActivity.map((item) => (
                <li key={item.sequence} data-terminal={Boolean(item.terminalStatus)}>
                  <div><strong>{item.label}</strong><small>#{item.sequence}</small></div>
                  {item.text !== null && <pre>{item.text}{item.truncated ? '\n…truncated' : ''}</pre>}
                  {item.terminalStatus && <span className="codex-terminal-state">{item.terminalStatus.replaceAll('_', ' ')}</span>}
                </li>
              ))}
            </ol>
          )}
        </details>

        <section className="codex-panel-section" aria-labelledby="codex-inventory-title">
          <h3 id="codex-inventory-title">Skills and MCP</h3>
          <p>{inventory.skills.length} configured skill{inventory.skills.length === 1 ? '' : 's'}</p>
          <ul className="codex-inventory-list">
            {inventory.skills.map((skill) => <li key={skill.name}><strong>{skill.name}</strong><span>{skill.description}</span></li>)}
          </ul>
          <ul className="codex-inventory-list">
            {inventory.mcpServers.map((server) => (
              <li key={server.name}>
                <strong>{server.name}</strong>
                <span>{server.authenticationStatus} · {server.tools.length} available tool{server.tools.length === 1 ? '' : 's'}</span>
              </li>
            ))}
          </ul>
          {inventory.skills.length === 0 && inventory.mcpServers.length === 0 && <p className="codex-panel-empty">No allowlisted extensions are available.</p>}
        </section>

        <section className="codex-panel-section" aria-labelledby="codex-review-title">
          <h3 id="codex-review-title">Review</h3>
          <form className="codex-inline-form" onSubmit={submitReview}>
            <label>
              <span>Target</span>
              <span className="codex-panel-select-wrap">
                <select className="codex-panel-select" value={reviewKind} disabled={busy || Boolean(taskDetail.activeOperation)} onChange={(event) => setReviewKind(event.target.value as CodexReviewKind)}>
                  <option value="UNCOMMITTED_CHANGES">Uncommitted changes</option>
                  <option value="BASE_BRANCH">Base branch</option>
                  <option value="COMMIT">Commit</option>
                  <option value="CUSTOM">Custom review instructions</option>
                </select>
                <SelectChevronIcon />
              </span>
            </label>
            {reviewKind !== 'UNCOMMITTED_CHANGES' && (
              <label>
                <span>{reviewKind === 'CUSTOM' ? 'Instructions' : 'Reference'}</span>
                <textarea value={reviewValue} rows={3} maxLength={10_000} disabled={busy || Boolean(taskDetail.activeOperation)} onChange={(event) => setReviewValue(event.target.value)} />
              </label>
            )}
            <button type="submit" disabled={busy || Boolean(taskDetail.activeOperation) || (reviewKind !== 'UNCOMMITTED_CHANGES' && !reviewValue.trim())}>Start review</button>
          </form>
        </section>
      </div>
    </aside>
  )
}

function SelectChevronIcon() {
  return (
    <svg viewBox="0 0 20 20" fill="none" focusable="false" aria-hidden="true">
      <path d="m5 7.5 5 5 5-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function describeGoal(status: CodexGoalStatus) {
  const presentations = {
    active: {
      label: 'In progress',
      title: 'Goal is in progress',
      description: 'Codex will keep this outcome in mind as you continue this task.',
      nextStep: 'Send a message in the conversation to continue working toward this goal.',
      tone: 'active', symbol: '✓',
      action: { label: 'Pause goal', pendingLabel: 'Pausing…', command: 'PAUSE' as const, explanation: 'Pause when you want Codex to stop pursuing this goal until you resume it.' },
    },
    paused: {
      label: 'Paused',
      title: 'Goal is paused',
      description: 'Codex will not continue pursuing this goal until you resume it.',
      nextStep: 'Resume the goal when you are ready, then send a message to continue the work.',
      tone: 'paused', symbol: 'Ⅱ',
      action: { label: 'Resume goal', pendingLabel: 'Resuming…', command: 'RESUME' as const, explanation: 'Resume makes this goal active again; it does not run a task by itself.' },
    },
    blocked: {
      label: 'Needs attention',
      title: 'Goal needs your attention',
      description: 'Codex stopped because it could not make more progress on its own. Your objective is still saved.',
      nextStep: 'Review or edit the objective, save any changes, then resume the goal. Send a message with any missing direction.',
      tone: 'attention', symbol: '!',
      action: { label: 'Resume goal', pendingLabel: 'Resuming…', command: 'RESUME' as const, explanation: 'Resume after you have clarified the objective or are ready for Codex to try again.' },
    },
    usageLimited: {
      label: 'Usage limit reached',
      title: 'Codex usage is temporarily unavailable',
      description: 'The goal is saved, but Codex cannot continue until account usage becomes available again.',
      nextStep: 'Wait for usage to become available. Your objective and progress remain saved in this task.',
      tone: 'attention', symbol: '!', action: null,
    },
    budgetLimited: {
      label: 'Budget reached',
      title: 'This goal reached its budget',
      description: 'The goal is saved, but Codex cannot continue within its current budget.',
      nextStep: 'Review the objective and usage. Clear the goal or adjust its budget outside this panel before continuing.',
      tone: 'attention', symbol: '!', action: null,
    },
    complete: {
      label: 'Completed',
      title: 'Goal is complete',
      description: 'Codex marked this objective as completed. The objective remains available for review.',
      nextStep: 'Review the result. Clear the goal if it is finished, or restart it if more work is needed.',
      tone: 'complete', symbol: '✓',
      action: { label: 'Restart goal', pendingLabel: 'Restarting…', command: 'RESUME' as const, explanation: 'Restart makes the same objective active again; it does not run a task by itself.' },
    },
  }
  return presentations[status]
}

function goalFeedbackMessage(feedback: 'saved' | 'resumed' | 'paused' | 'cleared') {
  if (feedback === 'saved') return 'Objective saved. The goal status did not change.'
  if (feedback === 'resumed') return 'Goal resumed. Send a message when you are ready for Codex to continue.'
  if (feedback === 'paused') return 'Goal paused. Your objective and progress are still saved.'
  return 'Goal cleared. Future messages will no longer use it.'
}

function clampPanelWidth(width: number) {
  return Math.min(MAX_PANEL_WIDTH, Math.max(MIN_PANEL_WIDTH, Math.round(width)))
}

function duration(seconds: number) {
  if (seconds < 60) return `${seconds} ${seconds === 1 ? 'second' : 'seconds'}`
  const minutes = Math.floor(seconds / 60)
  const remainder = seconds % 60
  return remainder === 0
    ? `${minutes} ${minutes === 1 ? 'minute' : 'minutes'}`
    : `${minutes}m ${remainder}s`
}
