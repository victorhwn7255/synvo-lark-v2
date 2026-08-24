import { useEffect, useMemo, useState } from 'react'
import type {
  CodexActivity,
  CodexInteraction,
  CodexOperationStatus,
  CodexTerminalStatus,
} from '../api/codex'

export type CodexSteeringMilestoneStatus = 'delivered' | 'completed' | 'failed' | 'stopped'

type StepStatus = 'completed' | 'current' | 'steering' | 'waiting' | 'failed' | 'stopped'

interface ActivityStep {
  id: string
  title: string
  detail: string | null
  status: StepStatus
}

interface Aggregate {
  firstSequence: number
  lastSequence: number
  open: number
}

interface WorkspaceAggregate extends Aggregate {
  commandsStarted: number
  commandsCompleted: number
  mcpStarted: number
  mcpCompleted: number
  nestedStarted: number
  nestedCompleted: number
  reviewsStarted: number
  reviewsCompleted: number
  waitsStarted: number
  waitsCompleted: number
}

export function CodexActivityTimeline({
  active,
  operationStatus,
  reconnecting,
  interaction,
  activity,
  steeringStatus,
}: {
  active: boolean
  operationStatus: CodexOperationStatus | null
  reconnecting: boolean
  interaction: CodexInteraction | null
  activity: CodexActivity[]
  steeringStatus?: CodexSteeringMilestoneStatus | null
}) {
  const steps = useMemo(
    () => projectActivity(activity, interaction, active, operationStatus, steeringStatus ?? null),
    [active, activity, interaction, operationStatus, steeringStatus],
  )
  const terminalStatus = activity.findLast(({ terminalStatus }) => terminalStatus !== null)?.terminalStatus ?? null
  const terminal = terminalStatus !== null || isTerminalOperation(operationStatus)
  const state = timelineState({ active, operationStatus, reconnecting, interaction, terminalStatus })
  const [expanded, setExpanded] = useState(!terminal || state.tone === 'failed')

  useEffect(() => {
    if (state.tone === 'failed' || state.tone === 'waiting' || state.tone === 'running') setExpanded(true)
    else if (terminal) setExpanded(false)
  }, [state.tone, terminal])

  const stepCount = steps.length

  return (
    <section className="codex-live-activity" data-state={state.tone} aria-label="Agent activity">
      <button
        className="codex-live-activity__toggle"
        type="button"
        aria-label={expanded ? 'Hide agent activity' : 'Show agent activity'}
        aria-expanded={expanded}
        onClick={() => setExpanded((visible) => !visible)}
      >
        <span className="codex-live-activity__state" aria-hidden="true" />
        <span className="codex-live-activity__heading">
          <span>Agent activity</span>
          <strong>{state.title}</strong>
        </span>
        <span className="codex-live-activity__summary">
          {state.summary} · {stepCount} {stepCount === 1 ? 'milestone' : 'milestones'}
        </span>
        <ActivityChevron />
      </button>

      {expanded && (
        <div className="codex-live-activity__body">
          {steps.length === 0 ? (
            <p className="codex-live-activity__empty">Connecting to live Codex activity… Updates will appear automatically.</p>
          ) : (
            <ol className="codex-live-activity__steps">
              {steps.map((step) => (
                <li
                  key={step.id}
                  data-step={step.id}
                  data-status={step.status}
                  aria-current={step.status === 'current' || step.status === 'steering' || step.status === 'waiting' ? 'step' : undefined}
                >
                  <span className="codex-live-activity__marker" aria-hidden="true" />
                  <div>
                    <strong>{step.title}</strong>
                    {step.detail && <p>{step.detail}</p>}
                    {step.status === 'waiting' && <span>Review in the approval panel</span>}
                  </div>
                </li>
              ))}
            </ol>
          )}
          <p className="codex-live-activity__technical-note">
            {activity.length} normalized {activity.length === 1 ? 'event' : 'events'} summarized. Safe technical details remain available in Task details.
          </p>
          <p className="codex-live-activity__notice">
            Activity and model-provided summaries—not private chain-of-thought.
          </p>
        </div>
      )}
    </section>
  )
}

