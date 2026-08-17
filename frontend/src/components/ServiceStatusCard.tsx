import type { ServiceStatus } from '../api/status'

type ConnectionState =
  | { kind: 'loading' }
  | { kind: 'ready'; status: ServiceStatus }
  | { kind: 'error'; message: string }

interface ServiceStatusCardProps {
  connection: ConnectionState
  onRetry: () => void
}

export function ServiceStatusCard({ connection, onRetry }: ServiceStatusCardProps) {
  return (
    <aside className="rounded-2xl border border-white/10 bg-white/[0.06] p-5 shadow-2xl shadow-black/20 backdrop-blur sm:p-6">
      <p className="text-xs font-semibold uppercase tracking-[0.18em] text-slate-400">
        System status
      </p>

      {connection.kind === 'loading' && (
        <div className="mt-5 flex items-center gap-3" role="status">
          <span className="size-2.5 animate-pulse rounded-full bg-amber-300" />
          <div>
            <p className="font-medium text-white">Connecting to backend</p>
            <p className="mt-1 text-sm text-slate-400">Checking the Phase 0 vertical slice…</p>
          </div>
        </div>
      )}

      {connection.kind === 'ready' && (
        <div className="mt-5 flex items-center gap-3" role="status">
          <span className="size-2.5 rounded-full bg-emerald-400 shadow-[0_0_18px] shadow-emerald-400" />
          <div>
            <p className="font-medium text-white">Backend connected</p>
            <p className="mt-1 font-mono text-xs text-slate-400">
              {connection.status.service} · {connection.status.status}
            </p>
          </div>
        </div>
      )}

      {connection.kind === 'error' && (
        <div className="mt-5" role="alert">
          <div className="flex items-start gap-3">
            <span className="mt-1 size-2.5 shrink-0 rounded-full bg-rose-400" />
            <div>
              <p className="font-medium text-white">Backend unavailable</p>
              <p className="mt-1 text-sm text-slate-400">{connection.message}</p>
            </div>
          </div>
          <button
            type="button"
            className="mt-5 rounded-lg bg-white px-3.5 py-2 text-sm font-medium text-slate-950 transition hover:bg-cyan-100 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-cyan-300"
            onClick={onRetry}
          >
            Try again
          </button>
        </div>
      )}
    </aside>
  )
}
