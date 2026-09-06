import { useEffect, useState } from 'react'
import { ArtifactIcon, CheckIcon, CloseIcon, CommandIcon, ConnectedToolIcon, StopIcon } from '../workspace/visuals'
import { decisionReceiptLabel, projectAgentActivity, type ActivityInput, type DecisionReceipt } from './activityPresentation'
export type { CodexSteeringMilestoneStatus } from './activityPresentation'

export function CodexActivityTimeline({
  presentation,
  operationId,
  startedAt,
  terminalAt,
  receipts = [],
  decisionAnnouncement = '',
  ...input
}: ActivityInput & {
  presentation?: ReturnType<typeof projectAgentActivity>
  operationId?: string
  startedAt?: string
  // Only a timestamp from an authoritative terminal REST record, never a
  // running record whose status was projected locally from terminal SSE.
  terminalAt?: string | null
  receipts?: DecisionReceipt[]
  decisionAnnouncement?: string
}) {
  const projected = presentation ?? projectAgentActivity(input)
  const { steps, tools, terminal, tone, title } = projected
  const [expanded, setExpanded] = useState(!terminal || tone === 'failed')
  useEffect(() => {
    setExpanded(!terminal || tone === 'failed')
  }, [operationId, terminal, tone])

  return (
    <section className="codex-live-activity" data-state={tone} aria-label="Agent activity">
      <button className="codex-live-activity__toggle" type="button"
        aria-label={expanded ? 'Hide agent activity' : 'Show agent activity'} aria-expanded={expanded}
        onClick={() => setExpanded((visible) => !visible)}>
        <span className="codex-live-activity__state" aria-hidden="true" />
        <span className="codex-live-activity__heading"><span>Agent activity</span><strong>{title}</strong></span>
        <span className="codex-live-activity__summary">{steps.length} {steps.length === 1 ? 'milestone' : 'milestones'}</span>
        <svg className="codex-live-activity__chevron" viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="m5 7.5 5 5 5-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
      </button>
      <span className="sr-only" role="status" aria-live="polite" aria-atomic="true">{title}</span>
      <span className="sr-only" role="status" aria-live="polite">{decisionAnnouncement}</span>
      <ElapsedTime key={operationId ?? 'unattached'} startedAt={startedAt} terminal={terminal} terminalAt={terminalAt} />
      {receipts.length > 0 && <div className="codex-decision-receipts" aria-label="Confirmed decisions">
        {receipts.map((receipt) => <p className="codex-decision-receipt" key={receipt.interactionId} data-decision={receipt.decision} data-new={Boolean(decisionAnnouncement) && receipt === receipts.at(-1)}>
          <span aria-hidden="true">{receipt.decision === 'APPROVE_ONCE' ? <CheckIcon /> : receipt.decision === 'DECLINE' ? <CloseIcon /> : <StopIcon />}</span>
          {decisionReceiptLabel(receipt.decision)}
        </p>)}
      </div>}
      {expanded && <div className="codex-live-activity__body">
        {tools.length > 0 && <div className="codex-tool-chips" aria-label="Observed tool activity">
          {tools.map((tool) => <details className="codex-tool-chip" key={tool.id} data-state={tool.state}>
            <summary><span aria-hidden="true">{tool.id === 'commands' ? <CommandIcon /> : tool.id === 'mcp' ? <ConnectedToolIcon /> : <ArtifactIcon />}</span>
              <strong>{tool.label}</strong><span>{Math.max(tool.started, tool.finished)}</span><span>{tool.state}</span>
            </summary>
            <div><p>{tool.started} starts · {tool.finished} completion events observed.</p>
              <p>Activity completion does not confirm a successful result. Review the response for the outcome.</p>
              <p>Safe event details are available in Task details.</p>
            </div>
          </details>)}
        </div>}
        {steps.length === 0 ? <p className="codex-live-activity__empty">Waiting for activity updates…</p>
          : <ol className="codex-live-activity__steps">{steps.map((step) => <li key={step.id} data-step={step.id} data-status={step.status}
            aria-current={step.status === 'current' || step.status === 'waiting' ? 'step' : undefined}>
            <span className="codex-live-activity__marker" aria-hidden="true">{step.status === 'completed' ? <CheckIcon /> : step.status === 'failed' ? <CloseIcon /> : step.status === 'stopped' ? <StopIcon /> : <svg viewBox="0 0 20 20" fill="currentColor"><circle cx="10" cy="10" r="3" /></svg>}</span>
            <div><strong>{step.title}</strong>{step.detail && <p>{step.detail}</p>}
              {step.id === 'analysis' && step.detail && <span>Model-provided summary; private reasoning is not shown.</span>}
              {step.status === 'waiting' && <span>Review in the approval panel</span>}
            </div>
          </li>)}</ol>}
      </div>}
    </section>
  )
}

function ElapsedTime({ startedAt, terminal, terminalAt }: { startedAt?: string; terminal: boolean; terminalAt?: string | null }) {
  const [now, setNow] = useState(Date.now)
  const start = startedAt ? Date.parse(startedAt) : NaN
  const end = terminalAt ? Date.parse(terminalAt) : NaN
  const validStart = Number.isFinite(start) && start <= Date.now()
  useEffect(() => {
    if (terminal || !validStart) return
    setNow(Date.now())
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [start, terminal, validStart])
  const until = terminal ? end : now
  if (!validStart || !Number.isFinite(until) || until < start || until > Date.now()) return null
  const seconds = Math.floor((until - start) / 1000)
  const duration = seconds >= 60 ? `${Math.floor(seconds / 60)}m ${seconds % 60}s` : `${seconds}s`
  return <p className="codex-live-activity__elapsed" title="Wall-clock time including waits">Elapsed {duration}</p>
}
