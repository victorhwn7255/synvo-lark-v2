import type { BotConnection } from '../api/lark'
import type { CodexStatus, CodexWorkspace } from '../api/codex'
import { ConnectedToolIcon, FolderIcon, SettingsIcon, ShieldIcon } from './visuals'

export function SettingsView({ botConnection, busy, onSignOut, status, workspaces }: {
  botConnection: BotConnection
  busy: boolean
  onSignOut: () => void
  status?: CodexStatus | null
  workspaces?: CodexWorkspace[]
}) {
  const account = status?.account
  const planLabel = account?.plan === 'prolite' ? 'Pro' : account?.plan
  const usedPercent = account?.usedPercent
  const hasUsage = typeof usedPercent === 'number' && Number.isFinite(usedPercent) && usedPercent >= 0 && usedPercent <= 100
  const resetDate = account?.resetsAt ? new Date(account.resetsAt) : null
  return (
    <section className="workspace-settings workspace-themed-scrollbar" aria-labelledby="settings-heading" tabIndex={0}>
      <div className="workspace-settings__content">
      <header className="workspace-settings__intro">
        <div className="workspace-settings__eyebrow"><SettingsIcon /><span>Your workspace, connected</span></div>
        <h2 id="settings-heading">Settings</h2>
        <p>Manage your connection and review account usage and workspace access.</p>
      </header>
      <div className="workspace-settings__overview">
      <article className="workspace-settings__section" aria-labelledby="settings-connection-heading">
          <header className="workspace-settings__section-heading"><span className="workspace-settings__icon"><ConnectedToolIcon /></span><h3 id="settings-connection-heading">Connection</h3></header>
          <dl className="workspace-settings__rows">
            <div><dt>H5 connection</dt><dd>Connected · Lark identity authorized</dd></div>
            <div><dt>Codex readiness</dt><dd><span className="workspace-settings__status" data-ready={status?.state === 'READY'}>{readinessLabel(status)}</span></dd></div>
          </dl>
          <div className="workspace-settings__connection-action"><button className="workspace-danger-button" type="button" disabled={busy} onClick={onSignOut}>
            {busy ? 'Disconnecting…' : 'Disconnect Synvo'}
          </button></div>
      </article>
      <article className="workspace-settings__section" aria-labelledby="settings-account-heading">
          <header className="workspace-settings__section-heading"><span className="workspace-settings__icon"><ShieldIcon /></span><h3 id="settings-account-heading">Account &amp; usage</h3></header>
          <dl className="workspace-settings__rows">
            <div><dt>Account</dt><dd>{account?.authenticationRequired ? 'Authentication required' : planLabel ?? account?.authentication ?? 'Account information unavailable'}</dd></div>
            <div><dt>Usage</dt><dd>{hasUsage ? `${usedPercent}% used` : 'Usage information unavailable'}</dd></div>
          </dl>
          {hasUsage && <div className="workspace-settings__usage" role="meter" aria-label="Account usage" aria-valuemin={0} aria-valuemax={100} aria-valuenow={usedPercent} aria-valuetext={`${usedPercent}% used`}><span style={{ width: `${usedPercent}%` }} /></div>}
          {resetDate && !Number.isNaN(resetDate.getTime()) && <p className="workspace-settings__reset">Resets <time dateTime={account!.resetsAt!}>{resetDate.toLocaleString(undefined, { day: 'numeric', month: 'short', year: 'numeric', hour: 'numeric', minute: '2-digit', timeZoneName: 'short' })}</time></p>}
      </article>
      </div>
      <article className="workspace-settings__section" aria-labelledby="settings-workspaces-heading">
          <header className="workspace-settings__section-heading"><span className="workspace-settings__icon"><FolderIcon /></span><h3 id="settings-workspaces-heading">Configured workspaces</h3></header>
          <p>New tasks start in Read Only. Permitted edits and commands stay inside the selected workspace; external access remains blocked.</p>
          {workspaces?.length ? <dl className="workspace-settings__rows workspace-settings__workspaces">{workspaces.map((workspace) => (
            <div key={workspace.id}><dt><FolderIcon />{workspace.displayName}</dt><dd><span className="workspace-settings__access">{workspace.writeEnabled ? 'Read Only or Edit workspace files' : 'Read Only'}</span></dd></div>
          ))}</dl> : <p>Workspace information unavailable.</p>}
          <p className="workspace-settings__policy"><ShieldIcon /><span>One-time decisions apply only to the displayed action. They do not expand workspace access.</span></p>
      </article>
      <details className="workspace-settings__diagnostics">
        <summary>Technical diagnostics</summary>
        <dl className="workspace-settings__rows">
          <div><dt>Model</dt><dd>{status?.model ?? 'Unavailable'}</dd></div>
          <div><dt>Runtime version</dt><dd>{status?.runtimeVersion ?? 'Unavailable'}</dd></div>
          <div><dt>Native Lark channel</dt><dd>{connectionLabel(botConnection)}</dd></div>
        </dl>
        <p>The native channel is separate from this authenticated H5 workspace.</p>
      </details>
      </div>
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
