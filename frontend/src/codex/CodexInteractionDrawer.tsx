import { useEffect, useRef, useState } from 'react'
import { useModalFocus } from '../workspace/useModalFocus'
import type {
  CodexInteraction,
  CodexInteractionDecision,
  CodexInteractionField,
} from '../api/codex'

export function CodexInteractionDrawer({
  interaction,
  submitting,
  error,
  onDecide,
}: {
  interaction: CodexInteraction
  submitting: boolean
  error: string | null
  onDecide: (
    decision: CodexInteractionDecision,
    formValues: Record<string, string>,
  ) => Promise<void>
}) {
  const [formState, setFormState] = useState(() => ({ interactionId: interaction.interactionId, values: initialFormValues(interaction) }))
  const formValues = formState.interactionId === interaction.interactionId ? formState.values : initialFormValues(interaction)
  const drawerRef = useRef<HTMLElement>(null)
  useModalFocus(drawerRef, true)
  const [expired, setExpired] = useState(() => isExpired(interaction.expiresAt))
  const detail = interaction.detail
  const fields = detail?.fields ?? []
  const needsInput = (
    interaction.kind === 'MCP_ELICITATION' || interaction.kind === 'MCP_TOOL_APPROVAL'
  ) && fields.length > 0



  useEffect(() => {
    const expiresAt = Date.parse(interaction.expiresAt)
    if (Number.isNaN(expiresAt)) {
      setExpired(false)
      return
    }
    let timeout: number | undefined
    const refresh = () => {
      const remaining = expiresAt - Date.now()
      setExpired(remaining <= 0)
      if (remaining > 0) timeout = window.setTimeout(refresh, Math.min(remaining, 60_000))
    }
    refresh()
    return () => window.clearTimeout(timeout)
  }, [interaction.expiresAt])

  return (
    <div className="codex-interaction-backdrop">
      <section
        ref={drawerRef}
        className="codex-interaction-drawer"
        role="dialog"
        aria-modal="true"
        aria-busy={submitting}
        aria-labelledby="codex-interaction-title"
        aria-describedby="codex-interaction-reason"
        tabIndex={-1}
      >
        <header>
          <p>Action required</p>
          <h2 id="codex-interaction-title">Review {readable(interaction.category)}</h2>
        </header>

        <dl className="codex-interaction-summary">
          <div><dt>Workspace</dt><dd>{interaction.workspaceName}</dd></div>
          <div><dt>Permission</dt><dd>{readable(interaction.permissionScope)}</dd></div>
          <div><dt>Expires</dt><dd><time dateTime={interaction.expiresAt}>{expired ? 'Expired' : formatTime(interaction.expiresAt)}</time></dd></div>
        </dl>
        <p id="codex-interaction-reason" className="codex-interaction-reason">{interaction.reason}</p>

        {expired && (
          <div className="codex-interaction-expired" role="alert">
            This approval request has expired. Refresh the task to see its current state.
          </div>
        )}

        {detail?.command && (
          <div className="codex-sensitive-detail">
            <span>Exact command</span>
            <code>{detail.command}</code>
          </div>
        )}
        {detail && detail.affectedPaths.length > 0 && (
          <div className="codex-sensitive-detail">
            <span>Affected workspace paths</span>
            <ul>{detail.affectedPaths.map((path) => <li key={path}><code>{path}</code></li>)}</ul>
          </div>
        )}
        {(detail?.mcpServer || detail?.mcpTool) && (
          <div className="codex-sensitive-detail">
            <span>MCP action</span>
            <p>{[detail.mcpServer, detail.mcpTool].filter(Boolean).join(' · ')}</p>
          </div>
        )}
        {detail?.message && <p className="codex-interaction-message">{detail.message}</p>}
        {detail?.elicitationUrl && (
          <a
            className="codex-elicitation-link"
            href={detail.elicitationUrl}
            target="_blank"
            rel="noreferrer noopener"
          >
            Open the allowlisted MCP request at {externalHost(detail.elicitationUrl)}
          </a>
        )}

        {needsInput && (
          <fieldset className="codex-elicitation-fields">
            <legend>Requested input</legend>
            {fields.map((field) => (
              <ElicitationField
                key={field.name}
                field={field}
                value={formValues[field.name] ?? ''}
                disabled={submitting}
                onChange={(value) => setFormState((current) => ({
                  interactionId: interaction.interactionId,
                  values: { ...(current.interactionId === interaction.interactionId ? current.values : initialFormValues(interaction)), [field.name]: value },
                }))}
              />
            ))}
          </fieldset>
        )}
        {error && <div className="codex-inline-error" role="alert">{error}</div>}

        <div className="codex-interaction-actions">
          {interaction.availableDecisions.map((decision, index) => (
            <button
              key={decision}
              data-modal-initial={index === 0 ? true : undefined}
              type="button"
              data-decision={decision.toLowerCase()}
              disabled={submitting || expired || (
                needsInput && isApproval(decision) && !isFormComplete(fields, formValues)
              )}
              onClick={() => void onDecide(
                decision,
                needsInput && isApproval(decision) ? formValues : {},
              ).catch(() => {})}
            >
              {decisionLabel(decision)}
            </button>
          ))}
        </div>
        {submitting && <p className="codex-interaction-submitting" role="status">Submitting your decision…</p>}
        <p className="codex-interaction-notice">
          Credentials, environment secrets, sensitive file contents, and unrestricted command output are never shown here.
        </p>
      </section>
    </div>
  )
}

