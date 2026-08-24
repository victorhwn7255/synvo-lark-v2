export type CodexRunMode = 'READ_ONLY' | 'WORKSPACE_WRITE'
export type CodexOperationStatus = 'RUNNING' | 'WAITING_FOR_INTERACTION' | 'COMPLETED' | 'FAILED' | 'STOPPED'
export type CodexTerminalStatus =
  | 'COMPLETED'
  | 'FAILED'
  | 'STOPPED'
  | 'TIMEOUT'
  | 'USAGE_LIMITED'
  | 'AUTHENTICATION_REQUIRED'
  | 'PROTOCOL_INCOMPATIBLE'
  | 'ENGINE_UNAVAILABLE'
export type CodexInteractionDecision = 'APPROVE_ONCE' | 'DECLINE' | 'CANCEL'
export type CodexReviewKind = 'UNCOMMITTED_CHANGES' | 'BASE_BRANCH' | 'COMMIT' | 'CUSTOM'

export interface CodexStatus {
  state: 'READY' | 'DISABLED' | 'AUTHENTICATION_REQUIRED' | 'UNAVAILABLE'
  model: string | null
  runtimeVersion: string | null
  reasoningEfforts: string[]
  account: {
    authentication: string
    authenticationRequired: boolean
    plan: string | null
    usedPercent: number | null
    resetsAt: string | null
  } | null
}

export interface CodexWorkspace {
  id: string
  displayName: string
  nativeChatDefault: boolean
  writeEnabled: boolean
  repositoryLabel: string | null
}

export interface CodexTask {
  taskId: string
  conversationId: string
  title: string
  workspaceId: string
  workspaceName: string
  mode: CodexRunMode
  pinned: boolean
  archived: boolean
  createdAt: string
  updatedAt: string
}

export interface CodexOperation {
  operationId: string
  taskId: string
  type: 'TURN' | 'REVIEW'
  status: CodexOperationStatus
  createdAt: string
  updatedAt: string
}

export interface CodexInteractionDetail {
  command: string | null
  workingDirectory: string | null
  affectedPaths: string[]
  mcpServer: string | null
  mcpTool: string | null
  message: string | null
  inputMode: string | null
  elicitationUrl: string | null
  fields: CodexInteractionField[]
}

export interface CodexInteractionField {
  name: string
  label: string
  type: 'TEXT' | 'BOOLEAN' | 'NUMBER' | 'INTEGER' | 'SELECT'
  required: boolean
  options: string[]
  maxLength: number
}

export interface CodexInteraction {
  interactionId: string
  taskId: string
  operationId: string
  workspaceId: string
  workspaceName: string
  kind: 'COMMAND_APPROVAL' | 'FILE_CHANGE_APPROVAL' | 'MCP_TOOL_APPROVAL' | 'MCP_ELICITATION'
  category: string
  reason: string
  permissionScope: string
  availableDecisions: CodexInteractionDecision[]
  status: string
  decision: CodexInteractionDecision | null
  expiresAt: string
  detail: CodexInteractionDetail | null
}

export interface CodexTaskDetail {
  task: CodexTask
  activeOperation: CodexOperation | null
  latestOperation: CodexOperation | null
  pendingInteractions: CodexInteraction[]
}

export interface CodexSkill {
  name: string
  description: string
}

export interface CodexMcpServer {
  name: string
  authenticationStatus: string
  tools: string[]
}

export interface CodexInventory {
  skills: CodexSkill[]
  mcpServers: CodexMcpServer[]
}

export type CodexGoalStatus =
  | 'active'
  | 'paused'
  | 'blocked'
  | 'usageLimited'
  | 'budgetLimited'
  | 'complete'

export type CodexGoalCommand = 'SAVE' | 'RESUME' | 'PAUSE'

export interface CodexGoal {
  objective: string
  status: CodexGoalStatus
  tokensUsed: number
  timeUsedSeconds: number
}

export interface CodexActivity {
  kind: 'activity'
  sequence: number
  type: string
  label: string
  text: string | null
  truncated: boolean
  terminalStatus: CodexTerminalStatus | null
}

export interface CodexInteractionRequired {
  kind: 'interaction_required'
  interactionId: string
  taskId: string
  operationId: string
  interactionKind: CodexInteraction['kind']
  category: string
  reason: string
  permissionScope: string
  expiresAt: string
}

