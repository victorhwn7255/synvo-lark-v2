import type { BotConnection } from '../api/lark'
import type { CodexStatus, CodexWorkspace } from '../api/codex'
import { FolderIcon, ShieldIcon } from './visuals'

export function SettingsView({ botConnection, busy, onSignOut, status, workspaces }: {
  botConnection: BotConnection
  busy: boolean
  onSignOut: () => void
  status?: CodexStatus | null
  workspaces?: CodexWorkspace[]
}) {
  const account = status?.account
  const usedPercent = account?.usedPercent
  const hasUsage = typeof usedPercent === 'number' && Number.isFinite(usedPercent)
  const resetDate = account?.resetsAt ? new Date(account.resetsAt) : null
  return (
    <section className="workspace-settings" aria-labelledby="settings-heading">
      <div className="workspace-settings__intro">
        <p className="workspace-overline">Synvo AI Assistant</p>
        <h2 id="settings-heading">Connection and workspace access</h2>
        <p>Review your connection, usage, and the boundaries for your work.</p>
      </div>
      <article className="workspace-settings__section">
        <div className="workspace-settings__icon"><ShieldIcon /></div>
        <div>
          <h3>Connection</h3>
          <dl>
            <div><dt>H5 connection</dt><dd>Connected · Lark identity authorized</dd></div>
            <div><dt>Codex readiness</dt><dd>{readinessLabel(status)}</dd></div>
          </dl>
          <button className="workspace-danger-button" type="button" disabled={busy} onClick={onSignOut}>
            {busy ? 'Disconnecting…' : 'Disconnect Synvo'}
          </button>
        </div>
      </article>
      <article className="workspace-settings__section">
        <div className="workspace-settings__icon"><ShieldIcon /></div>
        <div>
          <h3>Account &amp; usage</h3>
          <dl>
            <div><dt>Account</dt><dd>{account?.authenticationRequired ? 'Authentication required' : account?.plan ?? account?.authentication ?? 'Account information unavailable'}</dd></div>
            <div><dt>Usage</dt><dd>{hasUsage ? `${usedPercent}% used` : 'Usage information unavailable'}</dd></div>
            {resetDate && !Number.isNaN(resetDate.getTime()) && <div><dt>Resets</dt><dd><time dateTime={account!.resetsAt!}>{resetDate.toLocaleString()}</time></dd></div>}
          </dl>
        </div>
      </article>
      <article className="workspace-settings__section">
        <div className="workspace-settings__icon"><FolderIcon /></div>
        <div>
          <h3>Configured workspaces</h3>
          <p>New tasks start in Read Only. Permitted edits and commands stay inside the selected workspace; external access remains blocked.</p>
          {workspaces?.length ? <dl>{workspaces.map((workspace) => (
            <div key={workspace.id}><dt>{workspace.displayName}</dt><dd>{workspace.writeEnabled ? 'Read Only or Edit workspace files' : 'Read Only'}</dd></div>
          ))}</dl> : <p>Workspace information unavailable.</p>}
          <p>One-time decisions apply only to the displayed action. They do not expand workspace access.</p>
        </div>
      </article>
      <details className="workspace-settings__diagnostics">
        <summary>Technical diagnostics</summary>
        <dl>
          <div><dt>Model</dt><dd>{status?.model ?? 'Unavailable'}</dd></div>
          <div><dt>Runtime version</dt><dd>{status?.runtimeVersion ?? 'Unavailable'}</dd></div>
          <div><dt>Native Lark channel</dt><dd>{connectionLabel(botConnection)}</dd></div>
        </dl>
        <p>The native channel is separate from this authenticated H5 workspace.</p>
      </details>
    </section>
  )
}

function readinessLabel(status: CodexStatus | null | undefined) {
  switch (status?.state) {
    case 'READY': return 'Ready'
    case 'RECOVERING': return 'Reconnecting automatically'
    case 'AUTHENTICATION_REQUIRED': return 'Authentication required'
    case 'PROTOCOL_INCOMPATIBLE': return 'Runtime needs attention'
    case 'DISABLED': return 'Disabled in this environment'
    default: return 'Unavailable'
  }
}

function connectionLabel(connection: BotConnection) {
  switch (connection) {
    case 'connected': return 'Connected'
    case 'connecting': return 'Connecting'
    case 'reconnecting': return 'Reconnecting'
    case 'failed': return 'Needs attention'
    case 'disabled': return 'Disabled'
  }
}
