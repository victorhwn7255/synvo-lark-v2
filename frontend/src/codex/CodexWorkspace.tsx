import { useEffect, useRef, useState } from 'react'
import type { BotConnection } from '../api/lark'
import type { ConversationApi } from '../api/conversations'
import type { CodexApi } from '../api/codex'
import { ConversationView } from '../conversation/ConversationView'
import { useConversation } from '../conversation/useConversation'
import { SettingsView } from '../workspace/SettingsView'
import { ArrowLeftIcon, ArtifactIcon, FolderIcon } from '../workspace/visuals'
import { CodexComposerControls } from './CodexComposerControls'
import { CodexActivityTimeline } from './CodexActivityTimeline'
import { CodexInteractionDrawer } from './CodexInteractionDrawer'
import { CodexSidebar } from './CodexSidebar'
import { CodexTaskPanel } from './CodexTaskPanel'
import { CodexTaskSetup } from './CodexTaskSetup'
import { useCodexWorkspace } from './useCodexWorkspace'

type WorkspaceView = 'conversation' | 'settings'

const connectionNotices: Partial<Record<BotConnection, string>> = {
  connecting: 'The native Lark assistant channel is connecting.',
  reconnecting: 'The native Lark assistant channel is reconnecting automatically.',
  failed: 'The native Lark assistant channel needs attention. H5 task access remains protected.',
  disabled: 'The native Lark assistant channel is disabled in this environment.',
}