export type CodexOperationEvent = CodexActivity | CodexInteractionRequired

export interface CodexSubscription {
  close(): void
}

export interface CodexApi {
  status(signal?: AbortSignal): Promise<CodexStatus>
  workspaces(signal?: AbortSignal): Promise<CodexWorkspace[]>
  tasks(archived: boolean, search?: string, signal?: AbortSignal): Promise<CodexTask[]>
  task(taskId: string, signal?: AbortSignal): Promise<CodexTaskDetail>
  createTask(body: { workspaceId: string; mode: CodexRunMode; title?: string }, csrfToken: string): Promise<CodexTask>
  forkTask(taskId: string, title: string, csrfToken: string): Promise<CodexTask>
  renameTask(taskId: string, title: string, csrfToken: string): Promise<CodexTask>
  pinTask(taskId: string, enabled: boolean, csrfToken: string): Promise<CodexTask>
  archiveTask(taskId: string, enabled: boolean, csrfToken: string): Promise<CodexTask>
  changeMode(taskId: string, mode: CodexRunMode, csrfToken: string): Promise<CodexTask>
  deleteTask(taskId: string, csrfToken: string): Promise<void>
  stopOperation(operationId: string, csrfToken: string): Promise<{ stopped: boolean }>
  steer(operationId: string, content: string, csrfToken: string): Promise<void>
  interaction(interactionId: string, signal?: AbortSignal): Promise<CodexInteraction>
  decideInteraction(
    interactionId: string,
    decision: CodexInteractionDecision,
    formValues: Record<string, string>,
    csrfToken: string,
  ): Promise<CodexInteraction>
  inventory(taskId: string, signal?: AbortSignal): Promise<CodexInventory>
  goal(taskId: string, signal?: AbortSignal): Promise<CodexGoal | null>
  setGoal(taskId: string, objective: string, command: CodexGoalCommand, csrfToken: string): Promise<void>
  clearGoal(taskId: string, csrfToken: string): Promise<void>
  review(
    taskId: string,
    kind: CodexReviewKind,
    value: string | null,
    csrfToken: string,
  ): Promise<CodexOperation>
  csrfToken(signal?: AbortSignal): Promise<string>
  subscribe(
    operationId: string,
    onEvent: (event: CodexOperationEvent) => void,
    onConnectionError: () => void,
  ): CodexSubscription
}

