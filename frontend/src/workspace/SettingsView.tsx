import type { BotConnection } from '../api/lark'
import { FolderIcon, ShieldIcon } from './visuals'

export function SettingsView({
  botConnection,
  busy,
  onSignOut,
}: {
  botConnection: BotConnection
  busy: boolean
  onSignOut: () => void
}) {
  return (
    <section className="workspace-settings" aria-labelledby="settings-heading">
      <div className="workspace-settings__intro">
        <p className="workspace-overline">Workspace controls</p>
        <h2 id="settings-heading">Sources and permissions</h2>
        <p>Review the boundaries Synvo uses inside Lark. Your Lark account remains managed by Lark.</p>
      </div>

      <article className="workspace-settings__section">
        <div className="workspace-settings__icon"><FolderIcon /></div>
        <div>
          <h3>Knowledge Sources</h3>
          <p>Enterprise retrieval is limited to one configured folder in Lark Drive.</p>
          <dl>
            <div><dt>Source boundary</dt><dd>One configured Drive folder</dd></div>
            <div><dt>Research workflow</dt><dd>Planned for a later phase</dd></div>
          </dl>
        </div>
      </article>

      <article className="workspace-settings__section">
        <div className="workspace-settings__icon"><ShieldIcon /></div>
        <div>
          <h3>Lark Permissions</h3>
          <p>Synvo uses the existing encrypted Lark authorization and application scopes.</p>
          <dl>
            <div><dt>Lark identity</dt><dd>Authorized</dd></div>
            <div><dt>Assistant channel</dt><dd>{connectionLabel(botConnection)}</dd></div>
          </dl>
          <button className="workspace-danger-button" type="button" disabled={busy} onClick={onSignOut}>
            {busy ? 'Disconnecting…' : 'Disconnect Synvo'}
          </button>
        </div>
      </article>
    </section>
  )
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
