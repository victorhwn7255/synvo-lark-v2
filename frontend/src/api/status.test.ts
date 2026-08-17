import { afterEach, describe, expect, it, vi } from 'vitest'
import { getServiceStatus } from './status'

describe('getServiceStatus', () => {
  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('returns a validated backend response', async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: vi.fn().mockResolvedValue({
        service: 'synvo-backend',
        status: 'ready',
      }),
    })
    vi.stubGlobal('fetch', fetchMock)

    await expect(getServiceStatus()).resolves.toEqual({
      service: 'synvo-backend',
      status: 'ready',
    })
    expect(fetchMock).toHaveBeenCalledWith('/api/status', expect.any(Object))
  })

  it('rejects an invalid response contract', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: true,
        json: vi.fn().mockResolvedValue({ status: 'unknown' }),
      }),
    )

    await expect(getServiceStatus()).rejects.toThrow('invalid status response')
  })

  it('reports an unsuccessful HTTP response', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({
        ok: false,
        status: 503,
      }),
    )

    await expect(getServiceStatus()).rejects.toThrow('HTTP 503')
  })
})
