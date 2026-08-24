import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { conversationApi, type ConversationStreamEvent } from './conversations'

describe('conversationApi', () => {
  beforeEach(() => {
    FakeEventSource.latest = null
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    vi.restoreAllMocks()
  })

  it('loads typed persisted history using same-origin credentials', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response([{
      conversationId: 'conversation-1',
      title: 'Persisted title',
      updatedAt: '2026-08-18T01:00:00Z',
    }]))
    vi.stubGlobal('fetch', fetchMock)

    await expect(conversationApi.list()).resolves.toHaveLength(1)
    expect(fetchMock).toHaveBeenCalledWith('/api/conversations', expect.objectContaining({
      credentials: 'same-origin',
      headers: expect.objectContaining({ Accept: 'application/json' }),
    }))
  })

  it('rejects malformed backend data and returns only safe backend errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response({ title: 'missing identity' })))
    await expect(conversationApi.list()).rejects.toThrow('invalid conversation response')

    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(
      { code: 'SAFE_ERROR', message: 'Please try again.' },
      false,
      409,
    )))
    await expect(conversationApi.list()).rejects.toThrow('Please try again.')
  })

  it('sends mutations with the backend CSRF token and no browser owner identity', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({
      requestId: 'request-1',
      conversationId: 'conversation-1',
      runId: 'run-1',
      userTurnId: 'user-1',
      assistantTurnId: 'assistant-1',
      intent: 'DIRECT_ANSWER',
      status: 'RUNNING',
      replayed: false,
    }))
    vi.stubGlobal('fetch', fetchMock)

    await conversationApi.submit({
      requestId: 'request-1',
      conversationId: null,
      content: 'Explain SSE',
    }, 'csrf-token')

    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(request.method).toBe('POST')
    expect(request.headers).toMatchObject({
      'Content-Type': 'application/json',
      'X-SYNVO-CSRF': 'csrf-token',
      Accept: 'application/json',
    })
    expect(JSON.parse(request.body as string)).toEqual({
      requestId: 'request-1',
      conversationId: null,
      content: 'Explain SSE',
    })
  })

  it('deletes one encoded conversation using same-origin credentials and CSRF protection', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response(null))
    vi.stubGlobal('fetch', fetchMock)

    await conversationApi.remove('conversation/1', 'csrf-token')

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/conversations/conversation%2F1',
      expect.objectContaining({
        method: 'DELETE',
        credentials: 'same-origin',
        headers: expect.objectContaining({
          'X-SYNVO-CSRF': 'csrf-token',
          Accept: 'application/json',
        }),
      }),
    )
  })

  it('sends an explicit failed assistant turn only for retry replacement', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({
      requestId: 'request-2',
      conversationId: 'conversation-1',
      runId: 'run-2',
      userTurnId: 'user-2',
      assistantTurnId: 'assistant-2',
      intent: 'DIRECT_ANSWER',
      status: 'RUNNING',
      replayed: false,
    }))
    vi.stubGlobal('fetch', fetchMock)

    await conversationApi.submit({
      requestId: 'request-2',
      conversationId: 'conversation-1',
      content: 'Explain SSE',
      replaceFailedAssistantTurnId: 'assistant-1',
    }, 'csrf-token')

    const request = fetchMock.mock.calls[0]?.[1] as RequestInit
    expect(JSON.parse(request.body as string)).toMatchObject({
      requestId: 'request-2',
      conversationId: 'conversation-1',
      content: 'Explain SSE',
      replaceFailedAssistantTurnId: 'assistant-1',
    })
  })

  it('delivers valid named SSE events, ignores malformed data, and exposes reconnect and close', () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const received: ConversationStreamEvent[] = []
    const reconnect = vi.fn()

    const subscription = conversationApi.subscribe('run-1', (event) => received.push(event), reconnect)
    const source = FakeEventSource.latest as FakeEventSource
    expect(source.url).toBe('/api/conversations/runs/run-1/events')

    expect(() => source.emit('content_delta', '{not-json')).not.toThrow()
    source.emit('content_delta', JSON.stringify({
      sequence: 4,
      type: 'content_delta',
      message: null,
      delta: 'Hello',
    }))
    source.emit('content_reset', JSON.stringify({
      sequence: 5,
      type: 'content_reset',
      message: null,
      delta: null,
    }))
    source.emit('content_delta', JSON.stringify({ sequence: 'private', type: 'content_delta' }))
    source.onerror?.(new Event('error'))

    expect(received).toEqual([
      {
        sequence: 4,
        type: 'content_delta',
        message: null,
        delta: 'Hello',
        presentation: null,
        action: null,
      },
      {
        sequence: 5,
        type: 'content_reset',
        message: null,
        delta: null,
        presentation: null,
        action: null,
      },
    ])
    expect(reconnect).toHaveBeenCalledOnce()
    subscription.close()
    expect(source.closed).toBe(true)
  })

  it('accepts only validated workflow presentation data from SSE', () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const received: ConversationStreamEvent[] = []

    conversationApi.subscribe('run-1', (event) => received.push(event), vi.fn())
    const source = FakeEventSource.latest as FakeEventSource
    source.emit('tool_running', JSON.stringify({
      sequence: 3,
      type: 'tool_running',
      message: 'Reading a source',
      delta: null,
      presentation: {
        id: 'source-1',
        kind: 'source',
        sourceName: 'Launch plan',
        sourceType: 'document',
        summary: 'The configured source.',
        url: 'https://synvo.larksuite.com/docx/source-1',
      },
    }))
    source.emit('content_delta', JSON.stringify({
      sequence: 4,
      type: 'content_delta',
      message: null,
      delta: 'Safe content remains available.',
      presentation: {
        id: 'unsafe-source',
        kind: 'source',
        sourceName: 'Unsafe source',
        sourceType: 'document',
        summary: 'Do not render this link.',
        url: 'https://example.com/private',
      },
    }))

    expect(received[0]?.presentation).toMatchObject({ id: 'source-1', kind: 'source' })
    expect(received[1]).toMatchObject({
      delta: 'Safe content remains available.',
      presentation: null,
    })
  })

  it('delivers only typed action handoffs from the shared conversation stream', () => {
    vi.stubGlobal('EventSource', FakeEventSource)
    const received: ConversationStreamEvent[] = []

    conversationApi.subscribe('run-1', (event) => received.push(event), vi.fn())
    const source = FakeEventSource.latest as FakeEventSource
    source.emit('action_required', JSON.stringify({
      sequence: 6,
      type: 'action_required',
      message: 'Open in H5 to review and approve.',
      delta: null,
      action: {
        taskId: 'task-1',
        interactionId: 'interaction-1',
        category: 'file change',
        workspaceName: 'Synvo pilot',
        reason: 'Change one bounded workspace file.',
        permissionScope: 'once',
      },
    }))
    source.emit('action_required', JSON.stringify({
      sequence: 7,
      type: 'action_required',
      message: 'unsafe malformed handoff',
      delta: null,
      action: { taskId: 'task-1' },
    }))

    expect(received).toHaveLength(1)
    expect(received[0]).toMatchObject({
      type: 'action_required',
      action: { interactionId: 'interaction-1', workspaceName: 'Synvo pilot' },
    })
  })
})

function response(payload: unknown, ok = true, status = 200) {
  return {
    ok,
    status,
    json: vi.fn().mockResolvedValue(payload),
  }
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
