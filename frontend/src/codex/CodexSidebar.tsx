import { useState, type FormEvent, type RefObject } from 'react'
import type { CodexTask } from '../api/codex'
import {
  ArchiveIcon,
  ArtifactIcon,
  CheckIcon,
  CloseIcon,
  ConversationIcon,
  PanelLeftIcon,
  PencilIcon,
  PinIcon,
  PlusIcon,
  SettingsIcon,
  SynvoLogo,
  TrashIcon,
} from '../workspace/visuals'

export function CodexSidebar({
  collapsed,
  settingsActive,
  tasks,
  selectedTaskId,
  archived,
  busy,
  assistantReady,
  assistantAvailability,
  newTaskRef,
  onToggle,
  onNewTask,
  onOpenTask,
  onRenameTask,
  onArchiveTask,
  onDeleteTask,
  onArchivedChange,
  onOpenSettings,
}: {
  collapsed: boolean
  settingsActive: boolean
  tasks: CodexTask[]
  selectedTaskId: string | null
  archived: boolean
  busy: boolean
  assistantReady: boolean
  assistantAvailability: string
  newTaskRef: RefObject<HTMLButtonElement | null>
  onToggle: () => void
  onNewTask: () => void
  onOpenTask: (taskId: string) => void
  onRenameTask: (taskId: string, title: string) => Promise<void>
  onArchiveTask: (taskId: string) => Promise<void>
  onDeleteTask: (taskId: string) => Promise<void>
  onArchivedChange: (archived: boolean) => void
  onOpenSettings: () => void
}) {
  const [renamingTaskId, setRenamingTaskId] = useState<string | null>(null)
  const [renameValue, setRenameValue] = useState('')
  const [deletingTaskId, setDeletingTaskId] = useState<string | null>(null)

  const beginRename = (task: CodexTask) => {
    setDeletingTaskId(null)
    setRenamingTaskId(task.taskId)
    setRenameValue(task.title)
  }

  const cancelRename = () => {
    setRenamingTaskId(null)
    setRenameValue('')
  }

  const submitRename = async (event: FormEvent, task: CodexTask) => {
    event.preventDefault()
    const title = renameValue.trim()
    if (!title || title === task.title) {
      cancelRename()
      return
    }
    try {
      await onRenameTask(task.taskId, title)
      cancelRename()
    } catch {
      // The owning workspace presents the normalized mutation error.
    }
  }

  return (
    <aside className="workspace-sidebar" aria-label="Synvo AI Assistant task navigation">
      <div className="workspace-sidebar__brand">
        <SynvoLogo />
        <strong className="workspace-sidebar__label">Synvo AI Assistant</strong>
        <button
          className="workspace-icon-button workspace-sidebar__collapse"
          type="button"
          aria-label={collapsed ? 'Expand sidebar' : 'Hide sidebar'}
          onClick={onToggle}
        >
          <PanelLeftIcon />
        </button>
      </div>

      <button
        ref={newTaskRef}
        className="workspace-new-button"
        type="button"
        aria-label="New Codex task"
        disabled={busy}
        onClick={onNewTask}
      >
        <PlusIcon />
        <span className="workspace-sidebar__label">New Codex task</span>
      </button>

      <div className="workspace-sidebar__scroll">
        <nav
          className="workspace-sidebar__section codex-workflow-navigation"
          aria-label={collapsed ? 'Workflows' : undefined}
          aria-labelledby={collapsed ? undefined : 'codex-workflow-navigation-title'}
        >
          {!collapsed && (
            <h2 id="codex-workflow-navigation-title" className="workspace-sidebar__section-title">Workflows</h2>
          )}
          <button
            className="workspace-nav-item codex-workflow-link"
            type="button"
            aria-label="Quotation — coming soon"
            title={collapsed ? 'Quotation — coming soon' : undefined}
            disabled
          >
            <ArtifactIcon />
            <span className="workspace-sidebar__label">Quotation</span>
            <small className="workspace-sidebar__label">Coming soon</small>
          </button>
        </nav>

        <nav
          className="workspace-sidebar__section codex-task-navigation"
          aria-label={collapsed ? 'Codex tasks' : undefined}
          aria-labelledby={collapsed ? undefined : 'codex-task-navigation-title'}
        >
          {!collapsed && (
            <>
              <h2 id="codex-task-navigation-title" className="workspace-sidebar__section-title">Tasks</h2>
              <div className="codex-task-filters" aria-label="Task status">
                <button type="button" aria-pressed={!archived} onClick={() => onArchivedChange(false)}>Active</button>
                <button type="button" aria-pressed={archived} onClick={() => onArchivedChange(true)}>Archived</button>
              </div>
            </>
          )}
          {tasks.map((task) => {
            const active = selectedTaskId === task.taskId
            const renaming = renamingTaskId === task.taskId
            const confirmingDelete = deletingTaskId === task.taskId
            return (
              <div className="codex-task-entry" key={task.taskId}>
                <div className="codex-task-row" data-active={active} data-editing={renaming}>
                  {renaming ? (
                    <form className="codex-task-rename" onSubmit={(event) => void submitRename(event, task)}>
                      <ConversationIcon />
                      <label>
                        <span className="sr-only">Rename {task.title}</span>
                        <input
                          autoFocus
                          maxLength={160}
                          value={renameValue}
                          disabled={busy}
                          onChange={(event) => setRenameValue(event.target.value)}
                          onKeyDown={(event) => {
                            if (event.key === 'Escape') cancelRename()
                          }}
                        />
                      </label>
                      <button className="codex-task-row__action" type="submit" aria-label={`Save rename for ${task.title}`} title="Save" disabled={busy || !renameValue.trim()}><CheckIcon /></button>
                      <button className="codex-task-row__action" type="button" aria-label={`Cancel rename for ${task.title}`} title="Cancel" disabled={busy} onClick={cancelRename}><CloseIcon /></button>
                    </form>
                  ) : (
                    <>
                      <button
                        className="workspace-nav-item codex-task-link codex-task-row__open"
                        type="button"
                        aria-label={task.title}
                        aria-current={active ? 'page' : undefined}
                        disabled={busy && !active}
                        title={collapsed ? task.title : undefined}
                        onClick={() => onOpenTask(task.taskId)}
                      >
                        <ConversationIcon />
                        <span className="workspace-sidebar__label">{task.title}</span>
                        {task.pinned && <span className="codex-task-row__pin" role="img" aria-label="Pinned" title="Pinned"><PinIcon /></span>}
                      </button>
                      {!collapsed && (
                        <span className="codex-task-row__actions">
                          {!archived ? (
                            <>
                              <button className="codex-task-row__action" type="button" aria-label={`Rename ${task.title}`} title="Rename" disabled={busy} onClick={() => beginRename(task)}><PencilIcon /></button>
                              <button className="codex-task-row__action" type="button" aria-label={`Archive ${task.title}`} title="Archive" disabled={busy} onClick={() => void onArchiveTask(task.taskId).catch(() => undefined)}><ArchiveIcon /></button>
                            </>
                          ) : (
                            <button className="codex-task-row__action codex-task-row__action--delete" type="button" aria-label={`Delete ${task.title}`} title="Delete" disabled={busy} onClick={() => setDeletingTaskId(task.taskId)}><TrashIcon /></button>
                          )}
                        </span>
                      )}
                    </>
                  )}
                </div>
                {confirmingDelete && (
                  <div className="codex-task-delete-confirm" role="alertdialog" aria-label={`Delete ${task.title} permanently?`}>
                    <p><strong>Delete permanently?</strong><span>This cannot be undone.</span></p>
                    <button type="button" disabled={busy} onClick={() => void onDeleteTask(task.taskId).then(() => setDeletingTaskId(null)).catch(() => undefined)}>Delete</button>
                    <button type="button" disabled={busy} onClick={() => setDeletingTaskId(null)}>Cancel</button>
                  </div>
                )}
              </div>
            )
          })}
          {!collapsed && tasks.length === 0 && (
            <p className="workspace-sidebar__empty">{archived ? 'No archived tasks.' : 'No Codex tasks yet.'}</p>
          )}
        </nav>
      </div>

      <div className="workspace-sidebar__footer">
        <button
          className="workspace-nav-item workspace-sidebar__settings"
          type="button"
          data-active={settingsActive}
          aria-label="Settings"
          onClick={onOpenSettings}
        >
          <SettingsIcon />
          <span className="workspace-sidebar__label">Settings</span>
          <span
            className="workspace-settings-availability"
            data-state={assistantReady ? 'connected' : 'disconnected'}
            role="img"
            aria-label={assistantAvailability}
            title={assistantAvailability}
          />
        </button>
      </div>
    </aside>
  )
}