function projectActivity(
  activity: CodexActivity[],
  interaction: CodexInteraction | null,
  active: boolean,
  operationStatus: CodexOperationStatus | null,
  steeringStatus: CodexSteeringMilestoneStatus | null,
) {
  const ordered = [...activity].sort((left, right) => left.sequence - right.sequence)
  let startedAt: number | null = null
  let analysis: (Aggregate & { detail: string | null }) | null = null
  let workspace: WorkspaceAggregate | null = null
  let files: (Aggregate & { started: number; completed: number }) | null = null
  let approvals: (Aggregate & { count: number }) | null = null
  let terminal: CodexActivity | null = null

  const touchAnalysis = (current: typeof analysis, item: CodexActivity) => {
    const next = current ?? { firstSequence: item.sequence, lastSequence: item.sequence, open: 0, detail: null }
    next.lastSequence = item.sequence
    if (item.text) next.detail = boundedSummary(item.text)
    return next
  }
  const touchWorkspace = (current: WorkspaceAggregate | null, item: CodexActivity) => {
    const next = current ?? {
      firstSequence: item.sequence,
      lastSequence: item.sequence,
      open: 0,
      commandsStarted: 0,
      commandsCompleted: 0,
      mcpStarted: 0,
      mcpCompleted: 0,
      nestedStarted: 0,
      nestedCompleted: 0,
      reviewsStarted: 0,
      reviewsCompleted: 0,
      waitsStarted: 0,
      waitsCompleted: 0,
    }
    next.lastSequence = item.sequence
    return next
  }
  const touchFiles = (current: typeof files, item: CodexActivity) => {
    const next = current ?? { firstSequence: item.sequence, lastSequence: item.sequence, open: 0, started: 0, completed: 0 }
    next.lastSequence = item.sequence
    return next
  }

  for (const item of ordered) {
    switch (item.type) {
      case 'TURN_STARTED':
        startedAt ??= item.sequence
        break
      case 'PLAN_STARTED':
      case 'REASONING_STARTED':
        analysis = touchAnalysis(analysis, item)
        analysis.open += 1
        break
      case 'PLAN_DELTA':
      case 'PLAN_UPDATED':
      case 'REASONING_DELTA':
        analysis = touchAnalysis(analysis, item)
        if (analysis.open === 0) analysis.open = 1
        break
      case 'PLAN_COMPLETED':
      case 'REASONING_COMPLETED':
        analysis = touchAnalysis(analysis, item)
        analysis.open = Math.max(0, analysis.open - 1)
        break
      case 'COMPACTED':
        analysis = touchAnalysis(analysis, item)
        break
      case 'COMMAND_STARTED':
        workspace = touchWorkspace(workspace, item)
        workspace.commandsStarted += 1
        workspace.open += 1
        break
      case 'COMMAND_COMPLETED':
        workspace = touchWorkspace(workspace, item)
        workspace.commandsCompleted += 1
        workspace.open = Math.max(0, workspace.open - 1)
        break
      case 'MCP_STARTED':
        workspace = touchWorkspace(workspace, item)
        workspace.mcpStarted += 1
        workspace.open += 1
        break
      case 'MCP_PROGRESS':
        workspace = touchWorkspace(workspace, item)
        if (workspace.open === 0) workspace.open = 1
        break
      case 'MCP_COMPLETED':
        workspace = touchWorkspace(workspace, item)
        workspace.mcpCompleted += 1
        workspace.open = Math.max(0, workspace.open - 1)
        break
      case 'NESTED_ACTIVITY_STARTED':
        workspace = touchWorkspace(workspace, item)
        workspace.nestedStarted += 1
        workspace.open += 1
        break
      case 'NESTED_ACTIVITY_COMPLETED':
        workspace = touchWorkspace(workspace, item)
        workspace.nestedCompleted += 1
        workspace.open = Math.max(0, workspace.open - 1)
        break
      case 'REVIEW_ENTERED':
        workspace = touchWorkspace(workspace, item)
        workspace.reviewsStarted += 1
        workspace.open += 1
        break
      case 'REVIEW_EXITED':
        workspace = touchWorkspace(workspace, item)
        workspace.reviewsCompleted += 1
        workspace.open = Math.max(0, workspace.open - 1)
        break
      case 'WAIT_STARTED':
        workspace = touchWorkspace(workspace, item)
        workspace.waitsStarted += 1
        workspace.open += 1
        break
      case 'WAIT_COMPLETED':
        workspace = touchWorkspace(workspace, item)
        workspace.waitsCompleted += 1
        workspace.open = Math.max(0, workspace.open - 1)
        break
      case 'FILE_CHANGE_STARTED':
        files = touchFiles(files, item)
        files.started += 1
        files.open += 1
        break
      case 'FILE_CHANGE_COMPLETED':
        files = touchFiles(files, item)
        files.completed += 1
        files.open = Math.max(0, files.open - 1)
        break
      case 'INTERACTION_RESOLVED':
        approvals ??= { firstSequence: item.sequence, lastSequence: item.sequence, open: 0, count: 0 }
        approvals.lastSequence = item.sequence
        approvals.count += 1
        break
      case 'TURN_COMPLETED':
        terminal = item
        break
    }
  }

  const projected: Array<ActivityStep & { sequence: number; lastSequence: number; open?: boolean }> = []
  if (startedAt !== null) {
    projected.push({ id: 'task-started', sequence: startedAt, lastSequence: startedAt, title: 'Task started', detail: null, status: 'completed' })
  }
  if (analysis) {
    projected.push({
      id: 'analysis',
      sequence: analysis.firstSequence,
      lastSequence: analysis.lastSequence,
      title: analysis.open > 0 ? 'Analyzing the task' : 'Analysis completed',
      detail: analysis.detail,
      status: 'completed',
      open: analysis.open > 0,
    })
  }
  if (workspace) {
    projected.push({
      id: 'workspace-work',
      sequence: workspace.firstSequence,
      lastSequence: workspace.lastSequence,
      title: workspace.open > 0 ? 'Working in the workspace' : 'Workspace work completed',
      detail: workspaceSummary(workspace),
      status: 'completed',
      open: workspace.open > 0,
    })
  }
  if (files) {
    const count = Math.max(files.started, files.completed)
    projected.push({
      id: 'workspace-files',
      sequence: files.firstSequence,
      lastSequence: files.lastSequence,
      title: files.open > 0 ? 'Updating workspace files' : 'Workspace files updated',
      detail: quantity(count, 'file operation'),
      status: 'completed',
      open: files.open > 0,
    })
  }
  if (approvals) {
    projected.push({
      id: 'approvals',
      sequence: approvals.firstSequence,
      lastSequence: approvals.lastSequence,
      title: `${approvals.count} ${approvals.count === 1 ? 'approval' : 'approvals'} resolved`,
      detail: null,
      status: 'completed',
    })
  }
  if (steeringStatus) {
    projected.push({
      id: 'steering-update',
      sequence: Number.MAX_SAFE_INTEGER - 2,
      lastSequence: Number.MAX_SAFE_INTEGER - 2,
      title: 'Instructions updated',
      detail: 'Your steering update was delivered to Codex.',
      status: steeringStepStatus(steeringStatus),
    })
  }

  projected.sort((left, right) => left.sequence - right.sequence)
  if (active && !interaction && !terminal) {
    const current = projected.filter(({ open }) => open).sort((left, right) => right.lastSequence - left.lastSequence)[0]
    if (current) current.status = 'current'
  }
  if (interaction) {
    projected.push({
      id: `interaction-${interaction.interactionId}`,
      sequence: Number.MAX_SAFE_INTEGER - 1,
      lastSequence: Number.MAX_SAFE_INTEGER - 1,
      title: `Review ${interaction.category}`,
      detail: interaction.reason,
      status: 'waiting',
    })
  }
  if (terminal) {
    projected.push({
      id: 'terminal',
      sequence: terminal.sequence,
      lastSequence: terminal.sequence,
      title: terminalTitle(terminal.terminalStatus),
      detail: null,
      status: terminalStepStatus(terminal.terminalStatus),
    })
  } else if (isTerminalOperation(operationStatus)) {
    projected.push({
      id: 'terminal',
      sequence: Number.MAX_SAFE_INTEGER,
      lastSequence: Number.MAX_SAFE_INTEGER,
      title: operationTerminalTitle(operationStatus),
      detail: null,
      status: operationTerminalStepStatus(operationStatus),
    })
  }

  return projected
    .sort((left, right) => left.sequence - right.sequence)
    .map(({ sequence: _sequence, lastSequence: _lastSequence, open: _open, ...step }) => step)
}

