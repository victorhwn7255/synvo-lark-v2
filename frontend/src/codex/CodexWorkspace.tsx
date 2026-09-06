import { useEffect, useRef, useState } from 'react'
import type { BotConnection } from '../api/lark'
import type { ConversationApi } from '../api/conversations'
import type { CodexApi, CodexOperationStatus, CodexTerminalStatus } from '../api/codex'
import { ConversationView } from '../conversation/ConversationView'
import { useConversation } from '../conversation/useConversation'
import { SettingsView } from '../workspace/SettingsView'
import { useMediaQuery } from '../workspace/useMediaQuery'
import { ArrowLeftIcon, ArtifactIcon, FolderIcon, PanelLeftIcon } from '../workspace/visuals'
import { CodexComposerControls } from './CodexComposerControls'
import { projectAgentActivity } from './activityPresentation'
import { CodexActivityTimeline, type CodexSteeringMilestoneStatus } from './CodexActivityTimeline'
import { CodexInteractionDrawer } from './CodexInteractionDrawer'
import { CodexSidebar } from './CodexSidebar'
import { CodexTaskPanel, type CodexSteeringUpdate } from './CodexTaskPanel'
import { CodexTaskSetup } from './CodexTaskSetup'
import { useCodexWorkspace } from './useCodexWorkspace'

type WorkspaceView = 'conversation' | 'settings'

