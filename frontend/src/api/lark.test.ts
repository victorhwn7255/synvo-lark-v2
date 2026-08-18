import { afterEach, describe, expect, it, vi } from 'vitest'
import { larkApi } from './lark'

describe('larkApi', () => {
  afterEach(() => vi.unstubAllGlobals())

  it('validates the safe connection contract', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({
      larkEnabled: true,
      botConnection: 'connected',
      userAuthorization: 'connected',
      user: { displayName: 'Victor', avatarUrl: 'https://example.com/victor.png' },
    }))
    vi.stubGlobal('fetch', fetchMock)

    await expect(larkApi.getConnection()).resolves.toMatchObject({
      botConnection: 'connected',
      userAuthorization: 'connected',
    })
    expect(fetchMock).toHaveBeenCalledWith('/api/lark/connection', expect.objectContaining({
      credentials: 'same-origin',
    }))
  })

  it('sends only the short-lived code, state, and CSRF token during exchange', async () => {
    const fetchMock = vi.fn().mockResolvedValue(response({
      larkEnabled: true,
      botConnection: 'connected',
      userAuthorization: 'connected',
      user: { displayName: 'Victor', avatarUrl: 'https://example.com/victor.png' },
    }))
    vi.stubGlobal('fetch', fetchMock)

    await larkApi.exchange('one-time-code', 'one-time-state', 'csrf')

    expect(fetchMock).toHaveBeenCalledWith('/api/lark/auth/exchange', expect.objectContaining({
      method: 'POST',
      headers: expect.objectContaining({ 'X-SYNVO-CSRF': 'csrf' }),
      body: JSON.stringify({ code: 'one-time-code', state: 'one-time-state' }),
    }))
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain('access_token')
    expect(JSON.stringify(fetchMock.mock.calls)).not.toContain('refresh_token')
  })

  it('rejects malformed or unsuccessful responses safely', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ botConnection: 'mystery' })))
    await expect(larkApi.getConnection()).rejects.toThrow('invalid Lark connection response')

    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      json: vi.fn().mockResolvedValue({ message: 'Start a new Lark authorization attempt.' }),
    }))
    await expect(larkApi.getConnection()).rejects.toThrow('Start a new Lark authorization attempt.')
  })
})

function response(payload: unknown) {
  return {
    ok: true,
    status: 200,
    json: vi.fn().mockResolvedValue(payload),
  }
}
