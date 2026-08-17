import type { BotConnection, LarkConnection } from '../api/lark'
import { StatusChip } from './StatusChip'

interface ConnectionPageProps {
  connection: LarkConnection
  insideLark: boolean
  busy: boolean
  error: string | null
  onConnect: () => void
  onRetry: () => void
  onSignOut: () => void
}

const botPresentation: Record<
  BotConnection,
  { label: string; tone: 'neutral' | 'working' | 'positive' | 'warning' | 'negative'; copy: string }
> = {
  disabled: { label: 'Disabled', tone: 'neutral', copy: 'Available when Lark is enabled locally.' },
  connecting: { label: 'Connecting', tone: 'working', copy: 'Establishing a private WebSocket channel.' },
  connected: { label: 'Live', tone: 'positive', copy: 'Ready for direct messages from Victor.' },
  reconnecting: { label: 'Reconnecting', tone: 'warning', copy: 'Restoring the Lark channel automatically.' },
  failed: { label: 'Needs attention', tone: 'negative', copy: 'The Lark channel could not connect.' },
}

export function ConnectionPage({
  connection,
  insideLark,
  busy,
  error,
  onConnect,
  onRetry,
  onSignOut,
}: ConnectionPageProps) {
  const bot = botPresentation[connection.botConnection]
  const isAuthorized = connection.userAuthorization === 'connected'
  const canConnect = connection.larkEnabled && !isAuthorized

  return (
    <div className="app-shell">
      <div aria-hidden="true" className="ambient ambient--one" />
      <div aria-hidden="true" className="ambient ambient--two" />

      <header className="site-header">
        <a className="brand" href="#main" aria-label="Synvo AI Assistant home">
          <span className="brand__mark" aria-hidden="true"><span /><span /></span>
          <span><strong>Synvo</strong><small>AI Assistant</small></span>
        </a>
        <StatusChip label={insideLark ? 'Running in Lark' : 'Browser preview'} tone={insideLark ? 'positive' : 'neutral'} />
      </header>

      <main id="main" className="connection-layout">
        <section className="hero-copy" aria-labelledby="page-title">
          <div className="eyebrow"><span aria-hidden="true" />Phase 1 · Secure connection</div>
          <h1 id="page-title">A natural workspace, connected to Lark.</h1>
          <p className="hero-copy__lede">Synvo brings conversations and permissioned actions together in one calm, Lark-native experience.</p>
          <div className="trust-note">
            <ShieldIcon />
            <p><strong>Private by design.</strong>Your Lark access tokens stay encrypted in the backend and never enter this interface.</p>
          </div>
        </section>

        <section className="connection-card" aria-labelledby="connection-title">
          <div className="connection-card__header">
            <div>
              <p className="overline">Connection center</p>
              <h2 id="connection-title">{isAuthorized ? `Welcome, ${connection.user?.displayName ?? 'Victor'}` : 'Connect Synvo'}</h2>
            </div>
            <div className={`signal-orb signal-orb--${bot.tone}`} aria-hidden="true"><span /></div>
          </div>

          <div className="status-list">
            <div className="status-row">
              <div className="status-row__icon"><MessageIcon /></div>
              <div className="status-row__copy">
                <div><h3>Assistant channel</h3><StatusChip label={bot.label} tone={bot.tone} /></div>
                <p>{bot.copy}</p>
              </div>
            </div>
            <div className="status-row">
              <div className="status-row__icon"><PersonIcon /></div>
              <div className="status-row__copy">
                <div>
                  <h3>Lark identity</h3>
                  <StatusChip label={isAuthorized ? 'Authorized' : connection.larkEnabled ? 'Not connected' : 'Disabled'} tone={isAuthorized ? 'positive' : 'neutral'} />
                </div>
                <p>{isAuthorized ? `Signed in securely as ${connection.user?.displayName ?? 'Victor'}.` : insideLark ? 'Authorize once to prepare permissioned Lark actions.' : 'Open the Synvo Web App inside Lark to authorize.'}</p>
              </div>
            </div>
          </div>

          {error && (
            <div className="inline-alert" role="alert">
              <span aria-hidden="true">!</span>
              <div><strong>Connection paused</strong><p>{error}</p></div>
            </div>
          )}

          <div className="connection-card__actions">
            {canConnect && insideLark && (
              <button className="button button--primary" type="button" onClick={error ? onRetry : onConnect} disabled={busy}>
                {busy ? <><Spinner /> Connecting securely…</> : error ? 'Try authorization again' : 'Connect with Lark'}
              </button>
            )}
            {canConnect && !insideLark && (
              <button className="button button--secondary" type="button" onClick={onRetry} disabled={busy}>Check again</button>
            )}
            {isAuthorized && (
              <button className="button button--quiet" type="button" onClick={onSignOut} disabled={busy}>{busy ? 'Signing out…' : 'Sign out of Synvo'}</button>
            )}
          </div>

          <p className="connection-card__footnote">{connection.larkEnabled ? 'Victor-only pilot · Direct messages only' : 'Local foundation mode · Lark disabled'}</p>
        </section>
      </main>
    </div>
  )
}

function ShieldIcon() {
  return <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M12 3 5.5 5.7v5.5c0 4.1 2.7 7.8 6.5 9.2 3.8-1.4 6.5-5.1 6.5-9.2V5.7L12 3Z"/><path d="m9.3 11.8 1.8 1.8 3.8-4"/></svg>
}

function MessageIcon() {
  return <svg aria-hidden="true" viewBox="0 0 24 24"><path d="M5 5.5h14v10H9l-4 3v-13Z"/><path d="M8.5 9h7M8.5 12h4.5"/></svg>
}

function PersonIcon() {
  return <svg aria-hidden="true" viewBox="0 0 24 24"><circle cx="12" cy="8" r="3.5"/><path d="M5.5 20c.5-4 2.6-6 6.5-6s6 2 6.5 6"/></svg>
}

function Spinner() {
  return <span className="spinner" aria-hidden="true" />
}