export const codexApi: CodexApi = {
  status: (signal) => request('/api/codex/status', { signal }, isCodexStatus),
  workspaces: (signal) => request('/api/codex/workspaces', { signal }, isWorkspaceList),
  tasks: (archived, search, signal) => {
    const query = new URLSearchParams({ archived: String(archived) })
    if (search?.trim()) query.set('search', search.trim())
    return request(`/api/codex/tasks?${query}`, { signal }, isTaskList)
  },
  task: (taskId, signal) => request(
    `/api/codex/tasks/${encodeURIComponent(taskId)}`,
    { signal },
    isTaskDetail,
  ),
  createTask: (body, csrfToken) => mutation(
    '/api/codex/tasks', 'POST', body, csrfToken, isTask,
  ),
  forkTask: (taskId, title, csrfToken) => mutation(
    `/api/codex/tasks/${encodeURIComponent(taskId)}/fork`,
    'POST', { title }, csrfToken, isTask,
  ),
  renameTask: (taskId, title, csrfToken) => mutation(
    `/api/codex/tasks/${encodeURIComponent(taskId)}/rename`,
    'POST', { title }, csrfToken, isTask,
  ),
  pinTask: (taskId, enabled, csrfToken) => mutation(
    `/api/codex/tasks/${encodeURIComponent(taskId)}/pin`,
    'POST', { enabled }, csrfToken, isTask,
  ),
  archiveTask: (taskId, enabled, csrfToken) => mutation(
    `/api/codex/tasks/${encodeURIComponent(taskId)}/archive`,
    'POST', { enabled }, csrfToken, isTask,
  ),
  changeMode: (taskId, mode, csrfToken) => mutation(
    `/api/codex/tasks/${encodeURIComponent(taskId)}/mode`,
    'POST', { mode }, csrfToken, isTask,
  ),
  deleteTask: (taskId, csrfToken) => requestWithoutResponse(
    `/api/codex/tasks/${encodeURIComponent(taskId)}`,
    { method: 'DELETE', headers: mutationHeaders(csrfToken) },
  ),
  stopOperation: (operationId, csrfToken) => mutation(
    `/api/codex/operations/${encodeURIComponent(operationId)}/stop`,
    'POST', undefined, csrfToken, isStopResult,
  ),
  steer: (operationId, content, csrfToken) => requestWithoutResponse(
    `/api/codex/operations/${encodeURIComponent(operationId)}/steer`,
    {
      method: 'POST',
      headers: mutationHeaders(csrfToken),
      body: JSON.stringify({ content }),
    },
  ),
  interaction: (interactionId, signal) => request(
    `/api/codex/interactions/${encodeURIComponent(interactionId)}`,
    { signal },
    isInteraction,
  ),
  decideInteraction: (interactionId, decision, formValues, csrfToken) => mutation(
    `/api/codex/interactions/${encodeURIComponent(interactionId)}/decision`,
    'POST', { decision, formValues }, csrfToken, isInteraction,
  ),
  inventory: (taskId, signal) => request(
    `/api/codex/tasks/${encodeURIComponent(taskId)}/inventory`,
    { signal },
    isInventory,
  ),
  goal: async (taskId, signal) => {
    const response = await request(
      `/api/codex/tasks/${encodeURIComponent(taskId)}/goal`,
      { signal },
      isGoalView,
    )
    return response.goal
  },
  setGoal: (taskId, objective, command, csrfToken) => requestWithoutResponse(
    `/api/codex/tasks/${encodeURIComponent(taskId)}/goal`,
    {
      method: 'PUT',
      headers: mutationHeaders(csrfToken),
      body: JSON.stringify({ objective, command }),
    },
  ),
  clearGoal: (taskId, csrfToken) => requestWithoutResponse(
    `/api/codex/tasks/${encodeURIComponent(taskId)}/goal`,
    { method: 'DELETE', headers: mutationHeaders(csrfToken) },
  ),
  review: (taskId, kind, value, csrfToken) => mutation(
    `/api/codex/tasks/${encodeURIComponent(taskId)}/reviews`,
    'POST', { kind, value }, csrfToken, isOperation,
  ),
  csrfToken: async (signal) => {
    const bootstrap = await request(
      '/api/lark/auth/bootstrap', { signal }, isAuthorizationBootstrap,
    )
    return bootstrap.csrfToken
  },
  subscribe: (operationId, onEvent, onConnectionError) => {
    const source = new EventSource(
      `/api/codex/operations/${encodeURIComponent(operationId)}/events`,
    )
    const activityNames = [
      'turn_started', 'message_delta', 'message_completed',
      'plan_started', 'plan_delta', 'plan_completed', 'plan_updated',
      'reasoning_started', 'reasoning_delta', 'reasoning_completed',
      'command_started', 'command_output', 'command_completed',
      'file_change_started', 'file_output', 'diff', 'file_change_completed',
      'mcp_started', 'mcp_progress', 'mcp_completed',
      'nested_activity_started', 'nested_activity_completed',
      'review_entered', 'review_exited', 'compacted', 'usage_updated',
      'interaction_resolved', 'wait_started', 'wait_completed', 'turn_completed',
    ]
    const receiveActivity = (event: MessageEvent<string>) => {
      const value = parseJson(event.data)
      const activity = parseActivity(value)
      if (activity) onEvent(activity)
    }
    const receiveInteraction = (event: MessageEvent<string>) => {
      const value = parseJson(event.data)
      const interaction = parseInteractionRequired(value)
      if (interaction) onEvent(interaction)
    }
    activityNames.forEach((name) => source.addEventListener(name, receiveActivity as EventListener))
    source.addEventListener('interaction_required', receiveInteraction as EventListener)
    source.onerror = onConnectionError
    return { close: () => source.close() }
  },
}

async function mutation<T>(
  path: string,
  method: 'POST' | 'PUT',
  body: unknown,
  csrfToken: string,
  validate: (value: unknown) => value is T,
) {
  return request(
    path,
    {
      method,
      headers: mutationHeaders(csrfToken),
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    },
    validate,
  )
}