export function CodexWorkspace({
  botConnection,
  busy,
  userAvatarUrl,
  onSignOut,
  conversationApi,
  codexApi,
}: {
  botConnection: BotConnection
  busy: boolean
  userAvatarUrl: string | null
  onSignOut: () => void
  conversationApi?: ConversationApi
  codexApi: CodexApi
}) {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(initialSidebarCollapsed)
  const [view, setView] = useState<WorkspaceView>('conversation')
  const [taskPanelOpen, setTaskPanelOpen] = useState(false)
  const [reasoningEffort, setReasoningEffort] = useState('')
  const [skillName, setSkillName] = useState('')
  const newTaskRef = useRef<HTMLButtonElement>(null)
  const refreshedTerminalRef = useRef<string | null>(null)
  const taskState = useCodexWorkspace({ api: codexApi })
  const conversation = useConversation({ api: conversationApi })
  const openConversation = conversation.openConversation
  const openCodexTask = taskState.openTask
  const refreshSelectedTask = taskState.refreshSelectedTask
  const synchronizeSelectedTask = taskState.synchronizeSelectedTask
  const task = taskState.taskDetail?.task ?? null
  const activeOperation = taskState.taskDetail?.activeOperation ?? null
  const latestOperation = taskState.taskDetail?.latestOperation ?? null
  const activeRun = conversation.activeRun

  useEffect(() => {
    const efforts = taskState.status?.reasoningEfforts ?? []
    if (!reasoningEffort || !efforts.includes(reasoningEffort)) {
      setReasoningEffort(efforts.includes('medium') ? 'medium' : efforts[0] ?? '')
    }
  }, [reasoningEffort, taskState.status?.reasoningEfforts])

  useEffect(() => {
    const conversationId = task?.conversationId
    if (!conversationId || taskState.loadingTask || activeRun) return
    if (conversation.selectedConversation !== conversationId) {
      void openConversation(conversationId)
    }
  }, [activeRun, conversation.selectedConversation, openConversation, task?.conversationId, taskState.loadingTask])

  useEffect(() => {
    if (!activeRun?.runId || activeOperation) return
    let cancelled = false
    let timeout: number | null = null
    let delay = 40
    const synchronize = async () => {
      const detail = await synchronizeSelectedTask()
      if (cancelled || detail?.activeOperation) return
      delay = Math.min(delay * 2, 500)
      timeout = window.setTimeout(() => void synchronize(), delay)
    }
    timeout = window.setTimeout(() => void synchronize(), delay)
    return () => {
      cancelled = true
      if (timeout !== null) window.clearTimeout(timeout)
    }
  }, [activeOperation, activeRun?.runId, synchronizeSelectedTask])

  const terminalActivity = taskState.activity.findLast(({ terminalStatus }) => terminalStatus !== null)
  useEffect(() => {
    if (!terminalActivity || refreshedTerminalRef.current === `${task?.taskId}:${terminalActivity.sequence}`) return
    refreshedTerminalRef.current = `${task?.taskId}:${terminalActivity.sequence}`
    void refreshSelectedTask()
  }, [refreshSelectedTask, task?.taskId, terminalActivity])

  useEffect(() => {
    const handoff = conversation.interactionHandoff
    if (!handoff) return
    void openCodexTask(handoff.taskId, handoff.interactionId)
    setTaskPanelOpen(true)
  }, [conversation.interactionHandoff, openCodexTask])

  useEffect(() => {
    if (skillName && !taskState.inventory.skills.some(({ name }) => name === skillName)) {
      setSkillName('')
    }
  }, [skillName, taskState.inventory.skills])

  const openTask = async (taskId: string) => {
    if (activeRun) return
    setView('conversation')
    const detail = await openCodexTask(taskId)
    if (detail) await openConversation(detail.task.conversationId)
    collapseSidebarForNarrowViewport(setSidebarCollapsed)
  }

  const newTask = async () => {
    if (activeRun || activeOperation) return
    setView('conversation')
    setTaskPanelOpen(false)
    setSkillName('')
    taskState.clearSelection()
    await openConversation(null)
    collapseSidebarForNarrowViewport(setSidebarCollapsed)
  }

  const createTask = async (workspaceId: string, mode: 'READ_ONLY' | 'WORKSPACE_WRITE', title?: string) => {
    const created = await taskState.createTask(workspaceId, mode, title)
    if (created) await openConversation(created.conversationId)
  }

  const archiveTask = async (enabled: boolean) => {
    await taskState.archiveTask(enabled)
    setTaskPanelOpen(false)
    await openConversation(null)
  }

  const deleteTask = async () => {
    await taskState.deleteTask()
    setTaskPanelOpen(false)
    await openConversation(null)
    queueMicrotask(() => newTaskRef.current?.focus())
  }

  const archiveSidebarTask = async (taskId: string) => {
    const selected = taskState.selectedTaskId === taskId
    await taskState.archiveTaskById(taskId, true)
    if (selected) {
      setTaskPanelOpen(false)
      await openConversation(null)
    }
  }

  const deleteSidebarTask = async (taskId: string) => {
    const selected = taskState.selectedTaskId === taskId
    await taskState.deleteTaskById(taskId)
    if (selected) {
      setTaskPanelOpen(false)
      await openConversation(null)
      queueMicrotask(() => newTaskRef.current?.focus())
    }
  }

  const assistantReady = botConnection === 'connected' && taskState.status?.state === 'READY'
  const assistantAvailability = assistantReady
    ? 'Codex and the native Lark assistant are ready.'
    : 'One or more Codex in Lark services need attention.'
  const connectionNotice = connectionNotices[botConnection]
  const title = view === 'settings' ? 'Settings' : task?.title ?? 'New Codex task'
  const taskBusy = activeRun !== null || activeOperation !== null || taskState.submitting !== null
  const composerDisabled = !task || taskState.status?.state !== 'READY'
  const timelineOperation = activeOperation ?? (activeRun ? null : latestOperation)
  const timelineActivity = activeRun && !activeOperation ? [] : taskState.activity
  const activityPresentation = activeRun || timelineOperation ? (
    <CodexActivityTimeline
      active={activeRun !== null || activeOperation !== null}
      operationStatus={timelineOperation?.status ?? null}
      reconnecting={taskState.reconnecting}
      interaction={taskState.interaction}
      activity={timelineActivity}
    />
  ) : null

  return (
    <main className="workspace-shell" data-sidebar-collapsed={sidebarCollapsed} aria-label="Codex in Lark workspace">
      <CodexSidebar
        collapsed={sidebarCollapsed}
        settingsActive={view === 'settings'}
        tasks={taskState.tasks}
        selectedTaskId={taskState.selectedTaskId}
        archived={taskState.archived}
        search={taskState.search}
        busy={taskBusy}
        assistantReady={assistantReady}
        assistantAvailability={assistantAvailability}
        newTaskRef={newTaskRef}
        onToggle={() => setSidebarCollapsed((collapsed) => !collapsed)}
        onNewTask={() => void newTask()}
        onOpenTask={(taskId) => void openTask(taskId)}
        onRenameTask={async (taskId, nextTitle) => { await taskState.renameTaskById(taskId, nextTitle) }}
        onArchiveTask={archiveSidebarTask}
        onDeleteTask={deleteSidebarTask}
        onArchivedChange={taskState.setArchived}
        onSearchChange={taskState.setSearch}
        onOpenSettings={() => {
          setView('settings')
          setTaskPanelOpen(false)
          collapseSidebarForNarrowViewport(setSidebarCollapsed)
        }}
      />

      <section className="workspace-main">
        <header className="workspace-topbar">
          {view === 'settings' ? (
            <button className="workspace-icon-button workspace-topbar__back" type="button" aria-label="Back to Codex task" onClick={() => setView('conversation')}>
              <ArrowLeftIcon />
            </button>
          ) : <span className="workspace-topbar__folder" aria-hidden="true"><FolderIcon /></span>}
          <div className="workspace-topbar__title">
            <h1>{title}</h1>
            {task && <p className="codex-topbar-meta">{task.workspaceName} · {task.mode === 'READ_ONLY' ? 'Read Only' : 'Full Edit'}</p>}
          </div>
          {view === 'conversation' && task && (
            <button
              className="workspace-secondary-button"
              type="button"
              aria-label="Task details"
              aria-expanded={taskPanelOpen}
              aria-controls="codex-task-panel"
              onClick={() => setTaskPanelOpen((open) => !open)}
            >
              <ArtifactIcon /><span>Task details</span>
            </button>
          )}
        </header>

        {connectionNotice && <div className="workspace-connection-notice" role="status"><span aria-hidden="true" />{connectionNotice}</div>}
        {taskState.reconnecting && <div className="codex-reconnect-notice" role="status">Reconnecting to Codex activity…</div>}
        <div
          className="workspace-content"
          data-artifact-open={taskPanelOpen && view === 'conversation'}
          data-codex-panel-open={taskPanelOpen && view === 'conversation'}
        >
          {view === 'settings' ? (
            <SettingsView botConnection={botConnection} busy={busy} onSignOut={onSignOut} />
          ) : taskState.loading ? (
            <div className="workspace-history-state" role="status">Preparing Codex in Lark…</div>
          ) : !task ? (
            <CodexTaskSetup
              status={taskState.status}
              workspaces={taskState.workspaces}
              submitting={taskState.submitting === 'create-task'}
              error={taskState.error}
              onCreate={createTask}
            />
          ) : (
            <ConversationView
              turns={conversation.turns}
              userAvatarUrl={userAvatarUrl}
              composerValue={conversation.composerValue}
              loading={conversation.loadingConversation || taskState.loadingTask}
              error={conversation.conversationError ?? taskState.error}
              activeRun={activeRun}
              composerDisabled={composerDisabled}
              composerPlaceholder={composerDisabled ? 'Select a ready Codex task to continue.' : 'Ask Codex to work in this workspace…'}
              composerControls={(
                <CodexComposerControls
                  reasoningEfforts={taskState.status?.reasoningEfforts ?? []}
                  reasoningEffort={reasoningEffort}
                  skills={taskState.inventory.skills}
                  skillName={skillName}
                  disabled={activeRun !== null}
                  onReasoningEffortChange={setReasoningEffort}
                  onSkillNameChange={setSkillName}
                />
              )}
              activityPresentation={activityPresentation}
              onComposerChange={conversation.setComposerValue}
              onSubmit={(content) => void conversation.submitMessage(content, undefined, {
                ...(reasoningEffort ? { reasoningEffort } : {}),
                ...(skillName ? { skillName } : {}),
              })}
              onStop={() => void conversation.stopRun()}
              onRetry={conversation.retryTurn}
              onBranch={() => void taskState.forkTask(`Fork of ${task.title}`)}
            />
          )}

          {view === 'conversation' && taskPanelOpen && taskState.taskDetail && (
            <CodexTaskPanel
              status={taskState.status}
              taskDetail={taskState.taskDetail}
              activity={taskState.activity}
              inventory={taskState.inventory}
              goal={taskState.goal}
              reconnecting={taskState.reconnecting}
              submitting={taskState.submitting}
              error={taskState.error}
              onClose={() => setTaskPanelOpen(false)}
              onRename={async (nextTitle) => { await taskState.renameTask(nextTitle) }}
              onPin={async (enabled) => { await taskState.pinTask(enabled) }}
              onArchive={archiveTask}
              onModeChange={async (mode) => { await taskState.changeMode(mode) }}
              onFork={async (forkTitle) => { await taskState.forkTask(forkTitle) }}
              onDelete={deleteTask}
              onSteer={taskState.steer}
              onStopOperation={activeRun ? conversation.stopRun : taskState.stopOperation}
              onUpdateGoal={taskState.updateGoal}
              onClearGoal={taskState.clearGoal}
              onStartReview={taskState.startReview}
            />
          )}
        </div>
      </section>

      {taskState.interaction && (
        <CodexInteractionDrawer
          interaction={taskState.interaction}
          submitting={taskState.submitting === 'interaction-decision'}
          error={taskState.error}
          onDecide={taskState.decideInteraction}
        />
      )}
    </main>
  )
}

function initialSidebarCollapsed() {
  return typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 760px)').matches
}

function collapseSidebarForNarrowViewport(setCollapsed: (collapsed: boolean) => void) {
  if (typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 760px)').matches) setCollapsed(true)
}
