import type { CodexActivity, CodexInteraction, CodexInteractionDecision, CodexOperationStatus, CodexTerminalStatus } from '../api/codex'
import type { RunPhase } from '../conversation/useConversation'

export type CodexSteeringMilestoneStatus = 'delivered' | 'completed' | 'failed' | 'stopped'
export type ActivityStepStatus = 'completed' | 'current' | 'unfinished' | 'unconfirmed' | 'steering' | 'waiting' | 'failed' | 'stopped'
export interface DecisionReceipt {
  taskId: string
  operationId: string
  interactionId: string
  decision: CodexInteractionDecision
}
export interface ActivityInput {
  active: boolean
  operationStatus: CodexOperationStatus | null
  reconnecting: boolean
  interaction: CodexInteraction | null
  activity: CodexActivity[]
  phase?: RunPhase | null
  steeringStatus?: CodexSteeringMilestoneStatus | null
  interactionAcknowledged?: boolean
}
interface ActivityStep { id: string; title: string; detail: string | null; status: ActivityStepStatus }
interface Category {
  id: string
  label: string
  first: number
  last: number
  started: number
  finished: number
  open: number
  detail: string | null
}
export interface ToolSummary extends Category {
  state: 'In progress' | 'Activity finished' | 'Interrupted' | 'Unconfirmed'
}

// This is presentation over one owning operation. Events cannot identify or pair
// individual tool calls; category counters deliberately make no outcome claim.
export function projectAgentActivity(input: ActivityInput) {
  const ordered = [...new Map(input.activity.map((item) => [item.sequence, item])).values()]
    .sort((a, b) => a.sequence - b.sequence)
  const terminalStatus = ordered.findLast((item) => item.terminalStatus)?.terminalStatus
    ?? (isTerminalOperation(input.operationStatus) ? input.operationStatus as CodexTerminalStatus : null)
  const terminal = terminalStatus !== null
  const waiting = !terminal && (input.interaction?.status === 'PENDING' || !input.interactionAcknowledged && (input.operationStatus === 'WAITING_FOR_INTERACTION' || input.phase === 'action_required'))
  const categories = new Map<string, Category>()
  let started = false
  let decisions = 0
  for (const item of ordered) {
    if (item.type === 'TURN_STARTED') started = true
    if (item.type === 'INTERACTION_RESOLVED') decisions += 1
    const classification = classifyActivity(item.type)
    if (!classification) continue
    const [id, label, signal] = classification
    const category = categories.get(id) ?? { id, label, first: item.sequence, last: item.sequence, started: 0, finished: 0, open: 0, detail: null }
    category.last = item.sequence
    if (signal === 'start') { category.started += 1; category.open += 1 }
    if (signal === 'finish') { category.finished += 1; category.open = Math.max(0, category.open - 1) }
    if (signal === 'progress' && category.open === 0) category.open = 1
    // Only existing model-provided analysis summaries belong here. Command,
    // file and MCP payloads remain in the established technical disclosure.
    if (id === 'analysis' && item.text) category.detail = boundedSummary(item.text)
    categories.set(id, category)
  }
  const tools: ToolSummary[] = [...categories.values()]
    .filter(({ id }) => ['commands', 'mcp', 'files'].includes(id))
    .map((category) => ({ ...category, state: category.open === 0 ? 'Activity finished'
      : terminal ? terminalStatus === 'COMPLETED' ? 'Unconfirmed' : 'Interrupted' : 'In progress' }))
  const latestOpen = [...categories.values()].filter(({ open }) => open > 0).sort((a, b) => b.last - a.last)[0]
  const latest = [...categories.values()].sort((a, b) => b.last - a.last)[0]
  const workingCategory = latestOpen ?? latest
  const streaming = !terminal && !waiting && input.phase !== 'stopping' && !input.reconnecting && input.phase === 'streaming'
  const tone = terminal ? terminalStatus === 'COMPLETED' ? 'completed' : terminalStatus === 'STOPPED' ? 'stopped' : 'failed'
    : waiting ? 'waiting' : input.active ? 'running' : 'idle'
  const title = terminal ? terminalTitle(terminalStatus) : waiting ? 'Action required'
    : input.phase === 'stopping' ? 'Stopping…'
    : input.reconnecting || input.phase === 'reconnecting' ? 'Reconnecting…'
    : streaming ? 'Writing response…'
    : workingCategory && (workingCategory.open > 0 || input.phase === 'tool_running') ? categoryPhase(workingCategory.id)
    : input.phase === 'thinking' ? 'Analyzing your request…'
    : input.phase === 'accepted' ? 'Starting…'
    : input.active ? 'Working…' : 'Ready'
  const steps: ActivityStep[] = []
  if (started) steps.push({ id: 'task-started', title: 'Task started', detail: null, status: 'completed' })
  const stepStatus = (group: Category[]): ActivityStepStatus => {
    if (!group.some(({ open }) => open > 0)) return 'completed'
    if (terminal) return terminalStatus === 'COMPLETED' ? 'unconfirmed' : 'stopped'
    return !waiting && group.some(({ id }) => id === latestOpen?.id) ? 'current' : 'unfinished'
  }
  const groups: Array<[string, string, Category[]]> = [
    ['analysis', 'Analysis', [...categories.values()].filter(({ id }) => id === 'analysis')],
    ['workspace-work', 'Workspace activity', [...categories.values()].filter(({ id }) => !['analysis', 'files'].includes(id))],
    ['workspace-files', 'File activity', [...categories.values()].filter(({ id }) => id === 'files')],
  ]
  groups.sort((a, b) => Math.min(...a[2].map(({ first }) => first)) - Math.min(...b[2].map(({ first }) => first)))
  for (const [id, label, group] of groups) {
    if (!group.length) continue
    const status = stepStatus(group)
    const suffix = status === 'completed' ? 'finished' : terminal ? status === 'unconfirmed' ? 'unconfirmed' : 'interrupted' : 'in progress'
    const counts = group.map(({ label, started, finished }) => `${label}: ${Math.max(started, finished)}`).join(' · ')
    steps.push({ id, title: `${label} ${suffix}`, detail: id === 'analysis' ? group[0].detail : counts, status })
  }
  if (decisions) steps.push({ id: 'approvals', title: `${decisions} ${decisions === 1 ? 'decision' : 'decisions'} resolved`, detail: null, status: 'completed' })
  if (input.steeringStatus) steps.push({ id: 'steering-update', title: 'Instructions updated', detail: 'Your steering update was delivered to Codex.', status: input.steeringStatus === 'delivered' ? 'steering' : input.steeringStatus })
  if (waiting && input.interaction) steps.push({ id: `interaction-${input.interaction.interactionId}`, title: `Review ${input.interaction.category}`, detail: null, status: 'waiting' })
  if (terminal) steps.push({ id: 'terminal', title: terminalTitle(terminalStatus), detail: null, status: terminalStatus === 'COMPLETED' ? 'completed' : terminalStatus === 'STOPPED' ? 'stopped' : 'failed' })
  return { title, tone, terminal, streaming, steps, tools }
}

