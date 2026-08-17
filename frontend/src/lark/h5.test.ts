import { afterEach, describe, expect, it, vi } from 'vitest'
import { larkH5 } from './h5'

describe('larkH5', () => {
  afterEach(() => {
    delete window.tt
    vi.useRealTimers()
  })

  it('uses the Lark bridge and resolves a state-bound authorization code', async () => {
    window.tt = {
      requestAccess: ({ appID, scopeList, state, success }) => {
        expect(appID).toBe('cli-public-id')
        expect(scopeList).toEqual(['offline_access'])
        expect(state).toBe('one-time-state')
        success({ code: 'short-lived-code', state })
      },
    }

    expect(larkH5.isAvailable()).toBe(true)
    await expect(larkH5.requestAuthorizationCode('cli-public-id', 'one-time-state'))
      .resolves.toBe('short-lived-code')
  })

  it('fails safely outside Lark and on a missing code', async () => {
    expect(larkH5.isAvailable()).toBe(false)
    await expect(larkH5.requestAuthorizationCode('cli-public-id', 'state')).rejects.toThrow('inside Lark')

    window.tt = { requestAccess: ({ success }) => success({}) }
    await expect(larkH5.requestAuthorizationCode('cli-public-id', 'state')).rejects.toThrow('invalid')
  })

  it('rejects an authorization response whose OAuth state does not match', async () => {
    window.tt = {
      requestAccess: ({ success }) => success({ code: 'short-lived-code', state: 'other-state' }),
    }

    await expect(larkH5.requestAuthorizationCode('cli-public-id', 'expected-state')).rejects.toThrow('invalid')
  })

  it('detects a Lark bridge that arrives after the application starts', async () => {
    vi.useFakeTimers()
    const availability = larkH5.waitUntilAvailable()

    window.tt = { requestAccess: () => undefined }
    await vi.advanceTimersByTimeAsync(50)

    await expect(availability).resolves.toBe(true)
  })
})