async function requestWithoutResponse(path: string, init: RequestInit): Promise<void> {
  const response = await fetch(path, {
    ...init,
    credentials: 'same-origin',
    headers: { Accept: 'application/json', ...init.headers },
  })
  if (response.ok) return
  const payload: unknown = await response.json().catch(() => null)
  throw new Error(readSafeError(payload) ?? `Backend returned HTTP ${response.status}`)
}

async function request<T>(
  path: string,
  init: RequestInit,
  validate: (value: unknown) => value is T,
): Promise<T> {
  const response = await fetch(path, {
    ...init,
    credentials: 'same-origin',
    headers: { Accept: 'application/json', ...init.headers },
  })
  const payload: unknown = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error(readSafeError(payload) ?? `Backend returned HTTP ${response.status}`)
  }
  if (!validate(payload)) throw new Error('Backend returned an invalid Codex response')
  return payload
}

function mutationHeaders(csrfToken: string): HeadersInit {
  return { 'Content-Type': 'application/json', 'X-SYNVO-CSRF': csrfToken }
}

function parseJson(value: string): unknown {
  try {
    return JSON.parse(value) as unknown
  } catch {
    return null
  }
}

function parseActivity(value: unknown): CodexActivity | null {
  if (!(
    isRecord(value) && Number.isInteger(value.sequence) && hasText(value.type) &&
    hasText(value.label) && (typeof value.text === 'string' || value.text === null) &&
    typeof value.truncated === 'boolean' &&
    (typeof value.terminalStatus === 'string' || value.terminalStatus === null)
  )) return null
  return {
    kind: 'activity', sequence: value.sequence as number, type: value.type,
    label: value.label, text: value.text as string | null, truncated: value.truncated,
    terminalStatus: value.terminalStatus as CodexTerminalStatus | null,
  }
}

function parseInteractionRequired(value: unknown): CodexInteractionRequired | null {
  if (!(
    isRecord(value) && hasText(value.interactionId) && hasText(value.taskId) &&
    hasText(value.operationId) && hasText(value.kind) && hasText(value.category) &&
    hasText(value.reason) && hasText(value.permissionScope) && hasText(value.expiresAt)
  )) return null
  return {
    kind: 'interaction_required', interactionId: value.interactionId,
    taskId: value.taskId, operationId: value.operationId,
    interactionKind: value.kind as CodexInteraction['kind'], category: value.category,
    reason: value.reason, permissionScope: value.permissionScope, expiresAt: value.expiresAt,
  }
}

function isCodexStatus(value: unknown): value is CodexStatus {
  if (!isRecord(value)) return false
  return ['READY', 'DISABLED', 'AUTHENTICATION_REQUIRED', 'UNAVAILABLE'].includes(value.state as string) &&
    (typeof value.model === 'string' || value.model === null) &&
    (typeof value.runtimeVersion === 'string' || value.runtimeVersion === null) &&
    isStringArray(value.reasoningEfforts) &&
    (value.account === null || (isRecord(value.account) &&
      hasText(value.account.authentication) &&
      typeof value.account.authenticationRequired === 'boolean' &&
      (typeof value.account.plan === 'string' || value.account.plan === null) &&
      (typeof value.account.usedPercent === 'number' || value.account.usedPercent === null) &&
      (typeof value.account.resetsAt === 'string' || value.account.resetsAt === null)))
}

function isWorkspaceList(value: unknown): value is CodexWorkspace[] {
  return Array.isArray(value) && value.every((item) => isRecord(item) &&
    hasText(item.id) && hasText(item.displayName) &&
    typeof item.nativeChatDefault === 'boolean' && typeof item.writeEnabled === 'boolean' &&
    (typeof item.repositoryLabel === 'string' || item.repositoryLabel === null))
}

function isTaskList(value: unknown): value is CodexTask[] {
  return Array.isArray(value) && value.every(isTask)
}

function isTask(value: unknown): value is CodexTask {
  return isRecord(value) && hasText(value.taskId) && hasText(value.conversationId) &&
    hasText(value.title) && hasText(value.workspaceId) && hasText(value.workspaceName) &&
    ['READ_ONLY', 'WORKSPACE_WRITE'].includes(value.mode as string) &&
    typeof value.pinned === 'boolean' && typeof value.archived === 'boolean' &&
    hasText(value.createdAt) && hasText(value.updatedAt)
}

