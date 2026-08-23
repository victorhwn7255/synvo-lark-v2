import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { codexApi, type CodexOperationEvent } from './codex'

describe('codexApi', () => {
  beforeEach(() => {
    FakeEventSource.latest = null
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('loads and validates account, workspace, and task state', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response({
        state: 'READY',
        model: 'gpt-5.6-sol',
        runtimeVersion: '0.148.0',
        reasoningEfforts: ['medium', 'high'],
        account: {
          authentication: 'chatgpt',
          authenticationRequired: false,
          plan: 'Pro',
          usedPercent: 12,
          resetsAt: '2026-08-22T00:00:00Z',
        },
      }))
      .mockResolvedValueOnce(response([
        {
          id: 'finance',
          displayName: 'Finance',
          nativeChatDefault: false,
          writeEnabled: true,
          repositoryLabel: 'Synvo Workspaces/Finance',
        },
        {
          id: 'products',
          displayName: 'Products',
          nativeChatDefault: true,
          writeEnabled: true,
          repositoryLabel: 'Synvo Workspaces/Products',
        },
        {
          id: 'sales',
          displayName: 'Sales',
          nativeChatDefault: false,
          writeEnabled: true,
          repositoryLabel: 'Synvo Workspaces/Sales',
        },
      ]))
      .mockResolvedValueOnce(response([task()]))
    vi.stubGlobal('fetch', fetchMock)

    await expect(codexApi.status()).resolves.toMatchObject({ model: 'gpt-5.6-sol' })
    await expect(codexApi.workspaces()).resolves.toHaveLength(3)
    await expect(codexApi.tasks(false, '  products  ')).resolves.toEqual([task()])
    expect(fetchMock.mock.calls[2]?.[0]).toBe('/api/codex/tasks?archived=false&search=products')
    expect(fetchMock.mock.calls[0]?.[1]).toMatchObject({ credentials: 'same-origin' })
  })

  it('rejects malformed responses and exposes only the backend safe message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response({ state: 'READY' })))
    await expect(codexApi.status()).rejects.toThrow('invalid Codex response')

    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(
      { code: 'CODEX_BUSY', message: 'Codex is busy with another task.' },
      false,
      409,
    )))
    await expect(codexApi.tasks(false)).rejects.toThrow('Codex is busy with another task.')
  })

  it('sends mutations with CSRF and no browser-supplied owner or workspace path', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(task()))
    vi.stubGlobal('fetch', fetchMock)

    await codexApi.createTask({ workspaceId: 'products', mode: 'WORKSPACE_WRITE' }, 'csrf-token')

    expect(fetchMock).toHaveBeenCalledWith('/api/codex/tasks', expect.objectContaining({
      method: 'POST',
      credentials: 'same-origin',
      headers: expect.objectContaining({
        'Content-Type': 'application/json',
        'X-SYNVO-CSRF': 'csrf-token',
      }),
    }))
    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(JSON.parse(request.body as string)).toEqual({
      workspaceId: 'products',
      mode: 'WORKSPACE_WRITE',
    })
  })

  it('sends explicit Synvo goal lifecycle commands', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(null, true, 204))
    vi.stubGlobal('fetch', fetchMock)

    await codexApi.setGoal('task-1', 'Maintain verified reports.', 'RESUME', 'csrf-token')

    expect(fetchMock).toHaveBeenCalledWith('/api/codex/tasks/task-1/goal', expect.objectContaining({
      method: 'PUT',
      credentials: 'same-origin',
    }))
    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(JSON.parse(request.body as string)).toEqual({
      objective: 'Maintain verified reports.',
      command: 'RESUME',
    })
  })

  it('preserves whitespace fragments and delivers typed interaction events', () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const received: CodexOperationEvent[] = []
    const reconnect = vi.fn()
    const subscription = codexApi.subscribe('operation/1', (event) => received.push(event), reconnect)
    const source = FakeEventSource.latest as FakeEventSource

    expect(source.url).toBe('/api/codex/operations/operation%2F1/events')
    source.emit('message_delta', JSON.stringify({
      sequence: 2,
      type: 'MESSAGE_DELTA',
      label: 'Writing the result',
      text: '   ',
      truncated: false,
      terminalStatus: null,
    }))
    source.emit('interaction_required', JSON.stringify({
      interactionId: 'interaction-1',
      taskId: 'task-1',
      operationId: 'operation-1',
      kind: 'FILE_CHANGE_APPROVAL',
      category: 'file change',
      reason: 'Change one bounded workspace file.',
      permissionScope: 'once',
      expiresAt: '2026-08-21T13:00:00Z',
    }))
    source.emit('diff', '{not-json')
    source.onerror?.(new Event('error'))

    expect(received).toEqual([
      {
        kind: 'activity',
        sequence: 2,
        type: 'MESSAGE_DELTA',
        label: 'Writing the result',
        text: '   ',
        truncated: false,
        terminalStatus: null,
      },
      expect.objectContaining({
        kind: 'interaction_required',
        interactionId: 'interaction-1',
        interactionKind: 'FILE_CHANGE_APPROVAL',
      }),
    ])
    expect(reconnect).toHaveBeenCalledOnce()
    subscription.close()
    expect(source.closed).toBe(true)
  })
})

function task() {
  return {
    taskId: 'task-1',
    conversationId: 'conversation-1',
    title: 'Products task',
    workspaceId: 'products',
    workspaceName: 'Products',
    mode: 'WORKSPACE_WRITE' as const,
    pinned: false,
    archived: false,
    createdAt: '2026-08-21T12:00:00Z',
    updatedAt: '2026-08-21T12:00:00Z',
  }
}

function response(payload: unknown, ok = true, status = 200) {
  return { ok, status, json: vi.fn().mockResolvedValue(payload) }
}

class FakeEventSource {
  static latest: FakeEventSource | null = null

  readonly listeners = new Map<string, EventListener[]>()
  readonly url: string
  onerror: ((event: Event) => void) | null = null
  closed = false

  constructor(url: string) {
    this.url = url
    FakeEventSource.latest = this
  }

  addEventListener(type: string, listener: EventListener) {
    this.listeners.set(type, [...(this.listeners.get(type) ?? []), listener])
  }

  emit(type: string, data: string) {
    const event = { data } as MessageEvent<string>
    for (const listener of this.listeners.get(type) ?? []) listener(event)
  }

  close() {
    this.closed = true
  }
}