function isExpired(timestamp: string) {
  const expiresAt = Date.parse(timestamp)
  return !Number.isNaN(expiresAt) && expiresAt <= Date.now()
}

function ElicitationField({
  field,
  value,
  disabled,
  onChange,
}: {
  field: CodexInteractionField
  value: string
  disabled: boolean
  onChange: (value: string) => void
}) {
  if (field.type === 'BOOLEAN') {
    return (
      <label className="codex-elicitation-checkbox">
        <input
          type="checkbox"
          checked={value === 'true'}
          disabled={disabled}
          onChange={(event) => onChange(event.target.checked ? 'true' : 'false')}
        />
        <span>{field.label}{field.required ? ' (required)' : ''}</span>
      </label>
    )
  }
  if (field.type === 'SELECT') {
    return (
      <label className="codex-elicitation-field">
        <span>{field.label}{field.required ? ' (required)' : ''}</span>
        <select value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)}>
          <option value="">Select…</option>
          {field.options.map((option) => <option key={option} value={option}>{option}</option>)}
        </select>
      </label>
    )
  }
  return (
    <label className="codex-elicitation-field">
      <span>{field.label}{field.required ? ' (required)' : ''}</span>
      <input
        type={field.type === 'TEXT' ? 'text' : 'number'}
        step={field.type === 'INTEGER' ? '1' : 'any'}
        value={value}
        maxLength={field.type === 'TEXT' ? field.maxLength : undefined}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
      />
    </label>
  )
}

function isFormComplete(
  fields: CodexInteractionField[],
  values: Record<string, string>,
) {
  return fields.every((field) => !field.required || (
    field.type === 'BOOLEAN' ? field.name in values : Boolean(values[field.name]?.trim())
  ))
}

function isApproval(decision: CodexInteractionDecision) {
  return decision === 'APPROVE_ONCE'
}

function decisionLabel(decision: CodexInteractionDecision) {
  switch (decision) {
    case 'APPROVE_ONCE': return 'Approve once'
    case 'DECLINE': return 'Decline'
    case 'CANCEL': return 'Cancel task'
  }
}

function readable(value: string) {
  return value.replaceAll('_', ' ').toLowerCase()
}

function externalHost(value: string) {
  try {
    return new URL(value).host
  } catch {
    return 'the external service'
  }
}

function formatTime(timestamp: string) {
  const date = new Date(timestamp)
  return Number.isNaN(date.getTime()) ? 'Soon' : new Intl.DateTimeFormat('en-SG', {
    hour: '2-digit', minute: '2-digit', hourCycle: 'h23', timeZone: 'Asia/Singapore',
  }).format(date)
}

function initialFormValues(interaction: CodexInteraction): Record<string, string> {
  return Object.fromEntries((interaction.detail?.fields ?? []).filter((field) => field.type === 'BOOLEAN').map((field) => [field.name, 'false']))
}