function isTaskDetail(value: unknown): value is CodexTaskDetail {
  return isRecord(value) && isTask(value.task) &&
    (value.activeOperation === null || isOperation(value.activeOperation)) &&
    (value.latestOperation === null || isOperation(value.latestOperation)) &&
    Array.isArray(value.pendingInteractions) && value.pendingInteractions.every(isInteraction)
}

function isOperation(value: unknown): value is CodexOperation {
  return isRecord(value) && hasText(value.operationId) && hasText(value.taskId) &&
    ['TURN', 'REVIEW'].includes(value.type as string) &&
    ['RUNNING', 'WAITING_FOR_INTERACTION', 'COMPLETED', 'FAILED', 'STOPPED'].includes(value.status as string) &&
    hasText(value.createdAt) && hasText(value.updatedAt)
}

function isInteraction(value: unknown): value is CodexInteraction {
  return isRecord(value) && hasText(value.interactionId) && hasText(value.taskId) &&
    hasText(value.operationId) && hasText(value.workspaceId) && hasText(value.workspaceName) &&
    ['COMMAND_APPROVAL', 'FILE_CHANGE_APPROVAL', 'MCP_TOOL_APPROVAL', 'MCP_ELICITATION'].includes(value.kind as string) &&
    hasText(value.category) && hasText(value.reason) && hasText(value.permissionScope) &&
    Array.isArray(value.availableDecisions) && value.availableDecisions.every(isDecision) &&
    hasText(value.status) && (value.decision === null || isDecision(value.decision)) &&
    hasText(value.expiresAt) && (value.detail === null || isInteractionDetail(value.detail))
}

function isInteractionDetail(value: unknown): value is CodexInteractionDetail {
  return isRecord(value) && nullableString(value.command) && nullableString(value.workingDirectory) &&
    isStringArray(value.affectedPaths) && nullableString(value.mcpServer) &&
    nullableString(value.mcpTool) && nullableString(value.message) && nullableString(value.inputMode) &&
    nullableString(value.elicitationUrl) && Array.isArray(value.fields) &&
    value.fields.every(isInteractionField)
}

function isInteractionField(value: unknown): value is CodexInteractionField {
  return isRecord(value) && hasText(value.name) && hasText(value.label) &&
    ['TEXT', 'BOOLEAN', 'NUMBER', 'INTEGER', 'SELECT'].includes(value.type as string) &&
    typeof value.required === 'boolean' && isStringArray(value.options) &&
    Number.isInteger(value.maxLength) && (value.maxLength as number) >= 0 &&
    (value.maxLength as number) <= 2_000
}

function isDecision(value: unknown): value is CodexInteractionDecision {
  return ['APPROVE_ONCE', 'DECLINE', 'CANCEL'].includes(value as string)
}

function isInventory(value: unknown): value is CodexInventory {
  return isRecord(value) && Array.isArray(value.skills) && value.skills.every((skill) =>
    isRecord(skill) && hasText(skill.name) && typeof skill.description === 'string') &&
    Array.isArray(value.mcpServers) && value.mcpServers.every((server) =>
      isRecord(server) && hasText(server.name) && hasText(server.authenticationStatus) &&
      isStringArray(server.tools))
}

function isGoalView(value: unknown): value is { goal: CodexGoal | null } {
  return isRecord(value) && (value.goal === null || (isRecord(value.goal) &&
    hasText(value.goal.objective) && isGoalStatus(value.goal.status) &&
    typeof value.goal.tokensUsed === 'number' && typeof value.goal.timeUsedSeconds === 'number'))
}

function isGoalStatus(value: unknown): value is CodexGoalStatus {
  return ['active', 'paused', 'blocked', 'usageLimited', 'budgetLimited', 'complete'].includes(value as string)
}

function isStopResult(value: unknown): value is { stopped: boolean } {
  return isRecord(value) && typeof value.stopped === 'boolean'
}

function isAuthorizationBootstrap(value: unknown): value is { csrfToken: string } {
  return isRecord(value) && typeof value.csrfToken === 'string'
}

function nullableString(value: unknown): value is string | null {
  return typeof value === 'string' || value === null
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

function hasText(value: unknown): value is string {
  return typeof value === 'string' && value.length > 0
}

function readSafeError(value: unknown): string | null {
  return isRecord(value) && typeof value.message === 'string' ? value.message : null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