function classifyActivity(type: string): [string, string, 'start' | 'finish' | 'progress' | 'observed'] | null {
  switch (type) {
    case 'PLAN_STARTED': case 'REASONING_STARTED': return ['analysis', 'Analysis', 'start']
    case 'PLAN_DELTA': case 'PLAN_UPDATED': case 'REASONING_DELTA': return ['analysis', 'Analysis', 'progress']
    case 'PLAN_COMPLETED': case 'REASONING_COMPLETED': return ['analysis', 'Analysis', 'finish']
    case 'COMPACTED': return ['analysis', 'Analysis', 'observed']
    case 'COMMAND_STARTED': return ['commands', 'Workspace commands', 'start']
    case 'COMMAND_COMPLETED': return ['commands', 'Workspace commands', 'finish']
    case 'MCP_STARTED': return ['mcp', 'Connected tools', 'start']
    case 'MCP_PROGRESS': return ['mcp', 'Connected tools', 'progress']
    case 'MCP_COMPLETED': return ['mcp', 'Connected tools', 'finish']
    case 'FILE_CHANGE_STARTED': return ['files', 'File operations', 'start']
    case 'FILE_CHANGE_COMPLETED': return ['files', 'File operations', 'finish']
    case 'NESTED_ACTIVITY_STARTED': return ['nested', 'Nested activity', 'start']
    case 'NESTED_ACTIVITY_COMPLETED': return ['nested', 'Nested activity', 'finish']
    case 'REVIEW_ENTERED': return ['review', 'Review activity', 'start']
    case 'REVIEW_EXITED': return ['review', 'Review activity', 'finish']
    case 'WAIT_STARTED': return ['wait', 'Wait activity', 'start']
    case 'WAIT_COMPLETED': return ['wait', 'Wait activity', 'finish']
    default: return null
  }
}
function categoryPhase(id: string) {
  if (id === 'mcp') return 'Using a connected tool'
  if (id === 'files') return 'Updating workspace files'
  if (id === 'analysis') return 'Analyzing your request…'
  return 'Working in the workspace'
}
function boundedSummary(value: string) {
  const normalized = value.replace(/\s+/g, ' ').trim()
  return normalized.length <= 480 ? normalized : `${normalized.slice(0, 479).trimEnd()}…`
}
export function isTerminalOperation(status: CodexOperationStatus | null) {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'STOPPED'
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
export function decisionReceiptLabel(decision: CodexInteractionDecision) {
  return decision === 'APPROVE_ONCE' ? 'Approved once' : decision === 'DECLINE' ? 'Declined' : 'Cancellation requested'
}
