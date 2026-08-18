import { isWorkflowPresentation, type WorkflowPresentation } from './presentations'

export type ConversationRole = 'USER' | 'ASSISTANT'
export type ConversationTurnStatus = 'PENDING' | 'STREAMING' | 'COMPLETED' | 'FAILED'

export interface ConversationSummary {
  conversationId: string
  title: string
  updatedAt: string
}

export interface ConversationTurn {
  turnId: string
  role: ConversationRole
  content: string
  status: ConversationTurnStatus
  createdAt: string
  updatedAt: string
}

export interface ConversationDetail extends ConversationSummary {
  turns: ConversationTurn[]
}

export interface TurnSubmission {
  requestId: string
  conversationId: string | null
  content: string
  replaceFailedAssistantTurnId?: string
}

export interface ConversationRun {
  requestId: string
  conversationId: string
  runId: string
  userTurnId: string | null
  assistantTurnId: string | null
  intent: 'DIRECT_ANSWER' | 'CLARIFICATION' | 'RESEARCH' | 'MEETING'
  status: 'RUNNING' | 'COMPLETED' | 'FAILED'
  replayed: boolean
}

export interface ConversationStreamEvent {
  sequence: number
  type:
    | 'accepted'
    | 'thinking'
    | 'streaming'
    | 'content_delta'
    | 'content_reset'
    | 'tool_running'
    | 'completed'
    | 'failed'
  message: string | null
  delta: string | null
  presentation: WorkflowPresentation | null
}

export interface ConversationSubscription {
  close(): void
}

export interface ConversationApi {
  list(signal?: AbortSignal): Promise<ConversationSummary[]>
  get(conversationId: string, signal?: AbortSignal): Promise<ConversationDetail>
  csrfToken(signal?: AbortSignal): Promise<string>
  remove(conversationId: string, csrfToken: string): Promise<void>
  submit(body: TurnSubmission, csrfToken: string): Promise<ConversationRun>
  stop(runId: string, csrfToken: string): Promise<{ stopped: boolean; status: string }>
  subscribe(
    runId: string,
    onEvent: (event: ConversationStreamEvent) => void,
    onConnectionError: () => void,
  ): ConversationSubscription
}

export const conversationApi: ConversationApi = {
  list: (signal) => request('/api/conversations', { signal }, isConversationList),
  get: (conversationId, signal) =>
    request(
      `/api/conversations/${encodeURIComponent(conversationId)}`,
      { signal },
      isConversationDetail,
    ),
  csrfToken: async (signal) => {
    const bootstrap = await request(
      '/api/lark/auth/bootstrap',
      { signal },
      isAuthorizationBootstrap,
    )
    return bootstrap.csrfToken
  },
  remove: (conversationId, csrfToken) =>
    requestWithoutResponse(
      `/api/conversations/${encodeURIComponent(conversationId)}`,
      { method: 'DELETE', headers: mutationHeaders(csrfToken) },
    ),
  submit: (body, csrfToken) =>
    request(
      '/api/conversations/turns',
      {
        method: 'POST',
        headers: mutationHeaders(csrfToken),
        body: JSON.stringify(body),
      },
      isConversationRun,
    ),
  stop: (runId, csrfToken) =>
    request(
      `/api/conversations/runs/${encodeURIComponent(runId)}/stop`,
      { method: 'POST', headers: mutationHeaders(csrfToken) },
      isStopResponse,
    ),
  subscribe: (runId, onEvent, onConnectionError) => {
    const source = new EventSource(
      `/api/conversations/runs/${encodeURIComponent(runId)}/events`,
    )
    const eventNames: ConversationStreamEvent['type'][] = [
      'accepted',
      'thinking',
      'streaming',
      'content_delta',
      'content_reset',
      'tool_running',
      'completed',
      'failed',
    ]
    const receive = (event: MessageEvent<string>) => {
      try {
        const value: unknown = JSON.parse(event.data)
        const parsed = parseStreamEvent(value)
        if (parsed) onEvent(parsed)
      } catch {
        // Ignore malformed transport data. The browser-managed EventSource
        // connection remains available for the next persisted lifecycle event.
      }
    }
    for (const eventName of eventNames) source.addEventListener(eventName, receive as EventListener)
    source.onerror = onConnectionError
    return { close: () => source.close() }
  },
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
  if (!validate(payload)) throw new Error('Backend returned an invalid conversation response')
  return payload
}

function mutationHeaders(csrfToken: string): HeadersInit {
  return { 'Content-Type': 'application/json', 'X-SYNVO-CSRF': csrfToken }
}

function isConversationList(value: unknown): value is ConversationSummary[] {
  return Array.isArray(value) && value.every(isConversationSummary)
}

function isConversationSummary(value: unknown): value is ConversationSummary {
  return (
    isRecord(value) &&
    typeof value.conversationId === 'string' &&
    typeof value.title === 'string' &&
    typeof value.updatedAt === 'string'
  )
}

function isConversationDetail(value: unknown): value is ConversationDetail {
  if (!isRecord(value) || !isConversationSummary(value)) return false
  return Array.isArray(value.turns) && value.turns.every(isTurn)
}

function isTurn(value: unknown): value is ConversationTurn {
  return (
    isRecord(value) &&
    typeof value.turnId === 'string' &&
    ['USER', 'ASSISTANT'].includes(value.role as string) &&
    typeof value.content === 'string' &&
    ['PENDING', 'STREAMING', 'COMPLETED', 'FAILED'].includes(value.status as string) &&
    typeof value.createdAt === 'string' &&
    typeof value.updatedAt === 'string'
  )
}

function isConversationRun(value: unknown): value is ConversationRun {
  return (
    isRecord(value) &&
    typeof value.requestId === 'string' &&
    typeof value.conversationId === 'string' &&
    typeof value.runId === 'string' &&
    (typeof value.userTurnId === 'string' || value.userTurnId === null) &&
    (typeof value.assistantTurnId === 'string' || value.assistantTurnId === null) &&
    ['DIRECT_ANSWER', 'CLARIFICATION', 'RESEARCH', 'MEETING'].includes(value.intent as string) &&
    ['RUNNING', 'COMPLETED', 'FAILED'].includes(value.status as string) &&
    typeof value.replayed === 'boolean'
  )
}

function parseStreamEvent(value: unknown): ConversationStreamEvent | null {
  if (!(
    isRecord(value) &&
    Number.isInteger(value.sequence) &&
    [
      'accepted',
      'thinking',
      'streaming',
      'content_delta',
      'content_reset',
      'tool_running',
      'completed',
      'failed',
    ].includes(value.type as string) &&
    (typeof value.message === 'string' || value.message === null) &&
    (typeof value.delta === 'string' || value.delta === null)
  )) return null
  return {
    sequence: value.sequence as number,
    type: value.type as ConversationStreamEvent['type'],
    message: value.message as string | null,
    delta: value.delta as string | null,
    presentation: isWorkflowPresentation(value.presentation) ? value.presentation : null,
  }
}

function isAuthorizationBootstrap(value: unknown): value is { csrfToken: string } {
  return isRecord(value) && typeof value.csrfToken === 'string'
}

function isStopResponse(value: unknown): value is { stopped: boolean; status: string } {
  return isRecord(value) && typeof value.stopped === 'boolean' && typeof value.status === 'string'
}

function readSafeError(value: unknown): string | null {
  return isRecord(value) && typeof value.message === 'string' ? value.message : null
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
