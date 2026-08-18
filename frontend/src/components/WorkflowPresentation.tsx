import type { WorkflowPresentation } from '../api/presentations'

export function WorkflowPresentationList({
  presentations,
}: {
  presentations: WorkflowPresentation[]
}) {
  return (
    <div className="workflow-presentations" aria-label="Workflow output">
      {presentations.map((presentation) => (
        <WorkflowPresentationView key={presentation.id} presentation={presentation} />
      ))}
    </div>
  )
}

export function WorkflowPresentationView({
  presentation,
}: {
  presentation: WorkflowPresentation
}) {
  switch (presentation.kind) {
    case 'activity':
      return (
        <article className="workflow-card workflow-card--activity" data-status={presentation.status}>
          <span className="workflow-status-dot" aria-hidden="true" />
          <div>
            <p>{presentation.operationLabel ?? 'Synvo activity'}</p>
            <h3>{presentation.label}</h3>
          </div>
          <StatusLabel status={presentation.status} />
        </article>
      )
    case 'citation':
      return (
        <article className="workflow-card">
          <p>Citation</p>
          <h3>{presentation.sourceName}</h3>
          {presentation.excerpt && <blockquote>{presentation.excerpt}</blockquote>}
          <LarkResourceLink url={presentation.url} sourceName={presentation.sourceName} />
        </article>
      )
    case 'source':
      return (
        <article className="workflow-card">
          <p>{sourceTypeLabel(presentation.sourceType)}</p>
          <h3>{presentation.sourceName}</h3>
          <div className="workflow-card__copy">{presentation.summary}</div>
          <LarkResourceLink url={presentation.url} sourceName={presentation.sourceName} />
        </article>
      )
    case 'artifact':
      return (
        <article className="workflow-card workflow-card--artifact" data-status={presentation.status}>
          <div className="workflow-card__heading">
            <div>
              <p>{presentation.artifactType === 'research' ? 'Research artifact' : 'Execution-plan artifact'}</p>
              <h3>{presentation.title}</h3>
            </div>
            <StatusLabel status={presentation.status} />
          </div>
          <div className="workflow-card__copy">{presentation.summary}</div>
          {presentation.sections.map((section) => (
            <section key={section.heading} className="workflow-card__section">
              <h4>{section.heading}</h4>
              <p>{section.body}</p>
            </section>
          ))}
        </article>
      )
    case 'confirmation':
      return (
        <article className="workflow-card workflow-card--confirmation">
          <p>Review required</p>
          <h3>{presentation.title}</h3>
          <div className="workflow-card__copy">{presentation.description}</div>
          <dl><dt>Proposed operation</dt><dd>{presentation.operationLabel}</dd></dl>
          <button type="button" disabled title="No Phase 2 write action is registered">
            {presentation.confirmLabel}
          </button>
          <small>Review only. No Lark action can run from this Phase 2 preview.</small>
        </article>
      )
    case 'unavailable':
      return (
        <article className="workflow-card workflow-card--unavailable" role="status">
          <p>Unavailable</p>
          <h3>{presentation.title}</h3>
          <div className="workflow-card__copy">{presentation.description}</div>
        </article>
      )
    case 'error':
      return (
        <article className="workflow-card workflow-card--error" role="alert">
          <p>Could not complete</p>
          <h3>{presentation.title}</h3>
          <div className="workflow-card__copy">{presentation.message}</div>
        </article>
      )
  }
}

function LarkResourceLink({ url, sourceName }: { url: string; sourceName: string }) {
  return <a href={url} target="_blank" rel="noreferrer" aria-label={`Open ${sourceName} in Lark`}>Open in Lark</a>
}

function StatusLabel({ status }: { status: string }) {
  return <span className="workflow-status-label">{status.replace('_', ' ')}</span>
}

function sourceTypeLabel(sourceType: string) {
  return `${sourceType.charAt(0).toUpperCase()}${sourceType.slice(1)} source`
}
