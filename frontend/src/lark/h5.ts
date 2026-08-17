interface LarkAccessResult {
  code?: string
  state?: string
}

interface LarkClientError {
  errMsg?: string
}

interface LarkClientApi {
  requestAccess(options: {
    appID: string
    scopeList: string[]
    state: string
    success: (result: LarkAccessResult) => void
    fail: (error: LarkClientError) => void
  }): void
}

declare global {
  interface Window {
    tt?: LarkClientApi
  }
}

export interface LarkH5Adapter {
  isAvailable(): boolean
  waitUntilAvailable(signal?: AbortSignal): Promise<boolean>
  requestAuthorizationCode(appId: string, state: string): Promise<string>
}

const bridgeWaitMilliseconds = 10_000
const bridgePollMilliseconds = 50

export const larkH5: LarkH5Adapter = {
  isAvailable: hasRequestAccess,
  waitUntilAvailable: waitForRequestAccess,
  requestAuthorizationCode: (appId, state) =>
    new Promise((resolve, reject) => {
      const client = window.tt
      if (typeof client?.requestAccess !== 'function') {
        reject(new Error('Open this page from the Synvo app inside Lark to connect.'))
        return
      }
      let settled = false
      const finish = (callback: () => void) => {
        if (settled) return
        settled = true
        callback()
      }
      client.requestAccess({
        appID: appId,
        scopeList: ['offline_access'],
        state,
        success: (result) => {
          if (
            typeof result.code === 'string' &&
            result.code.length > 0 &&
            result.state === state
          ) {
            const authorizationCode = result.code
            finish(() => resolve(authorizationCode))
            return
          }
          finish(() => reject(new Error('Lark returned an invalid authorization response.')))
        },
        fail: () => finish(() => reject(new Error('Lark authorization was cancelled or unavailable.'))),
      })
    }),
}

function hasRequestAccess() {
  return typeof window.tt?.requestAccess === 'function'
}

function waitForRequestAccess(signal?: AbortSignal): Promise<boolean> {
  if (hasRequestAccess()) return Promise.resolve(true)
  if (signal?.aborted) return Promise.resolve(false)

  return new Promise((resolve) => {
    const deadline = Date.now() + bridgeWaitMilliseconds
    let timer = 0

    const finish = (available: boolean) => {
      window.clearTimeout(timer)
      signal?.removeEventListener('abort', onAbort)
      resolve(available)
    }
    const onAbort = () => finish(false)
    const check = () => {
      if (hasRequestAccess()) {
        finish(true)
        return
      }
      if (Date.now() >= deadline) {
        finish(false)
        return
      }
      timer = window.setTimeout(check, bridgePollMilliseconds)
    }

    signal?.addEventListener('abort', onAbort, { once: true })
    check()
  })
}
