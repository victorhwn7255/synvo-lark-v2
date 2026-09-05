import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ConversationApi, ConversationDetail } from '../api/conversations'
import { useConversation } from './useConversation'

afterEach(cleanup)

describe('conversation selection', () => {
  it.each([false, true])('ignores a late conversation response (active run: %s)', async (active) => {
    const previous = deferred<ConversationDetail>()
    const current = detail('current')
    const api = conversationApi()
    vi.mocked(api.get).mockImplementation((id) => id === 'previous' ? previous.promise : Promise.resolve(current))
    const { result } = renderHook(() => useConversation({ api }))
    await waitFor(() => expect(api.list).toHaveBeenCalled())

    let pending!: Promise<void>
    act(() => { pending = result.current.openConversation('previous') })
    await act(async () => { await result.current.openConversation('current') })
    await act(async () => {
      previous.resolve(detail('previous', active))
      await pending
    })

    expect(result.current.selectedConversation).toBe('current')
    expect(result.current.turns).toEqual(current.turns)
    expect(result.current.activeRun).toBeNull()
    expect(api.subscribe).not.toHaveBeenCalled()
  })

  it('keeps a new conversation empty when an earlier load finishes', async () => {
    const previous = deferred<ConversationDetail>()
    const api = conversationApi()
    vi.mocked(api.get).mockReturnValue(previous.promise)
    const { result } = renderHook(() => useConversation({ api }))
    let pending!: Promise<void>
    act(() => { pending = result.current.openConversation('previous') })
    await act(async () => { await result.current.openConversation(null) })

    expect(result.current.loadingConversation).toBe(false)
    await act(async () => {
      previous.resolve(detail('previous', true))
      await pending
    })
    expect(result.current.selectedConversation).toBeNull()
    expect(result.current.turns).toEqual([])
    expect(result.current.activeRun).toBeNull()
    expect(api.subscribe).not.toHaveBeenCalled()
  })
})

function conversationApi(): ConversationApi {
  return {
    list: vi.fn().mockResolvedValue([]),
    get: vi.fn(),
    csrfToken: vi.fn(),
    remove: vi.fn(),
    submit: vi.fn(),
    stop: vi.fn(),
    subscribe: vi.fn().mockReturnValue({ close: vi.fn() }),
  }
}

function detail(id: string, active = false): ConversationDetail {
  return {
    conversationId: id,
    title: id,
    updatedAt: '2026-09-05T00:00:00Z',
    turns: [{
      turnId: `${id}-assistant`, role: 'ASSISTANT', content: `${id} answer`,
      status: active ? 'STREAMING' : 'COMPLETED',
      createdAt: '2026-09-05T00:00:00Z', updatedAt: '2026-09-05T00:00:00Z',
    }],
    activeRun: active ? {
      requestId: `${id}-request`, conversationId: id, runId: `${id}-run`,
      userTurnId: `${id}-user`, assistantTurnId: `${id}-assistant`,
      intent: 'DIRECT_ANSWER', status: 'RUNNING',
    } : null,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}
