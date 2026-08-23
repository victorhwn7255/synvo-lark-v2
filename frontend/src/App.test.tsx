import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'
import type { LarkApi, LarkConnection } from './api/lark'
import type { LarkH5Adapter } from './lark/h5'

const unauthorized: LarkConnection = {
  larkEnabled: true,
  botConnection: 'connected',
  userAuthorization: 'unauthorized',
  user: null,
}

const connected: LarkConnection = {
  larkEnabled: true,
  botConnection: 'connected',
  userAuthorization: 'connected',
  user: { displayName: 'Victor', avatarUrl: 'https://example.com/victor.png' },
}

describe('App', () => {
  beforeEach(() => {
    vi.restoreAllMocks()
  })

  afterEach(cleanup)

  it('renders an intentional loading state before connection status arrives', () => {
    const api = apiWith({
      getConnection: vi.fn(() => new Promise<LarkConnection>(() => undefined)),
    })

    render(<App api={api} h5={h5OutsideLark()} workspaceAgentApi={null} />)

    expect(screen.getByRole('status')).toHaveTextContent('Preparing your Synvo workspace')
    expect(screen.getByText('Checking the secure Lark connection…')).toBeInTheDocument()
  })

  it('routes an authorized user into the workspace without duplicating Lark identity', async () => {
    render(<App api={apiWith({ getConnection: vi.fn().mockResolvedValue(connected) })} h5={h5OutsideLark()} workspaceAgentApi={null} />)

    expect(await screen.findByRole('main', { name: 'Synvo AI workspace' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'New conversation' })).toBeInTheDocument()
    expect(screen.queryByText('Welcome, Victor')).not.toBeInTheDocument()
    expect(screen.queryByText('Running in Lark')).not.toBeInTheDocument()
  })

  it('shows the safe browser-preview state outside Lark', async () => {
    render(<App api={apiWith({ getConnection: vi.fn().mockResolvedValue(unauthorized) })} h5={h5OutsideLark()} workspaceAgentApi={null} />)

    expect(await screen.findByRole('heading', { name: 'Connect Codex in Lark' })).toBeInTheDocument()
    expect(screen.getByText('Phase 3 · Codex in Lark')).toBeInTheDocument()
    expect(screen.queryByText(/permissioned Lark actions/i)).not.toBeInTheDocument()
    expect(screen.getByText('Open the Synvo Web App inside Lark to authorize.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Check again' })).toBeEnabled()
  })

  it('automatically completes the replaceable Lark H5 authorization flow', async () => {
    const api = apiWith({
      getConnection: vi.fn().mockResolvedValue(unauthorized),
      bootstrap: vi.fn().mockResolvedValue({
        larkEnabled: true,
        appId: 'cli-public-id',
        state: 'one-time-state',
        csrfToken: 'csrf-token',
      }),
      exchange: vi.fn().mockResolvedValue(connected),
    })
    const h5: LarkH5Adapter = {
      isAvailable: () => true,
      waitUntilAvailable: vi.fn().mockResolvedValue(true),
      requestAuthorizationCode: vi.fn().mockResolvedValue('short-lived-code'),
    }

    render(<App api={api} h5={h5} workspaceAgentApi={null} />)

    expect(await screen.findByRole('main', { name: 'Synvo AI workspace' })).toBeInTheDocument()
    expect(h5.requestAuthorizationCode).toHaveBeenCalledWith('cli-public-id', 'one-time-state')
    expect(api.exchange).toHaveBeenCalledWith('short-lived-code', 'one-time-state', 'csrf-token')
    expect(screen.queryByText('Running in Lark')).not.toBeInTheDocument()
  })

  it('authorizes when the Lark bridge becomes available after startup', async () => {
    let bridgeReady: ((available: boolean) => void) | undefined
    const api = apiWith({
      getConnection: vi.fn().mockResolvedValue(unauthorized),
      bootstrap: vi.fn().mockResolvedValue({
        larkEnabled: true,
        appId: 'cli-public-id',
        state: 'late-state',
        csrfToken: 'csrf-token',
      }),
      exchange: vi.fn().mockResolvedValue(connected),
    })
    const h5: LarkH5Adapter = {
      isAvailable: () => false,
      waitUntilAvailable: () => new Promise((resolve) => { bridgeReady = resolve }),
      requestAuthorizationCode: vi.fn().mockResolvedValue('late-code'),
    }

    render(<App api={api} h5={h5} workspaceAgentApi={null} />)

    expect(await screen.findByText('Browser preview')).toBeInTheDocument()
    bridgeReady?.(true)

    expect(await screen.findByRole('main', { name: 'Synvo AI workspace' })).toBeInTheDocument()
    expect(screen.queryByText('Running in Lark')).not.toBeInTheDocument()
    expect(api.exchange).toHaveBeenCalledWith('late-code', 'late-state', 'csrf-token')
  })

  it('offers a retry after a failed authorization exchange', async () => {
    const api = apiWith({
      getConnection: vi.fn().mockResolvedValue(unauthorized),
      bootstrap: vi.fn().mockResolvedValue({
        larkEnabled: true,
        appId: 'cli-public-id',
        state: 'state',
        csrfToken: 'csrf',
      }),
      exchange: vi.fn()
        .mockRejectedValueOnce(new Error('Authorization expired.'))
        .mockResolvedValueOnce(connected),
    })
    const h5: LarkH5Adapter = {
      isAvailable: () => true,
      waitUntilAvailable: vi.fn().mockResolvedValue(true),
      requestAuthorizationCode: vi.fn().mockResolvedValue('code'),
    }

    render(<App api={api} h5={h5} workspaceAgentApi={null} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Authorization expired.')
    fireEvent.click(screen.getByRole('button', { name: 'Try authorization again' }))

    expect(await screen.findByRole('main', { name: 'Synvo AI workspace' })).toBeInTheDocument()
    expect(api.exchange).toHaveBeenCalledTimes(2)
  })

  it('shows reconnecting in text rather than color alone', async () => {
    const reconnecting = { ...connected, botConnection: 'reconnecting' as const }
    render(<App api={apiWith({ getConnection: vi.fn().mockResolvedValue(reconnecting) })} h5={h5OutsideLark()} workspaceAgentApi={null} />)

    expect(await screen.findByText('The assistant channel is reconnecting automatically.')).toBeInTheDocument()
  })

  it('recovers from a backend connection error', async () => {
    const getConnection = vi.fn()
      .mockRejectedValueOnce(new Error('Network request failed'))
      .mockResolvedValueOnce(connected)

    render(<App api={apiWith({ getConnection })} h5={h5OutsideLark()} workspaceAgentApi={null} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Network request failed')
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(await screen.findByRole('main', { name: 'Synvo AI workspace' })).toBeInTheDocument()
  })

  it('signs out of Synvo without writing any browser token storage', async () => {
    const localStorageSpy = vi.spyOn(Storage.prototype, 'setItem')
    const api = apiWith({
      getConnection: vi.fn().mockResolvedValue(connected),
      bootstrap: vi.fn().mockResolvedValue({
        larkEnabled: true,
        appId: 'cli-public-id',
        state: 'state',
        csrfToken: 'csrf',
      }),
      signOut: vi.fn().mockResolvedValue(unauthorized),
    })

    render(<App api={api} h5={h5OutsideLark()} workspaceAgentApi={null} />)
    fireEvent.click(await screen.findByRole('button', { name: 'Settings' }))
    fireEvent.click(screen.getByRole('button', { name: 'Disconnect Synvo' }))

    expect(await screen.findByRole('heading', { name: 'Connect Codex in Lark' })).toBeInTheDocument()
    expect(api.signOut).toHaveBeenCalledWith('csrf')
    expect(localStorageSpy).not.toHaveBeenCalled()
  })
})

function apiWith(overrides: Partial<LarkApi>): LarkApi {
  return {
    getConnection: vi.fn().mockResolvedValue(connected),
    bootstrap: vi.fn(),
    exchange: vi.fn(),
    signOut: vi.fn(),
    ...overrides,
  }
}

function h5OutsideLark(): LarkH5Adapter {
  return {
    isAvailable: () => false,
    waitUntilAvailable: vi.fn().mockResolvedValue(false),
    requestAuthorizationCode: vi.fn(),
  }
}
