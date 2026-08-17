import { useEffect, useState } from 'react'
import { getServiceStatus, type ServiceStatus } from './api/status'
import { ServiceStatusCard } from './components/ServiceStatusCard'

type ConnectionState =
  | { kind: 'loading' }
  | { kind: 'ready'; status: ServiceStatus }
  | { kind: 'error'; message: string }

function App() {
  const [attempt, setAttempt] = useState(0)
  const [connection, setConnection] = useState<ConnectionState>({ kind: 'loading' })

  useEffect(() => {
    const controller = new AbortController()
    setConnection({ kind: 'loading' })

    getServiceStatus(controller.signal)
      .then((status) => setConnection({ kind: 'ready', status }))
      .catch((error: unknown) => {
        if (controller.signal.aborted) {
          return
        }

        const message = error instanceof Error ? error.message : 'Unexpected connection error'
        setConnection({ kind: 'error', message })
      })

    return () => controller.abort()
  }, [attempt])

  return (
    <main className="min-h-svh bg-slate-950 px-5 py-8 text-slate-100 sm:px-8 sm:py-12">
      <div className="mx-auto flex min-h-[calc(100svh-4rem)] max-w-5xl flex-col">
        <header className="flex items-center gap-3">
          <div
            aria-hidden="true"
            className="grid size-10 place-items-center rounded-xl bg-cyan-400 font-semibold text-slate-950 shadow-lg shadow-cyan-400/20"
          >
            S
          </div>
          <div>
            <p className="font-semibold tracking-tight">Synvo AI Assistant</p>
            <p className="text-sm text-slate-400">Lark-native foundation</p>
          </div>
        </header>

        <section className="my-auto grid gap-10 py-16 lg:grid-cols-[1.35fr_0.65fr] lg:items-end">
          <div>
            <p className="mb-4 text-sm font-medium uppercase tracking-[0.2em] text-cyan-300">
              Phase 0
            </p>
            <h1 className="max-w-3xl text-4xl font-semibold tracking-[-0.04em] text-white sm:text-6xl">
              The foundation is ready for useful agent workflows.
            </h1>
            <p className="mt-6 max-w-2xl text-base leading-7 text-slate-300 sm:text-lg">
              React H5, Spring Boot, and PostgreSQL are connected through one small,
              maintainable vertical slice.
            </p>
          </div>

          <ServiceStatusCard
            connection={connection}
            onRetry={() => setAttempt((current) => current + 1)}
          />
        </section>

        <footer className="border-t border-white/10 pt-5 text-sm text-slate-500">
          Lark and model integrations remain safely disabled in this foundation phase.
        </footer>
      </div>
    </main>
  )
}

export default App