function steeringStepStatus(status: CodexSteeringMilestoneStatus): StepStatus {
  if (status === 'completed') return 'completed'
  if (status === 'failed') return 'failed'
  if (status === 'stopped') return 'stopped'
  return 'steering'
}

function workspaceSummary(workspace: WorkspaceAggregate) {
  const parts = [
    quantity(Math.max(workspace.commandsStarted, workspace.commandsCompleted), 'command'),
    quantity(Math.max(workspace.mcpStarted, workspace.mcpCompleted), 'MCP tool'),
    quantity(Math.max(workspace.nestedStarted, workspace.nestedCompleted), 'nested task'),
    quantity(Math.max(workspace.reviewsStarted, workspace.reviewsCompleted), 'review'),
    quantity(Math.max(workspace.waitsStarted, workspace.waitsCompleted), 'wait'),
  ].filter((part): part is string => part !== null)
  return parts.length === 0 ? null : parts.join(' · ')
}

function quantity(count: number, singular: string) {
  if (count === 0) return null
  return `${count} ${singular}${count === 1 ? '' : 's'}`
}

function boundedSummary(value: string | null) {
  if (!value) return null
  const normalized = value.replace(/\s+/g, ' ').trim()
  if (!normalized) return null
  return normalized.length <= 480 ? normalized : `${normalized.slice(0, 479).trimEnd()}…`
}

