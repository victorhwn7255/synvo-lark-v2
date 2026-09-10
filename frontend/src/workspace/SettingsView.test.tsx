import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CodexStatus } from '../api/codex'
import { SettingsView } from './SettingsView'

const status: CodexStatus = { state: 'READY', model: 'test-model', runtimeVersion: 'test-version', reasoningEfforts: [], account: { authentication: 'subscription', authenticationRequired: false, plan: 'Test plan', usedPercent: 48, resetsAt: '2026-09-15T03:32:38Z' } }
describe('SettingsView', () => {
  afterEach(cleanup)
  it('displays prolite as Pro without changing account data or authentication precedence', () => {
    const account = { ...status.account!, plan: 'prolite' }
    const view = render(<SettingsView botConnection="connected" busy={false} onSignOut={vi.fn()} status={{ ...status, account }} />)
    expect(screen.getByText('Pro')).toBeInTheDocument()
    expect(screen.queryByText('prolite')).not.toBeInTheDocument()
    expect(account.plan).toBe('prolite')
    view.rerender(<SettingsView botConnection="connected" busy={false} onSignOut={vi.fn()} status={{ ...status, account: { ...account, authenticationRequired: true } }} />)
    expect(screen.getByText('Authentication required')).toBeInTheDocument()
    expect(screen.queryByText('Pro')).not.toBeInTheDocument()
  })
  it('separates the keyboard-scrollable panel from its reading column and displays honest account data', () => {
    render(<SettingsView botConnection="failed" busy={false} onSignOut={vi.fn()} status={status} />)
    const panel = screen.getByRole('region', { name: 'Settings' })
    expect(panel).toHaveClass('workspace-themed-scrollbar')
    expect(panel).toHaveAttribute('tabindex', '0')
    expect(panel.firstElementChild).toHaveClass('workspace-settings__content')
    expect(screen.getByRole('meter', { name: 'Account usage' })).toHaveAttribute('aria-valuenow', '48')
    expect(screen.getByText('48% used')).toBeInTheDocument()
    expect(screen.getByText('Connected · Lark identity authorized')).toBeInTheDocument()
    expect(screen.getByText('Needs attention')).toBeInTheDocument()
    expect(panel.querySelector('time')).toHaveAttribute('datetime', status.account!.resetsAt!)
    expect(panel.querySelector('time')?.textContent).not.toContain(':38')
    expect(panel.querySelector('details')).not.toHaveAttribute('open')
  })
  it.each([null, NaN, -1, 101])('does not invent or draw unavailable/invalid usage (%s)', (usedPercent) => {
    render(<SettingsView botConnection="connected" busy={false} onSignOut={vi.fn()} status={{ ...status, account: { ...status.account!, usedPercent, resetsAt: 'invalid' } }} />)
    expect(screen.getByText('Usage information unavailable')).toBeInTheDocument()
    expect(screen.queryByRole('meter')).not.toBeInTheDocument()
    expect(screen.queryByText('Resets')).not.toBeInTheDocument()
  })
  it.each([0, 100])('keeps valid usage endpoints (%s)', (usedPercent) => {
    render(<SettingsView botConnection="connected" busy={false} onSignOut={vi.fn()} status={{ ...status, account: { ...status.account!, usedPercent } }} />)
    expect(screen.getByRole('meter')).toHaveAttribute('aria-valuenow', String(usedPercent))
  })
  it('preserves workspace ceilings and disables duplicate disconnection while busy', () => {
    const onSignOut = vi.fn()
    const props = { botConnection: 'connected' as const, onSignOut, workspaces: [{ id: 'a', displayName: 'Finance', writeEnabled: true, repositoryLabel: null, nativeChatDefault: false }, { id: 'b', displayName: 'Archive', writeEnabled: false, repositoryLabel: null, nativeChatDefault: false }] }
    const view = render(<SettingsView {...props} busy={false} />)
    const workspaces = screen.getByRole('article', { name: 'Configured workspaces' })
    expect(within(workspaces).getByText('Read Only or Edit workspace files')).toBeInTheDocument()
    expect(within(workspaces).getByText('Read Only')).toBeInTheDocument()
    expect(workspaces).toHaveTextContent('One-time decisions apply only to the displayed action.')
    fireEvent.click(screen.getByRole('button', { name: 'Disconnect Synvo' }))
    expect(onSignOut).toHaveBeenCalledTimes(1)
    view.rerender(<SettingsView {...props} busy />)
    expect(screen.getByRole('button', { name: 'Disconnecting…' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: 'Disconnecting…' }))
    expect(onSignOut).toHaveBeenCalledTimes(1)
  })
})
