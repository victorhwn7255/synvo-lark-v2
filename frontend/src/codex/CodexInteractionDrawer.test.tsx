import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CodexInteraction } from '../api/codex'
import { CodexInteractionDrawer } from './CodexInteractionDrawer'

describe('CodexInteractionDrawer', () => {
  afterEach(cleanup)

  it('contains keyboard focus and restores it when the interaction closes', () => {
    const outside = <button type="button">Open approval</button>
    const { rerender } = render(outside)
    screen.getByRole('button', { name: 'Open approval' }).focus()

    rerender(<>{outside}<Drawer /></>)
    const dialog = screen.getByRole('dialog', { name: 'Review file change' })
    const approve = screen.getByRole('button', { name: 'Approve once' })
    const cancel = screen.getByRole('button', { name: 'Cancel task' })
    expect(approve).toHaveFocus()

    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true })
    expect(cancel).toHaveFocus()
    fireEvent.keyDown(dialog, { key: 'Tab' })
    expect(approve).toHaveFocus()

    rerender(outside)
    expect(screen.getByRole('button', { name: 'Open approval' })).toHaveFocus()
  })

  it('announces a submitted decision and disables every action', () => {
    render(<Drawer submitting />)

    expect(screen.getByRole('dialog')).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('status')).toHaveTextContent('Submitting your decision…')
    for (const button of screen.getAllByRole('button')) expect(button).toBeDisabled()
  })

  it('fails closed when a stale interaction remains visible', () => {
    render(<Drawer interaction={{ ...interaction(), expiresAt: '2020-01-01T00:00:00Z' }} />)

    expect(screen.getByText('Expired')).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('This approval request has expired')
    for (const button of screen.getAllByRole('button')) expect(button).toBeDisabled()
  })

  it('offers only a one-time approval for a bounded interaction', () => {
    render(<Drawer />)

    expect(screen.getByRole('button', { name: 'Approve once' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /session/i })).not.toBeInTheDocument()
  })
})

function Drawer({
  interaction: value = interaction(),
  submitting = false,
}: {
  interaction?: CodexInteraction
  submitting?: boolean
}) {
  return (
    <CodexInteractionDrawer
      interaction={value}
      submitting={submitting}
      error={null}
      onDecide={vi.fn().mockResolvedValue(undefined)}
    />
  )
}

function interaction(): CodexInteraction {
  return {
    interactionId: 'interaction-1',
    taskId: 'task-1',
    operationId: 'operation-1',
    workspaceId: 'pilot',
    workspaceName: 'Pilot workspace',
    kind: 'FILE_CHANGE_APPROVAL',
    category: 'file change',
    reason: 'Change one bounded workspace file.',
    permissionScope: 'once',
    availableDecisions: ['APPROVE_ONCE', 'DECLINE', 'CANCEL'],
    status: 'PENDING',
    decision: null,
    expiresAt: '2099-01-01T00:00:00Z',
    detail: {
      command: null,
      workingDirectory: null,
      affectedPaths: ['focused.test.tsx'],
      mcpServer: null,
      mcpTool: null,
      message: null,
      inputMode: null,
      elicitationUrl: null,
      fields: [],
    },
  }
}