function terminalStepStatus(status: CodexTerminalStatus | null): StepStatus {
  if (status === 'COMPLETED') return 'completed'
  if (status === 'STOPPED') return 'stopped'
  return 'failed'
}

function terminalTitle(status: CodexTerminalStatus | null) {
  switch (status) {
    case 'COMPLETED': return 'Completed'
    case 'STOPPED': return 'Stopped'
    case 'TIMEOUT': return 'Timed out'
    case 'USAGE_LIMITED': return 'Usage limit reached'
    case 'AUTHENTICATION_REQUIRED': return 'Authentication required'
    case 'PROTOCOL_INCOMPATIBLE': return 'Runtime incompatible'
    case 'ENGINE_UNAVAILABLE': return 'Codex unavailable'
    default: return 'Failed'
  }
}

function operationTerminalTitle(status: CodexOperationStatus) {
  if (status === 'COMPLETED') return 'Completed'
  if (status === 'STOPPED') return 'Stopped'
  return 'Failed'
}

function operationTerminalStepStatus(status: CodexOperationStatus): StepStatus {
  if (status === 'COMPLETED') return 'completed'
  if (status === 'STOPPED') return 'stopped'
  return 'failed'
}

function timelineState({
  active,
  operationStatus,
  reconnecting,
  interaction,
  terminalStatus,
}: {
  active: boolean
  operationStatus: CodexOperationStatus | null
  reconnecting: boolean
  interaction: CodexInteraction | null
  terminalStatus: CodexTerminalStatus | null
}) {
  if (reconnecting) return { title: 'Reconnecting to Codex', summary: 'Reconnecting', tone: 'running' }
  if (interaction || operationStatus === 'WAITING_FOR_INTERACTION') {
    return { title: 'Needs your approval', summary: 'Action required', tone: 'waiting' }
  }
  if (terminalStatus === 'COMPLETED' || operationStatus === 'COMPLETED') {
    return { title: 'Task completed', summary: 'Completed', tone: 'completed' }
  }
  if (terminalStatus === 'STOPPED' || operationStatus === 'STOPPED') {
    return { title: 'Task stopped', summary: 'Stopped', tone: 'stopped' }
  }
  if (terminalStatus || operationStatus === 'FAILED') {
    return { title: terminalTitle(terminalStatus), summary: 'Needs attention', tone: 'failed' }
  }
  if (active || operationStatus === 'RUNNING') {
    return { title: 'Codex is working', summary: 'In progress', tone: 'running' }
  }
  return { title: 'Agent activity', summary: 'Ready', tone: 'idle' }
}

function isTerminalOperation(status: CodexOperationStatus | null) {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'STOPPED'
}

function ActivityChevron() {
  return (
    <svg className="codex-live-activity__chevron" viewBox="0 0 20 20" fill="none" aria-hidden="true">
      <path d="m5 7.5 5 5 5-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}
