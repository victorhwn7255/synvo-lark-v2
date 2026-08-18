import { useCallback, useEffect, useState } from 'react'
import { larkApi, type LarkApi, type LarkConnection } from './api/lark'
import { ConnectionPage } from './components/ConnectionPage'
import { Workspace } from './components/Workspace'
import { larkH5, type LarkH5Adapter } from './lark/h5'

interface AppProps {
  api?: LarkApi
  h5?: LarkH5Adapter
}

type AppState =
  | { kind: 'loading' }
  | { kind: 'ready'; connection: LarkConnection; error: string | null; busy: boolean }
  | { kind: 'error'; message: string }

function App({ api = larkApi, h5 = larkH5 }: AppProps) {
  const [state, setState] = useState<AppState>({ kind: 'loading' })
  const [insideLark, setInsideLark] = useState(h5.isAvailable())

  const authorize = useCallback(
    async (connection: LarkConnection) => {
      setState({ kind: 'ready', connection, error: null, busy: true })
      try {
        const bootstrap = await api.bootstrap()
        if (!bootstrap.larkEnabled || !bootstrap.appId || !bootstrap.state) {
          throw new Error('Lark authorization is not configured yet.')
        }
        const code = await h5.requestAuthorizationCode(bootstrap.appId, bootstrap.state)
        const connected = await api.exchange(code, bootstrap.state, bootstrap.csrfToken)
        setState({ kind: 'ready', connection: connected, error: null, busy: false })
      } catch (error: unknown) {
        setState({ kind: 'ready', connection, error: safeMessage(error), busy: false })
      }
    },
    [api, h5],
  )

  const load = useCallback(
    async (autoAuthorize: boolean, signal?: AbortSignal, h5Available = h5.isAvailable()) => {
      setState({ kind: 'loading' })
      try {
        const connection = await api.getConnection(signal)
        if (
          autoAuthorize &&
          h5Available &&
          connection.larkEnabled &&
          connection.userAuthorization === 'unauthorized'
        ) {
          await authorize(connection)
          return
        }
        setState({ kind: 'ready', connection, error: null, busy: false })
      } catch (error: unknown) {
        if (signal?.aborted) return
        setState({ kind: 'error', message: safeMessage(error) })
      }
    },
    [api, authorize, h5],
  )

  useEffect(() => {
    const controller = new AbortController()
    const initialize = async () => {
      const availableAtStartup = h5.isAvailable()
      setInsideLark(availableAtStartup)
      await load(true, controller.signal, availableAtStartup)
      if (availableAtStartup || controller.signal.aborted) return

      const becameAvailable = await h5.waitUntilAvailable(controller.signal)
      if (!becameAvailable || controller.signal.aborted) return
      setInsideLark(true)
      await load(true, controller.signal, true)
    }
    void initialize()
    return () => controller.abort()
  }, [h5, load])

  const signOut = async (connection: LarkConnection) => {
    setState({ kind: 'ready', connection, error: null, busy: true })
    try {
      const bootstrap = await api.bootstrap()
      const signedOut = await api.signOut(bootstrap.csrfToken)
      setState({ kind: 'ready', connection: signedOut, error: null, busy: false })
    } catch (error: unknown) {
      setState({ kind: 'ready', connection, error: safeMessage(error), busy: false })
    }
  }

  if (state.kind === 'loading') return <LoadingPage />
  if (state.kind === 'error') return <ErrorPage message={state.message} onRetry={() => void load(true)} />

  if (state.connection.userAuthorization === 'connected') {
    return (
      <Workspace
        botConnection={state.connection.botConnection}
        busy={state.busy}
        userAvatarUrl={state.connection.user?.avatarUrl ?? null}
        onSignOut={() => void signOut(state.connection)}
      />
    )
  }

  return (
    <ConnectionPage
      connection={state.connection}
      insideLark={insideLark}
      busy={state.busy}
      error={state.error}
      onConnect={() => void authorize(state.connection)}
      onRetry={() => void authorize(state.connection)}
      onSignOut={() => void signOut(state.connection)}
    />
  )
}

function LoadingPage() {
  return (
    <main className="centered-state" role="status" aria-live="polite">
      <div className="brand__mark brand__mark--large" aria-hidden="true"><span /><span /></div>
      <div className="loading-line"><span /></div>
      <h1>Preparing your Synvo workspace</h1>
      <p>Checking the secure Lark connection…</p>
    </main>
  )
}

function ErrorPage({ message, onRetry }: { message: string; onRetry: () => void }) {
  return (
    <main className="centered-state" role="alert">
      <div className="error-mark" aria-hidden="true">!</div>
      <h1>Synvo is temporarily unavailable</h1>
      <p>{message}</p>
      <button className="button button--primary" type="button" onClick={onRetry}>Try again</button>
    </main>
  )
}

function safeMessage(error: unknown) {
  return error instanceof Error ? error.message : 'An unexpected connection error occurred.'
}

export default App