type SteeringDraft = {
  content: string
  operationId: string
  feedback: 'sending' | 'sent' | 'failed' | null
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
  const phone = useMediaQuery('(max-width: 760px)')
  const panelOverlay = useMediaQuery('(max-width: 980px)')
  const [sidebarCollapsed, setSidebarCollapsed] = useState(initialSidebarCollapsed)
  useEffect(() => { if (phone) setSidebarCollapsed(true) }, [phone])
  const [view, setView] = useState<WorkspaceView>('conversation')
  const [taskPanelOpen, setTaskPanelOpen] = useState(false)
  const [reasoningEffortsByTask, setReasoningEffortsByTask] = useState<Record<string, string>>({})
  const [skillName, setSkillName] = useState('')
  const [startingTask, setStartingTask] = useState(false)
  const startInFlightRef = useRef(false)
  const [pendingStart, setPendingStart] = useState<{ taskId: string; conversationId: string; content: string; reasoningEffort: string } | null>(null)
  const pendingStartRef = useRef<string | null>(null)
  const [unconfirmedStart, setUnconfirmedStart] = useState<{ content: string; workspaceId: string } | null>(null)
  const [messageDrafts, setMessageDrafts] = useState<Record<string, string>>({})
  const [steeringDrafts, setSteeringDrafts] = useState<Record<string, SteeringDraft>>({})
  const pendingSteeringRef = useRef(new Set<string>())
  const operationOutcomesRef = useRef(new Map<string, CodexSteeringUpdate['status']>())
  const [steeringUpdatesByTask, setSteeringUpdatesByTask] = useState<Record<string, CodexSteeringUpdate[]>>({})
  const newTaskRef = useRef<HTMLButtonElement>(null)
  const steeringUpdateSequenceRef = useRef(0)
  const refreshedTerminalRef = useRef<string | null>(null)
  const taskState = useCodexWorkspace({ api: codexApi })
  const conversation = useConversation({ api: conversationApi })
  const submitFirstMessage = conversation.submitMessage
  const openConversation = conversation.openConversation
  const openCodexTask = taskState.openTask
  const refreshSelectedTask = taskState.refreshSelectedTask
  const synchronizeSelectedTask = taskState.synchronizeSelectedTask
  const task = taskState.taskDetail?.task ?? null
  const availableEfforts = taskState.status?.reasoningEfforts ?? []
  const defaultReasoningEffort = availableEfforts.includes('high') ? 'high' : availableEfforts[0] ?? ''
  const selectedEffort = task ? reasoningEffortsByTask[task.taskId] : undefined
  const reasoningEffort = selectedEffort && availableEfforts.includes(selectedEffort) ? selectedEffort : defaultReasoningEffort
  const activeOperation = taskState.taskDetail?.activeOperation ?? null
  const latestOperation = taskState.taskDetail?.latestOperation ?? null
  const activeRun = conversation.activeRun
  const steeringUpdates = task ? steeringUpdatesByTask[task.taskId] ?? [] : []

  useEffect(() => {
    const viewport = window.visualViewport
    if (!viewport) return
    const update = () => {
      if (viewport.scale === 1) {
        document.documentElement.style.setProperty('--workspace-height', `${viewport.height}px`)
        document.documentElement.style.setProperty('--workspace-offset', `${viewport.offsetTop}px`)
      } else {
        document.documentElement.style.removeProperty('--workspace-height')
        document.documentElement.style.removeProperty('--workspace-offset')
      }
    }
    update()
    viewport.addEventListener('resize', update)
    viewport.addEventListener('scroll', update)
    return () => {
      viewport.removeEventListener('resize', update)
      viewport.removeEventListener('scroll', update)
      document.documentElement.style.removeProperty('--workspace-height')
      document.documentElement.style.removeProperty('--workspace-offset')
    }
  }, [])

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

  const terminalSteeringStatus = timelineSteeringStatus(
    activeOperation ?? latestOperation,
    terminalActivity?.terminalStatus ?? null,
  )
  useEffect(() => {
    const operation = activeOperation ?? latestOperation
    if (!task || !operation || terminalSteeringStatus === 'delivered') return
    operationOutcomesRef.current.set(operation.operationId, terminalSteeringStatus)
    setSteeringUpdatesByTask((current) => updateSteeringStatuses(
      current,
      task.taskId,
      operation.operationId,
      terminalSteeringStatus,
    ))
  }, [activeOperation, latestOperation, task, terminalSteeringStatus])

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
    if (activeRun || startingTask || pendingStart) return
    setView('conversation')
    const detail = await openCodexTask(taskId)
    if (detail) await openConversation(detail.task.conversationId)
    collapseSidebarForNarrowViewport(setSidebarCollapsed)
  }

  const newTask = async () => {
    if (activeRun || activeOperation || startingTask || pendingStart) return
    setView('conversation')
    setTaskPanelOpen(false)
    setSkillName('')
    taskState.clearSelection()
    await openConversation(null)
    collapseSidebarForNarrowViewport(setSidebarCollapsed)
  }

  const createTask = async (workspaceId: string, mode: 'READ_ONLY' | 'WORKSPACE_WRITE', content: string, title?: string) => {
    if (startInFlightRef.current || pendingStartRef.current || unconfirmedStart || !content.trim()
      || !taskState.status?.model || !defaultReasoningEffort) return
    startInFlightRef.current = true
    setStartingTask(true)
    try {
      const created = await taskState.createTask(workspaceId, mode, title)
      pendingStartRef.current = created.taskId
      setReasoningEffortsByTask((current) => ({ ...current, [created.taskId]: defaultReasoningEffort }))
      setPendingStart({ taskId: created.taskId, conversationId: created.conversationId, content, reasoningEffort: defaultReasoningEffort })
    } catch {
      setUnconfirmedStart({ content, workspaceId })
      await taskState.refreshTasks()
    } finally {
      startInFlightRef.current = false
      setStartingTask(false)
    }
  }

  // Consume the handoff once, only when the owning conversation has loaded.
  // The message hook keeps its existing retry and exactly-one-run ownership.
  useEffect(() => {
    if (!pendingStart || pendingStartRef.current !== pendingStart.taskId
      || task?.taskId !== pendingStart.taskId || taskState.loadingTask
      || conversation.loadingConversation || conversation.conversationError
      || conversation.selectedConversation !== pendingStart.conversationId) return
    if (activeRun || activeOperation || taskState.interaction) {
      setMessageDrafts((current) => ({ ...current, [pendingStart.taskId]: pendingStart.content }))
    } else {
      void submitFirstMessage(pendingStart.content, undefined, {
        reasoningEffort: pendingStart.reasoningEffort,
      })
    }
    pendingStartRef.current = null
    setPendingStart(null)
  }, [pendingStart, task?.taskId, taskState.loadingTask, taskState.interaction,
    conversation.loadingConversation, conversation.conversationError, conversation.selectedConversation,
    submitFirstMessage, activeRun, activeOperation])

  const retryStartLoading = async () => {
    if (!pendingStart || taskState.loadingTask || conversation.loadingConversation) return
    const detail = await openCodexTask(pendingStart.taskId)
    if (detail) await openConversation(pendingStart.conversationId)
  }

  const archiveTask = async (enabled: boolean) => {
    await taskState.archiveTask(enabled)
    setTaskPanelOpen(false)
    await openConversation(null)
  }

  const deleteTask = async () => {
    const taskId = task?.taskId ?? null
    await taskState.deleteTask()
    if (taskId) setSteeringUpdatesByTask((current) => withoutSteeringHistory(current, taskId))
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
    setSteeringUpdatesByTask((current) => withoutSteeringHistory(current, taskId))
    if (selected) {
      setTaskPanelOpen(false)
      await openConversation(null)
      queueMicrotask(() => newTaskRef.current?.focus())
    }
  }

  const assistantReady = taskState.status?.state === 'READY'
  const assistantAvailability = assistantReady
    ? 'Synvo H5 and Codex are ready.'
    : 'Codex needs attention. Open Settings for connection details.'
  const title = view === 'settings' ? 'Settings' : task?.title ?? 'New Codex task'
  const taskBusy = activeRun !== null || activeOperation !== null || taskState.submitting !== null || startingTask || pendingStart !== null
  const pendingDecision = taskState.interaction !== null || activeOperation?.status === 'WAITING_FOR_INTERACTION'
  const draft = task ? steeringDrafts[task.taskId] : undefined
  const hasSteeringDraft = Boolean(draft?.content)
  const staleSteering = hasSteeringDraft && draft?.operationId !== activeOperation?.operationId
  const isSteering = Boolean(activeOperation) || hasSteeringDraft
  const composerDisabled = !task || taskState.status?.state !== 'READY' || pendingDecision
    || Boolean(staleSteering) || (Boolean(activeRun) && !activeOperation)
    || taskState.loadingTask || conversation.loadingConversation
    || conversation.selectedConversation !== task?.conversationId || pendingStart !== null
  const composerValue = task ? isSteering ? draft?.content ?? '' : messageDrafts[task.taskId] ?? '' : ''

  const changeComposer = (content: string) => {
    if (!task) return
    if (isSteering && (draft || activeOperation)) {
      setSteeringDrafts((current) => ({ ...current, [task.taskId]: {
        content, operationId: draft?.content ? draft.operationId : activeOperation!.operationId,
        feedback: draft?.feedback === 'sending' ? 'sending' : null,
      } }))
    } else setMessageDrafts((current) => ({ ...current, [task.taskId]: content }))
  }

  const submitComposer = async (content: string) => {
    if (!task || composerDisabled || !content.trim()) return
    const taskId = task.taskId
    if (!isSteering) {
      setMessageDrafts((current) => ({ ...current, [taskId]: '' }))
      setSteeringDrafts((current) => current[taskId]
        ? { ...current, [taskId]: { ...current[taskId], feedback: null } }
        : current)
      await conversation.submitMessage(content, undefined, {
        ...(reasoningEffort ? { reasoningEffort } : {}),
        ...(skillName ? { skillName } : {}),
      })
      return
    }
    const operationId = activeOperation?.operationId
    if (!operationId || pendingSteeringRef.current.has(taskId)) return
    pendingSteeringRef.current.add(taskId)
    const updateId = `${operationId}:${++steeringUpdateSequenceRef.current}`
    setSteeringDrafts((current) => ({ ...current, [taskId]: { content, operationId, feedback: 'sending' } }))
    let delivered = false
    try {
      await taskState.steer(content.trim(), operationId)
      delivered = true
    } catch {
      // The hook owns the normalized error; retain this task's input for recovery.
    } finally {
      pendingSteeringRef.current.delete(taskId)
      setSteeringUpdatesByTask((current) => appendSteeringUpdate(current, taskId, {
        id: updateId, operationId, content: content.trim(), deliveredAt: new Date().toISOString(),
        status: delivered ? operationOutcomesRef.current.get(operationId) ?? 'delivered' : 'failed',
      }))
      setSteeringDrafts((current) => {
        const owned = current[taskId]
        if (owned?.operationId !== operationId || owned.content !== content) return current
        return { ...current, [taskId]: { ...owned, content: delivered ? '' : content, feedback: delivered ? 'sent' : 'failed' } }
      })
    }
  }

  const reuseSteeringDraft = () => {
    if (!task || !draft || pendingSteeringRef.current.has(task.taskId) || pendingDecision) return
    if (activeOperation) {
      setSteeringDrafts((current) => ({ ...current, [task.taskId]: { ...draft, operationId: activeOperation.operationId, feedback: null } }))
    } else {
      setMessageDrafts((current) => ({ ...current, [task.taskId]: [current[task.taskId], draft.content].filter(Boolean).join('\n\n') }))
      setSteeringDrafts((current) => ({ ...current, [task.taskId]: { ...draft, content: '', feedback: null } }))
    }
  }
  const steeringFeedback = staleSteering ? (
    <div className="codex-composer-feedback" role="status">
      <p>This update belongs to an earlier operation. Your draft is retained.</p>
      <button type="button" disabled={pendingDecision || draft?.feedback === 'sending' || Boolean(activeRun && !activeOperation)} onClick={reuseSteeringDraft}>
        {activeOperation ? 'Use for current operation' : 'Use as new message'}
      </button>
    </div>
  ) : draft?.feedback && draft.operationId === (activeOperation ?? latestOperation)?.operationId
    && !(activeRun && !activeOperation) ? (
    <div className="codex-composer-feedback" role={draft.feedback === 'failed' ? 'alert' : 'status'}>
      <strong>{draft.feedback === 'sending' ? 'Sending your update…' : draft.feedback === 'sent' ? 'Steering sent' : 'Steering wasn’t sent'}</strong>
      <p>{draft.feedback === 'failed' ? 'Your instruction is still in the box. Review the error above and try again.' : draft.feedback === 'sent' ? 'Codex accepted your update. Delivery does not mean the work is complete.' : 'You can keep reading while your update is delivered.'}</p>
    </div>
  ) : null
  const timelineOperation = activeOperation ?? (activeRun ? null : latestOperation)
  const timelineActivity = activeRun && !activeOperation ? [] : taskState.activity
  const operationSteeringUpdates = timelineOperation
    ? steeringUpdates.filter(({ operationId }) => operationId === timelineOperation.operationId)
    : []
  const steeringStatus = operationSteeringUpdates.length > 0
    ? presentedSteeringStatus(operationSteeringUpdates)
    : null
  const activityInput = {
    active: activeRun !== null || activeOperation !== null,
    operationStatus: timelineOperation?.status ?? null,
    reconnecting: taskState.reconnecting,
    interaction: taskState.interaction,
    activity: timelineActivity,
    steeringStatus,
    interactionAcknowledged: taskState.decisionReceipts.some((receipt) => receipt.taskId === task?.taskId
      && receipt.operationId === timelineOperation?.operationId
      && receipt.interactionId === (taskState.taskDetail?.pendingInteractions[0]?.interactionId ?? conversation.interactionHandoff?.interactionId)),
    phase: timelineOperation && taskState.stopRequestedOperationId === timelineOperation.operationId ? 'stopping' as const : activeRun?.phase,
  }
  const activityView = projectAgentActivity(activityInput)
  const activityPresentation = activeRun || timelineOperation ? (
    <CodexActivityTimeline
      {...activityInput}
      presentation={activityView}
      operationId={timelineOperation?.operationId}
      startedAt={timelineOperation?.createdAt}
      terminalAt={taskState.terminalTiming?.operationId === timelineOperation?.operationId ? taskState.terminalTiming?.updatedAt : null}
      receipts={taskState.decisionReceipts.filter((receipt) => receipt.taskId === task?.taskId && receipt.operationId === timelineOperation?.operationId)}
      decisionAnnouncement={taskState.decisionAnnouncement}
    />
  ) : null

  return (
    <main className="workspace-shell" data-sidebar-collapsed={sidebarCollapsed} aria-label="Synvo AI Assistant workspace">
      <CodexSidebar
        collapsed={sidebarCollapsed}
        modal={phone && !sidebarCollapsed && !taskState.interaction}
        settingsActive={view === 'settings'}
        tasks={taskState.tasks}
        selectedTaskId={taskState.selectedTaskId}
        archived={taskState.archived}
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
        onOpenSettings={() => {
          setView('settings')
          setTaskPanelOpen(false)
          collapseSidebarForNarrowViewport(setSidebarCollapsed)
        }}
      />

      {phone && !sidebarCollapsed && <button className="codex-navigation-backdrop" data-modal-backdrop aria-hidden="true" type="button" aria-label="Close navigation backdrop" tabIndex={-1} onClick={() => setSidebarCollapsed(true)} />}
      <section className="workspace-main">
        <header className="workspace-topbar">
          {phone && <button className="workspace-icon-button" type="button" aria-label="Open navigation" aria-expanded={!sidebarCollapsed} aria-controls="codex-navigation" onClick={() => setSidebarCollapsed(false)}><PanelLeftIcon /></button>}
          {view === 'settings' ? (
            <button className="workspace-icon-button workspace-topbar__back" type="button" aria-label="Back to Codex task" onClick={() => setView('conversation')}>
              <ArrowLeftIcon />
            </button>
          ) : <span className="workspace-topbar__folder" aria-hidden="true"><FolderIcon /></span>}
          <div className="workspace-topbar__title">
            <h1>{title}</h1>
            {task && <p className="codex-topbar-meta">{task.workspaceName} · {task.mode === 'READ_ONLY' ? 'Read Only' : 'Edit workspace files'}</p>}
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

        {taskState.reconnecting && <div className="codex-reconnect-notice" role="status">Reconnecting to Codex activity…</div>}
        {pendingStart && (taskState.error || conversation.conversationError) && <div className="codex-start-recovery" role="status">
          <p>Your task was created. Load it to send your retained request.</p>
          <button type="button" disabled={taskState.loadingTask || conversation.loadingConversation} onClick={() => void retryStartLoading()}>Retry loading task</button>
        </div>}
        {task && unconfirmedStart && <div className="codex-start-recovery" role="status">
          <p>Your earlier request is retained. Confirm this is the intended task before using it.</p>
          <button type="button" disabled={taskBusy || task.workspaceId !== unconfirmedStart.workspaceId} onClick={() => {
            setMessageDrafts((current) => ({ ...current, [task.taskId]: [current[task.taskId], unconfirmedStart.content].filter(Boolean).join('\n\n') }))
            setUnconfirmedStart(null)
          }}>Use retained request</button>
        </div>}
        <div
          className="workspace-content"
          data-artifact-open={taskPanelOpen && view === 'conversation'}
          data-codex-panel-open={taskPanelOpen && view === 'conversation'}
        >
          {view === 'settings' ? (
            <SettingsView botConnection={botConnection} busy={busy} onSignOut={onSignOut} status={taskState.status} workspaces={taskState.workspaces} />
          ) : taskState.loading ? (
            <div className="workspace-history-state" role="status">Preparing Synvo AI Assistant…</div>
          ) : !task ? (
            <CodexTaskSetup
              status={taskState.status}
              defaultReasoningEffort={defaultReasoningEffort}
              workspaces={taskState.workspaces}
              submitting={startingTask || pendingStart !== null}
              error={taskState.error}
              onCreate={createTask}
              creationUncertain={unconfirmedStart !== null}
              retainedRequest={unconfirmedStart?.content}
              onReviewTasks={() => setSidebarCollapsed(false)}
              onConfirmRetry={() => setUnconfirmedStart(null)}
            />
          ) : (
            <ConversationView
              turns={conversation.turns}
              userAvatarUrl={userAvatarUrl}
              composerValue={composerValue}
              loading={conversation.loadingConversation || taskState.loadingTask}
              error={conversation.conversationError ?? taskState.error}
              activeRun={activeRun}
              composerDisabled={composerDisabled}
              composerPlaceholder={pendingDecision ? 'Review the pending decision to continue.' : isSteering ? 'Add a constraint or refine the result…' : 'Ask Synvo to work in this workspace…'}
              composerAction={{
                label: isSteering ? 'Update instructions' : 'Send message',
                hint: pendingDecision ? 'A decision is required.' : isSteering ? 'Updates apply to this running task.' : 'Enter to send · Shift + Enter for a new line',
                busy: draft?.feedback === 'sending',
                stop: activeRun || activeOperation ? { label: 'Stop', disabled: pendingDecision || (activeRun ? !activeRun.runId || activeRun.phase === 'stopping' : taskState.submitting !== null) } : undefined,
                feedback: steeringFeedback,
              }}
              composerControls={(
                <CodexComposerControls
                  reasoningEfforts={taskState.status?.reasoningEfforts ?? []}
                  reasoningEffort={reasoningEffort}
                  skills={taskState.inventory.skills}
                  skillName={skillName}
                  disabled={activeRun !== null || activeOperation !== null || pendingDecision}
                  onReasoningEffortChange={(effort) => {
                    if (task && availableEfforts.includes(effort)) {
                      setReasoningEffortsByTask((current) => ({ ...current, [task.taskId]: effort }))
                    }
                  }}
                  onSkillNameChange={setSkillName}
                />
              )}
              activityPresentation={activityPresentation}
              responseStreaming={activityView.streaming}
              onComposerChange={changeComposer}
              onSubmit={(content) => void submitComposer(content)}
              onStop={() => void (activeRun ? conversation.stopRun() : taskState.stopOperation()).catch(() => {})}
              onRetry={conversation.retryTurn}
              onBranch={() => { if (!taskBusy) void taskState.forkTask(`Fork of ${task.title}`).catch(() => {}) }}
            />
          )}

          {view === 'conversation' && taskPanelOpen && taskState.taskDetail && (
            <CodexTaskPanel
              key={taskState.taskDetail.task.taskId}
              taskDetail={taskState.taskDetail}
              modal={panelOverlay && !taskState.interaction}
              activity={taskState.activity}
              inventory={taskState.inventory}
              goal={taskState.goal}
              steeringUpdates={steeringUpdates}
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

function steeringMilestoneStatus(
  operationStatus: CodexOperationStatus,
  terminalStatus: CodexTerminalStatus | null,
): CodexSteeringMilestoneStatus {
  if (terminalStatus === 'COMPLETED' || operationStatus === 'COMPLETED') return 'completed'
  if (terminalStatus === 'STOPPED' || operationStatus === 'STOPPED') return 'stopped'
  if (terminalStatus !== null || operationStatus === 'FAILED') return 'failed'
  return 'delivered'
}

function timelineSteeringStatus(
  operation: { status: CodexOperationStatus } | null,
  terminalStatus: CodexTerminalStatus | null,
): CodexSteeringUpdate['status'] {
  if (!operation) return 'delivered'
  return steeringMilestoneStatus(operation.status, terminalStatus)
}

function appendSteeringUpdate(
  updatesByTask: Record<string, CodexSteeringUpdate[]>,
  taskId: string,
  update: CodexSteeringUpdate,
) {
  return { ...updatesByTask, [taskId]: [...(updatesByTask[taskId] ?? []), update] }
}

function updateSteeringStatuses(
  updatesByTask: Record<string, CodexSteeringUpdate[]>,
  taskId: string,
  operationId: string,
  status: CodexSteeringUpdate['status'],
) {
  const updates = updatesByTask[taskId]
  if (!updates?.some((update) => update.operationId === operationId && update.status === 'delivered')) {
    return updatesByTask
  }
  return {
    ...updatesByTask,
    [taskId]: updates.map((update) => update.operationId === operationId && update.status === 'delivered'
      ? { ...update, status }
      : update),
  }
}

function withoutSteeringHistory(updatesByTask: Record<string, CodexSteeringUpdate[]>, taskId: string) {
  const { [taskId]: _removed, ...remaining } = updatesByTask
  return remaining
}

function presentedSteeringStatus(updates: CodexSteeringUpdate[]): CodexSteeringMilestoneStatus {
  if (updates.some(({ status }) => status === 'delivered')) return 'delivered'
  if (updates.some(({ status }) => status === 'completed')) return 'completed'
  if (updates.some(({ status }) => status === 'failed')) return 'failed'
  return 'stopped'
}
