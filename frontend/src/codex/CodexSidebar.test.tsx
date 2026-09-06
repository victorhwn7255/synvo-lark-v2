import { createRef } from 'react'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { CodexTask } from '../api/codex'
import { CodexSidebar } from './CodexSidebar'

describe('CodexSidebar', () => {
  afterEach(cleanup)

  it('places the first workflow above Tasks without a task search control', () => {
    renderSidebar()

    const workflows = screen.getByRole('navigation', { name: 'Workflows' })
    const tasks = screen.getByRole('navigation', { name: 'Tasks' })
    expect(workflows.compareDocumentPosition(tasks) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Quotation — coming soon' })).toBeDisabled()
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
    expect(workflows).toHaveClass('codex-workflow-navigation')
    expect(tasks).toHaveClass('codex-task-navigation')
  })

  it('highlights the selected active task and exposes rename and archive actions', async () => {
    const onRenameTask = vi.fn().mockResolvedValue(undefined)
    const onArchiveTask = vi.fn().mockResolvedValue(undefined)
    renderSidebar({
      tasks: [task('task-1', 'Selected task'), task('task-2', 'Quarterly plan')],
      selectedTaskId: 'task-1',
      onRenameTask,
      onArchiveTask,
    })

    const selectedLink = screen.getByRole('button', { name: 'Selected task' })
    expect(selectedLink).toHaveAttribute('aria-current', 'page')
    expect(selectedLink.closest('.codex-task-row')).toHaveAttribute('data-active', 'true')
    expect(screen.getByRole('button', { name: 'Rename Quarterly plan' })).toHaveAttribute('title', 'Rename')
    expect(screen.getByRole('button', { name: 'Archive Quarterly plan' })).toHaveAttribute('title', 'Archive')
    expect(screen.queryByRole('button', { name: 'Delete Quarterly plan' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Rename Quarterly plan' }))
    const renameInput = screen.getByRole('textbox', { name: 'Rename Quarterly plan' })
    expect(renameInput).toHaveFocus()
    fireEvent.change(renameInput, { target: { value: 'Q3 operating plan' } })
    fireEvent.click(screen.getByRole('button', { name: 'Save rename for Quarterly plan' }))
    await waitFor(() => expect(onRenameTask).toHaveBeenCalledWith('task-2', 'Q3 operating plan'))

    fireEvent.click(screen.getByRole('button', { name: 'Archive Quarterly plan' }))
    await waitFor(() => expect(onArchiveTask).toHaveBeenCalledWith('task-2'))
  })

  it('shows only a guarded delete action for archived tasks', async () => {
    const onDeleteTask = vi.fn().mockResolvedValue(undefined)
    renderSidebar({
      archived: true,
      tasks: [{ ...task('task-3', 'Archived report'), archived: true }],
      onDeleteTask,
    })

    expect(screen.queryByRole('button', { name: 'Rename Archived report' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Archive Archived report' })).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Delete Archived report' }))

    const confirmation = screen.getByRole('alertdialog', { name: 'Delete Archived report permanently?' })
    expect(confirmation).toHaveTextContent('This cannot be undone.')
    fireEvent.click(within(confirmation).getByRole('button', { name: 'Delete' }))
    await waitFor(() => expect(onDeleteTask).toHaveBeenCalledWith('task-3'))
  })

  it('keeps row actions out of the collapsed sidebar', () => {
    renderSidebar({ collapsed: true, tasks: [task('task-1', 'Compact task')] })

    expect(screen.getByRole('button', { name: 'Compact task' })).toHaveAttribute('title', 'Compact task')
    expect(screen.queryByRole('button', { name: 'Rename Compact task' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Archive Compact task' })).not.toBeInTheDocument()
  })
})

function renderSidebar(overrides: Partial<Parameters<typeof CodexSidebar>[0]> = {}) {
  const props: Parameters<typeof CodexSidebar>[0] = {
    collapsed: false,
    settingsActive: false,
    tasks: [task('task-1', 'Selected task')],
    selectedTaskId: null,
    archived: false,
    busy: false,
    assistantReady: true,
    assistantAvailability: 'Codex is ready.',
    newTaskRef: createRef<HTMLButtonElement>(),
    onToggle: vi.fn(),
    onNewTask: vi.fn(),
    onOpenTask: vi.fn(),
    onRenameTask: vi.fn().mockResolvedValue(undefined),
    onArchiveTask: vi.fn().mockResolvedValue(undefined),
    onDeleteTask: vi.fn().mockResolvedValue(undefined),
    onArchivedChange: vi.fn(),
    onOpenSettings: vi.fn(),
    ...overrides,
  }
  return render(<CodexSidebar {...props} />)
}

function task(taskId: string, title: string): CodexTask {
  return {
    taskId,
    conversationId: `conversation-${taskId}`,
    title,
    workspaceId: 'sales',
    workspaceName: 'Sales',
    mode: 'WORKSPACE_WRITE',
    pinned: false,
    archived: false,
    createdAt: '2026-08-23T00:00:00Z',
    updatedAt: '2026-08-23T00:00:00Z',
  }
}
