import {
  useCallback,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type FormEvent,
  type KeyboardEvent,
  type ReactNode,
} from 'react'
import type { BotConnection } from '../api/lark'
import type { WorkflowPresentation } from '../api/presentations'
import synvoAvatar from '../../assets/logo.jpg'
import defaultUserAvatar from '../../assets/user.png'
import {
  conversationApi,
  type ConversationApi,
  type ConversationStreamEvent,
  type ConversationSubscription,
  type ConversationSummary,
  type ConversationTurn,
} from '../api/conversations'
import { AssistantMarkdown } from './AssistantMarkdown'
import { WorkflowPresentationList } from './WorkflowPresentation'

interface WorkspaceProps {
  botConnection: BotConnection
  busy: boolean
  userAvatarUrl?: string | null
  onSignOut: () => void
  api?: ConversationApi
}

const connectionNotices: Partial<Record<BotConnection, string>> = {
  connecting: 'The assistant channel is connecting.',
  reconnecting: 'The assistant channel is reconnecting automatically.',
  failed: 'The assistant channel needs attention. Your Lark authorization is still protected.',
  disabled: 'The assistant channel is disabled in this environment.',
}

const COMPOSER_MIN_HEIGHT_PX = 44
const COMPOSER_MAX_HEIGHT_PX = 200
const CONVERSATION_BOTTOM_THRESHOLD_PX = 96
const SINGAPORE_TIME_ZONE = 'Asia/Singapore'

type WorkspaceView = 'conversation' | 'settings'
type RunPhase = 'accepted' | 'thinking' | 'streaming' | 'tool_running' | 'reconnecting' | 'stopping'

interface ActiveRun {
  requestId: string
  runId: string | null
  assistantTurnId: string
  phase: RunPhase
}

interface RetryTarget {
  userTurn: ConversationTurn
  assistantTurn: ConversationTurn
}

export function Workspace({
  botConnection,
  busy,
  userAvatarUrl = null,
  onSignOut,
  api = conversationApi,
}: WorkspaceProps) {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(initialSidebarCollapsed)
  const [view, setView] = useState<WorkspaceView>('conversation')
  const [selectedConversation, setSelectedConversation] = useState<string | null>(null)
  const [artifactOpen, setArtifactOpen] = useState(false)
  const [recentConversations, setRecentConversations] = useState<ConversationSummary[]>([])
  const [turns, setTurns] = useState<ConversationTurn[]>([])
  const [composerValue, setComposerValue] = useState('')
  const [loadingConversation, setLoadingConversation] = useState(false)
  const [conversationError, setConversationError] = useState<string | null>(null)
  const [activeRun, setActiveRun] = useState<ActiveRun | null>(null)
  const [presentations, setPresentations] = useState<WorkflowPresentation[]>([])
  const [deleteTarget, setDeleteTarget] = useState<ConversationSummary | null>(null)
  const [deletingConversation, setDeletingConversation] = useState(false)
  const [deleteConversationError, setDeleteConversationError] = useState<string | null>(null)
  const [exitingConversationId, setExitingConversationId] = useState<string | null>(null)
  const streamRef = useRef<ConversationSubscription | null>(null)
  const runInFlightRef = useRef(false)
  const newConversationRef = useRef<HTMLButtonElement>(null)
  const deleteTriggerRef = useRef<HTMLButtonElement | null>(null)
  const deleteExitTimerRef = useRef<number | null>(null)

  const refreshRecent = useCallback(async (signal?: AbortSignal) => {
    try {
      setRecentConversations(await api.list(signal))
    } catch (error: unknown) {
      if (!signal?.aborted) setConversationError(safeMessage(error))
    }
  }, [api])

  useEffect(() => {
    const controller = new AbortController()
    void refreshRecent(controller.signal)
    return () => {
      controller.abort()
      streamRef.current?.close()
      if (deleteExitTimerRef.current !== null) window.clearTimeout(deleteExitTimerRef.current)
    }
  }, [refreshRecent])

  const currentTitle = selectedConversation
    ? recentConversations.find(({ conversationId }) => conversationId === selectedConversation)?.title
      ?? 'Conversation'
    : 'New conversation'
  const connectionNotice = connectionNotices[botConnection]
  const assistantReady = botConnection === 'connected'
  const assistantAvailability = assistantReady
    ? 'Synvo AI Assistant is connected and ready.'
    : 'Synvo AI Assistant is not connected.'

  const openConversation = async (id: string | null) => {
    if (activeRun) return
    streamRef.current?.close()
    streamRef.current = null
    setSelectedConversation(id)
    setView('conversation')
    setArtifactOpen(false)
    setPresentations([])
    setConversationError(null)
    collapseSidebarForNarrowViewport(setSidebarCollapsed)
    if (id === null) {
      setTurns([])
      return
    }
    setLoadingConversation(true)
    try {
      const detail = await api.get(id)
      setTurns(detail.turns)
    } catch (error: unknown) {
      setConversationError(safeMessage(error))
      setTurns([])
    } finally {
      setLoadingConversation(false)
    }
  }

  const finishStream = useCallback(() => {
    streamRef.current?.close()
    streamRef.current = null
    runInFlightRef.current = false
    setActiveRun(null)
    void refreshRecent()
  }, [refreshRecent])

  const applyStreamEvent = useCallback((
    event: ConversationStreamEvent,
    assistantTurnId: string,
  ) => {
    const presentation = event.presentation
    if (presentation) {
      setPresentations((current) => upsertPresentation(current, presentation))
    }
    if (event.type === 'content_delta' && event.delta) {
      setTurns((current) => current.map((turn) => turn.turnId === assistantTurnId
        ? { ...turn, content: turn.content + event.delta, status: 'STREAMING' }
        : turn))
      setActiveRun((current) => current ? { ...current, phase: 'streaming' } : current)
      return
    }
    if (event.type === 'content_reset') {
      setTurns((current) => current.map((turn) => turn.turnId === assistantTurnId
        ? { ...turn, content: '', status: 'PENDING' }
        : turn))
      setActiveRun((current) => current ? { ...current, phase: 'thinking' } : current)
      return
    }
    if (event.type === 'failed') {
      const now = new Date().toISOString()
      setTurns((current) => current.map((turn) => turn.turnId === assistantTurnId
        ? {
            ...turn,
            content: event.message ?? 'I couldn’t complete that response. Please try again.',
            status: 'FAILED',
            updatedAt: now,
          }
        : turn))
      finishStream()
      return
    }
    if (event.type === 'completed') {
      const now = new Date().toISOString()
      setTurns((current) => current.map((turn) => turn.turnId === assistantTurnId
        ? { ...turn, status: 'COMPLETED', updatedAt: now }
        : turn))
      finishStream()
      return
    }
    if (['accepted', 'thinking', 'streaming', 'tool_running'].includes(event.type)) {
      setActiveRun((current) => current
        ? { ...current, phase: event.type as RunPhase }
        : current)
    }
  }, [finishStream])

  const submitMessage = useCallback(async (
    rawContent: string,
    retryTarget?: RetryTarget,
  ) => {
    const content = rawContent.trim()
    if (!content || activeRun || runInFlightRef.current) return
    runInFlightRef.current = true
    const requestId = createRequestId()
    const localUserTurnId = `local-user-${requestId}`
    const localAssistantTurnId = `local-assistant-${requestId}`
    const now = new Date().toISOString()
    const userTurn: ConversationTurn = {
      turnId: localUserTurnId,
      role: 'USER',
      content,
      status: 'COMPLETED',
      createdAt: now,
      updatedAt: now,
    }
    const assistantTurn: ConversationTurn = {
      turnId: localAssistantTurnId,
      role: 'ASSISTANT',
      content: '',
      status: 'PENDING',
      createdAt: now,
      updatedAt: now,
    }

    setConversationError(null)
    setComposerValue('')
    setTurns((current) => retryTarget
      ? current.map((turn) => turn.turnId === retryTarget.assistantTurn.turnId
        ? assistantTurn
        : turn)
      : [...current, userTurn, assistantTurn])
    setActiveRun({ requestId, runId: null, assistantTurnId: localAssistantTurnId, phase: 'accepted' })

    let assistantTurnIdForFailure = localAssistantTurnId
    let submissionAccepted = false
    try {
      const csrfToken = await api.csrfToken()
      const run = await api.submit(
        {
          requestId,
          conversationId: selectedConversation,
          content,
          ...(retryTarget
            ? { replaceFailedAssistantTurnId: retryTarget.assistantTurn.turnId }
            : {}),
        },
        csrfToken,
      )
      submissionAccepted = true
      const userTurnId = run.userTurnId ?? localUserTurnId
      const assistantTurnId = run.assistantTurnId ?? localAssistantTurnId
      assistantTurnIdForFailure = assistantTurnId
      setSelectedConversation(run.conversationId)
      setTurns((current) => current.map((turn) => {
        if (turn.turnId === (retryTarget?.userTurn.turnId ?? localUserTurnId)) {
          return { ...turn, turnId: userTurnId, updatedAt: now }
        }
        if (turn.turnId === localAssistantTurnId) return { ...turn, turnId: assistantTurnId }
        return turn
      }))
      setActiveRun({ requestId, runId: run.runId, assistantTurnId, phase: 'accepted' })
      void refreshRecent()

      let subscription: ConversationSubscription | null = null
      subscription = api.subscribe(
        run.runId,
        (event) => {
          applyStreamEvent(event, assistantTurnId)
          if (event.type === 'completed' || event.type === 'failed') subscription?.close()
        },
        () => setActiveRun((current) => current
          ? { ...current, phase: 'reconnecting' }
          : current),
      )
      streamRef.current = subscription
    } catch (error: unknown) {
      if (retryTarget && !submissionAccepted) {
        setTurns((current) => current.map((turn) => turn.turnId === localAssistantTurnId
          ? retryTarget.assistantTurn
          : turn))
        setConversationError(safeMessage(error))
      } else {
        setTurns((current) => current.map((turn) => turn.turnId === assistantTurnIdForFailure
          ? { ...turn, content: safeMessage(error), status: 'FAILED' }
          : turn))
      }
      runInFlightRef.current = false
      setActiveRun(null)
    }
  }, [activeRun, api, applyStreamEvent, refreshRecent, selectedConversation])

  const stopRun = async () => {
    if (!activeRun?.runId) return
    setActiveRun({ ...activeRun, phase: 'stopping' })
    try {
      const csrfToken = await api.csrfToken()
      await api.stop(activeRun.runId, csrfToken)
    } catch (error: unknown) {
      setConversationError(safeMessage(error))
      setActiveRun((current) => current ? { ...current, phase: 'reconnecting' } : current)
    }
  }

  const retryTurn = (failedTurnId: string) => {
    const failedIndex = turns.findIndex(({ turnId }) => turnId === failedTurnId)
    const priorUserTurn = turns.slice(0, failedIndex).reverse().find(({ role }) => role === 'USER')
    const failedTurn = turns[failedIndex]
    if (priorUserTurn && failedTurn?.role === 'ASSISTANT' && failedTurn.status === 'FAILED') {
      void submitMessage(priorUserTurn.content, {
        userTurn: priorUserTurn,
        assistantTurn: failedTurn,
      })
    }
  }

  const closeDeleteDialog = () => {
    if (deletingConversation) return
    setDeleteTarget(null)
    setDeleteConversationError(null)
    queueMicrotask(() => deleteTriggerRef.current?.focus())
  }

  const finishDeleteAnimation = useCallback((conversationId: string) => {
    if (deleteExitTimerRef.current !== null) {
      window.clearTimeout(deleteExitTimerRef.current)
      deleteExitTimerRef.current = null
    }
    setRecentConversations((current) => current.filter(
      (conversation) => conversation.conversationId !== conversationId,
    ))
    setExitingConversationId((current) => current === conversationId ? null : current)
    queueMicrotask(() => newConversationRef.current?.focus())
  }, [])

  const confirmDeleteConversation = async () => {
    const target = deleteTarget
    if (!target || deletingConversation || activeRun) return
    setDeletingConversation(true)
    setDeleteConversationError(null)
    try {
      const csrfToken = await api.csrfToken()
      await api.remove(target.conversationId, csrfToken)
      setDeleteTarget(null)
      setExitingConversationId(target.conversationId)
      if (selectedConversation === target.conversationId) {
        streamRef.current?.close()
        streamRef.current = null
        setSelectedConversation(null)
        setTurns([])
        setPresentations([])
        setArtifactOpen(false)
        setView('conversation')
        setConversationError(null)
      }
      deleteExitTimerRef.current = window.setTimeout(
        () => finishDeleteAnimation(target.conversationId),
        280,
      )
    } catch (error: unknown) {
      setDeleteConversationError(safeMessage(error))
    } finally {
      setDeletingConversation(false)
    }
  }

  return (
    <main
      className="workspace-shell"
      data-sidebar-collapsed={sidebarCollapsed}
      aria-label="Synvo AI workspace"
    >
      <aside className="workspace-sidebar" aria-label="Synvo navigation">
        <div className="workspace-sidebar__brand">
          <SynvoLogo />
          <strong className="workspace-sidebar__label">Synvo</strong>
          <button
            className="workspace-icon-button workspace-sidebar__collapse"
            type="button"
            aria-label={sidebarCollapsed ? 'Expand sidebar' : 'Hide sidebar'}
            onClick={() => setSidebarCollapsed((collapsed) => !collapsed)}
          >
            <PanelLeftIcon />
          </button>
        </div>

        <button
          ref={newConversationRef}
          className="workspace-new-button"
          type="button"
          aria-label="New conversation"
          disabled={activeRun !== null}
          onClick={() => void openConversation(null)}
        >
          <PlusIcon />
          <span className="workspace-sidebar__label">New conversation</span>
        </button>

        <div className="workspace-sidebar__scroll">
          <nav
            className="workspace-sidebar__section workspace-workflows"
            aria-label={sidebarCollapsed ? 'Workflows' : undefined}
            aria-labelledby={sidebarCollapsed ? undefined : 'workflow-navigation-title'}
          >
            {!sidebarCollapsed && (
              <h2 id="workflow-navigation-title" className="workspace-sidebar__section-title">Workflows</h2>
            )}
            <button
              className="workspace-nav-item"
              type="button"
              aria-label="Enterprise Research — planned"
              disabled
              title="Planned for a later phase"
            >
              <ResearchIcon />
              <span className="workspace-sidebar__label">Enterprise Research</span>
              <small className="workspace-sidebar__label">Planned</small>
            </button>
            <button
              className="workspace-nav-item"
              type="button"
              aria-label="Meeting to Execution — planned"
              disabled
              title="Planned for a later phase"
            >
              <MeetingIcon />
              <span className="workspace-sidebar__label">Meeting to Execution</span>
              <small className="workspace-sidebar__label">Planned</small>
            </button>
          </nav>

          <nav
            className="workspace-sidebar__section workspace-recents"
            aria-label={sidebarCollapsed ? 'Recent' : undefined}
            aria-labelledby={sidebarCollapsed ? undefined : 'recent-navigation-title'}
          >
            {!sidebarCollapsed && (
              <h2 id="recent-navigation-title" className="workspace-sidebar__section-title">Recent</h2>
            )}
            {recentConversations.map((conversation) => (
              <div
                key={conversation.conversationId}
                className="workspace-recent-item"
                data-active={view === 'conversation' && selectedConversation === conversation.conversationId}
                data-deleting={exitingConversationId === conversation.conversationId}
                onAnimationEnd={() => {
                  if (exitingConversationId === conversation.conversationId) {
                    finishDeleteAnimation(conversation.conversationId)
                  }
                }}
              >
                <button
                  className="workspace-nav-item workspace-recent-item__open"
                  type="button"
                  aria-label={conversation.title}
                  aria-current={view === 'conversation' && selectedConversation === conversation.conversationId ? 'page' : undefined}
                  disabled={activeRun !== null || exitingConversationId === conversation.conversationId}
                  onClick={() => void openConversation(conversation.conversationId)}
                >
                  <ConversationIcon />
                  <span className="workspace-sidebar__label">{conversation.title}</span>
                </button>
                {!sidebarCollapsed && (
                  <button
                    className="workspace-recent-item__delete"
                    type="button"
                    aria-label={`Delete chat “${conversation.title}”`}
                    title="Delete chat"
                    disabled={activeRun !== null || loadingConversation || exitingConversationId === conversation.conversationId}
                    onClick={(event) => {
                      deleteTriggerRef.current = event.currentTarget
                      setDeleteTarget(conversation)
                      setDeleteConversationError(null)
                    }}
                  >
                    <TrashIcon />
                  </button>
                )}
              </div>
            ))}
            {recentConversations.length === 0 && (
              <p className="workspace-sidebar__empty workspace-sidebar__label">
                No conversations yet
              </p>
            )}
          </nav>
        </div>

        <div className="workspace-sidebar__footer">
          <button
            className="workspace-nav-item workspace-sidebar__settings"
            data-active={view === 'settings'}
            type="button"
            aria-label="Settings"
            aria-current={view === 'settings' ? 'page' : undefined}
            onClick={() => {
              setView('settings')
              setArtifactOpen(false)
              collapseSidebarForNarrowViewport(setSidebarCollapsed)
            }}
          >
            <SettingsIcon />
            <span className="workspace-sidebar__label">Settings</span>
            <span
              className="workspace-settings-availability"
              data-state={assistantReady ? 'connected' : 'disconnected'}
              aria-hidden="true"
            />
          </button>
          <span className="sr-only" role="status" aria-live="polite">
            {assistantAvailability}
          </span>
        </div>
      </aside>

      <section className="workspace-main">
        <header className="workspace-topbar">
          {view === 'settings' ? (
            <button
              className="workspace-icon-button workspace-topbar__back"
              type="button"
              aria-label="Back to conversation"
              title={`Back to ${currentTitle}`}
              onClick={() => setView('conversation')}
            >
              <ArrowLeftIcon />
            </button>
          ) : (
            <span className="workspace-topbar__folder" aria-hidden="true"><FolderIcon /></span>
          )}
          <div className="workspace-topbar__title">
            <h1>{view === 'settings' ? 'Settings' : currentTitle}</h1>
          </div>
          {view === 'conversation' && (
            <button
              className="workspace-secondary-button"
              type="button"
              aria-label="Artifacts"
              aria-expanded={artifactOpen}
              aria-controls="artifact-panel"
              onClick={() => setArtifactOpen((open) => !open)}
            >
              <ArtifactIcon />
              <span>Artifacts</span>
            </button>
          )}
        </header>

        {connectionNotice && (
          <div className="workspace-connection-notice" role="status">
            <span aria-hidden="true" />
            {connectionNotice}
          </div>
        )}

        <div className="workspace-content" data-artifact-open={artifactOpen && view === 'conversation'}>
          {view === 'settings' ? (
            <SettingsView botConnection={botConnection} busy={busy} onSignOut={onSignOut} />
          ) : (
            <ConversationView
              turns={turns}
              userAvatarUrl={userAvatarUrl}
              composerValue={composerValue}
              loading={loadingConversation}
              error={conversationError}
              activeRun={activeRun}
              onComposerChange={setComposerValue}
              onSubmit={(content) => void submitMessage(content)}
              onStop={() => void stopRun()}
              onRetry={retryTurn}
            />
          )}

          {view === 'conversation' && artifactOpen && (
            <aside id="artifact-panel" className="workspace-artifact-panel" aria-label="Artifacts">
              <div className="workspace-artifact-panel__header">
                <div>
                  <p>Conversation context</p>
                  <h2>Artifacts</h2>
                </div>
                <button
                  className="workspace-icon-button"
                  type="button"
                  aria-label="Close artifact panel"
                  onClick={() => setArtifactOpen(false)}
                >
                  <CloseIcon />
                </button>
              </div>
              {presentations.length === 0 ? (
                <div className="workspace-artifact-panel__empty">
                  <ArtifactIcon />
                  <h3>No artifacts yet</h3>
                  <p>Cited research and reviewable execution plans will appear here when their workflows are available.</p>
                </div>
              ) : (
                <WorkflowPresentationList presentations={presentations} />
              )}
            </aside>
          )}
        </div>
      </section>
      {deleteTarget && (
        <DeleteConversationDialog
          conversation={deleteTarget}
          deleting={deletingConversation}
          error={deleteConversationError}
          onCancel={closeDeleteDialog}
          onConfirm={() => void confirmDeleteConversation()}
        />
      )}
    </main>
  )
}

function DeleteConversationDialog({
  conversation,
  deleting,
  error,
  onCancel,
  onConfirm,
}: {
  conversation: ConversationSummary
  deleting: boolean
  error: string | null
  onCancel: () => void
  onConfirm: () => void
}) {
  const cancelRef = useRef<HTMLButtonElement>(null)
  const dialogRef = useRef<HTMLElement>(null)

  useEffect(() => {
    cancelRef.current?.focus()
  }, [])

  useEffect(() => {
    const closeOnEscape = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape' && !deleting) onCancel()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [deleting, onCancel])

  const keepFocusInside = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key !== 'Tab') return
    const buttons = Array.from(
      dialogRef.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [],
    )
    if (buttons.length === 0) return
    const first = buttons[0]
    const last = buttons.at(-1)
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last?.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first?.focus()
    }
  }

  return (
    <div className="workspace-dialog-backdrop">
      <section
        ref={dialogRef}
        className="workspace-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="delete-chat-title"
        aria-describedby="delete-chat-description"
        onKeyDown={keepFocusInside}
      >
        <div className="workspace-dialog__icon" aria-hidden="true"><TrashIcon /></div>
        <div className="workspace-dialog__content">
          <h2 id="delete-chat-title">Delete this chat?</h2>
          <p id="delete-chat-description">
            This will permanently delete “{conversation.title}” and all its messages. This action cannot be undone.
          </p>
          {error && <p className="workspace-dialog__error" role="alert">{error}</p>}
        </div>
        <div className="workspace-dialog__actions">
          <button ref={cancelRef} type="button" disabled={deleting} onClick={onCancel}>Cancel</button>
          <button type="button" disabled={deleting} onClick={onConfirm}>
            {deleting ? 'Deleting…' : 'Delete chat'}
          </button>
        </div>
      </section>
    </div>
  )
}

function ConversationView({
  turns,
  userAvatarUrl,
  composerValue,
  loading,
  error,
  activeRun,
  onComposerChange,
  onSubmit,
  onStop,
  onRetry,
}: {
  turns: ConversationTurn[]
  userAvatarUrl: string | null
  composerValue: string
  loading: boolean
  error: string | null
  activeRun: ActiveRun | null
  onComposerChange: (value: string) => void
  onSubmit: (content: string) => void
  onStop: () => void
  onRetry: (failedTurnId: string) => void
}) {
  const composerRef = useRef<HTMLTextAreaElement>(null)
  const streamViewportRef = useRef<HTMLDivElement>(null)
  const lastUserTurnIdRef = useRef<string | null>(null)
  const stickToBottomRef = useRef(true)

  const lastUserTurnId = turns.findLast(({ role }) => role === 'USER')?.turnId ?? null

  useLayoutEffect(() => {
    resizeComposerTextarea(composerRef.current)
  }, [composerValue])

  useLayoutEffect(() => {
    const viewport = streamViewportRef.current
    const hasNewUserTurn = lastUserTurnId !== null && lastUserTurnId !== lastUserTurnIdRef.current
    if (viewport && (hasNewUserTurn || stickToBottomRef.current)) {
      if (hasNewUserTurn) stickToBottomRef.current = true
      scrollConversationToBottom(viewport)
    }
    lastUserTurnIdRef.current = lastUserTurnId
  }, [lastUserTurnId, loading, turns])

  useEffect(() => {
    const resize = () => resizeComposerTextarea(composerRef.current)
    window.addEventListener('resize', resize)
    return () => window.removeEventListener('resize', resize)
  }, [])

  const submit = (event: FormEvent) => {
    event.preventDefault()
    onSubmit(composerValue)
  }
  const handleKeyDown = (event: KeyboardEvent<HTMLTextAreaElement>) => {
    if (event.key === 'Enter' && !event.shiftKey) {
      event.preventDefault()
      onSubmit(composerValue)
    }
  }
  const updateScrollPreference = () => {
    const viewport = streamViewportRef.current
    if (!viewport) return
    const distanceFromBottom = viewport.scrollHeight - viewport.scrollTop - viewport.clientHeight
    stickToBottomRef.current = distanceFromBottom <= CONVERSATION_BOTTOM_THRESHOLD_PX
  }

  return (
    <section className="workspace-conversation" aria-label="Conversation">
      <div
        ref={streamViewportRef}
        className="workspace-conversation__stream"
        aria-live="polite"
        onScroll={updateScrollPreference}
      >
        {loading && <div className="workspace-history-state" role="status">Loading conversation…</div>}
        {!loading && error && <div className="workspace-history-state workspace-history-state--error" role="alert">{error}</div>}
        {!loading && turns.length === 0 && (
          <div className="workspace-empty-state">
            <SynvoLogo large />
            <h2>How can Synvo help?</h2>
            <p>Ask a general question, continue a conversation, or describe what you want to accomplish in natural language.</p>
          </div>
        )}
        {!loading && turns.length > 0 && (
          <div className="workspace-turn-list">
            {turns.map((turn) => (
              <ConversationTurnView
                key={turn.turnId}
                turn={turn}
                userAvatarUrl={userAvatarUrl}
                active={activeRun?.assistantTurnId === turn.turnId}
                activity={activeRun?.assistantTurnId === turn.turnId ? activityLabel(activeRun.phase) : null}
                onRetry={() => onRetry(turn.turnId)}
              />
            ))}
          </div>
        )}
      </div>

      <form className="workspace-composer" onSubmit={submit} aria-label="Message composer">
        <label className="sr-only" htmlFor="synvo-message">Message Synvo</label>
        <textarea
          ref={composerRef}
          id="synvo-message"
          value={composerValue}
          disabled={activeRun !== null}
          placeholder={activeRun ? 'Synvo is responding…' : 'Message Synvo…'}
          rows={2}
          onChange={(event) => onComposerChange(event.target.value)}
          onKeyDown={handleKeyDown}
        />
        <div className="workspace-composer__footer">
          <span>{activeRun ? activityLabel(activeRun.phase) : 'Enter to send · Shift + Enter for a new line'}</span>
          {activeRun ? (
            <button type="button" aria-label="Stop response" onClick={onStop} disabled={!activeRun.runId}>
              <StopIcon />
            </button>
          ) : (
            <button type="submit" disabled={!composerValue.trim()} aria-label="Send message"><ArrowUpIcon /></button>
          )}
        </div>
      </form>
    </section>
  )
}

function ConversationTurnView({
  turn,
  userAvatarUrl,
  active,
  activity,
  onRetry,
}: {
  turn: ConversationTurn
  userAvatarUrl: string | null
  active: boolean
  activity: string | null
  onRetry: () => void
}) {
  const assistantWaiting = turn.role === 'ASSISTANT' && !turn.content && (turn.status === 'PENDING' || active)
  const showAssistantActions = turn.role === 'ASSISTANT' && turn.status === 'COMPLETED' && Boolean(turn.content)
  return (
    <article
      className="workspace-turn"
      data-role={turn.role.toLowerCase()}
      data-status={turn.status.toLowerCase()}
      aria-label={turn.role === 'ASSISTANT' ? 'Synvo response' : 'Your message'}
    >
      <div className="workspace-turn__identity" aria-hidden="true">
        <ConversationAvatar role={turn.role} userAvatarUrl={userAvatarUrl} />
      </div>
      <div className="workspace-turn__body">
        {assistantWaiting ? (
          <div className="workspace-typing" role="status" aria-label={activity ?? 'Synvo is thinking'}>
            <span /><span /><span />
          </div>
        ) : (
          <div className="workspace-turn__content">
            {turn.role === 'ASSISTANT' ? <AssistantMarkdown>{turn.content}</AssistantMarkdown> : turn.content}
          </div>
        )}
        {active && turn.content && activity && (
          <div className="workspace-turn__activity" role="status">{activity}</div>
        )}
        {showAssistantActions && <AssistantTurnActions turn={turn} />}
        {turn.status === 'FAILED' && (
          <button className="workspace-retry-button" type="button" onClick={onRetry}>Retry</button>
        )}
      </div>
    </article>
  )
}

function AssistantTurnActions({ turn }: { turn: ConversationTurn }) {
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle')

  useEffect(() => {
    if (copyState === 'idle') return
    const timeout = window.setTimeout(() => setCopyState('idle'), 1800)
    return () => window.clearTimeout(timeout)
  }, [copyState])

  const copyResponse = async () => {
    try {
      await navigator.clipboard.writeText(turn.content)
      setCopyState('copied')
    } catch {
      setCopyState('failed')
    }
  }

  const copyLabel = copyState === 'copied'
    ? 'Response copied'
    : copyState === 'failed'
      ? 'Could not copy response'
      : 'Copy response'

  return (
    <div className="workspace-turn__actions" aria-label="Response actions">
      <button
        type="button"
        aria-label={copyLabel}
        title={copyLabel}
        data-copy-state={copyState}
        onClick={() => void copyResponse()}
      >
        {copyState === 'copied' ? <CheckIcon /> : <CopyIcon />}
      </button>
      <button
        type="button"
        aria-label="Branch in new chat — coming soon"
        title="Branch in new chat — coming soon"
        disabled
      >
        <BranchIcon />
      </button>
      <time dateTime={turn.updatedAt} title={formatTurnDateTime(turn.updatedAt)}>
        {formatTurnTime(turn.updatedAt)}
      </time>
      <span className="sr-only" role="status" aria-live="polite">
        {copyState === 'copied' ? 'Response copied to clipboard.' : copyState === 'failed' ? 'Response could not be copied.' : ''}
      </span>
    </div>
  )
}

function SettingsView({
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

function activityLabel(phase: RunPhase) {
  switch (phase) {
    case 'accepted': return 'Starting…'
    case 'thinking': return 'Preparing a response…'
    case 'streaming': return 'Responding…'
    case 'tool_running': return 'Running an approved operation…'
    case 'reconnecting': return 'Reconnecting to the response…'
    case 'stopping': return 'Stopping…'
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

function createRequestId() {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function safeMessage(error: unknown) {
  return error instanceof Error ? error.message : 'Synvo could not complete that request. Please try again.'
}

function resizeComposerTextarea(textarea: HTMLTextAreaElement | null) {
  if (!textarea) return
  textarea.style.height = 'auto'
  const nextHeight = Math.max(
    COMPOSER_MIN_HEIGHT_PX,
    Math.min(textarea.scrollHeight, COMPOSER_MAX_HEIGHT_PX),
  )
  textarea.style.height = `${nextHeight}px`
  textarea.style.overflowY = textarea.scrollHeight > COMPOSER_MAX_HEIGHT_PX ? 'auto' : 'hidden'
}

function scrollConversationToBottom(viewport: HTMLDivElement) {
  const top = Math.max(0, viewport.scrollHeight - viewport.clientHeight)
  viewport.scrollTop = top
}

function formatTurnTime(timestamp: string) {
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('en-SG', {
    timeZone: SINGAPORE_TIME_ZONE,
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
  }).format(date)
}

function formatTurnDateTime(timestamp: string) {
  const date = new Date(timestamp)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat('en-SG', {
    timeZone: SINGAPORE_TIME_ZONE,
    year: 'numeric',
    month: 'short',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
    hourCycle: 'h23',
    timeZoneName: 'short',
  }).format(date)
}

function initialSidebarCollapsed() {
  return typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 760px)').matches
}

function collapseSidebarForNarrowViewport(setCollapsed: (collapsed: boolean) => void) {
  if (typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 760px)').matches) {
    setCollapsed(true)
  }
}

function upsertPresentation(
  current: WorkflowPresentation[],
  next: WorkflowPresentation,
) {
  const index = current.findIndex(({ id }) => id === next.id)
  if (index < 0) return [...current, next]
  return current.map((presentation, position) => position === index ? next : presentation)
}

function SynvoLogo({ large = false }: { large?: boolean }) {
  return (
    <img
      className={`workspace-synvo-logo${large ? ' workspace-synvo-logo--large' : ''}`}
      src={synvoAvatar}
      alt=""
      aria-hidden="true"
    />
  )
}

function ConversationAvatar({
  role,
  userAvatarUrl,
}: {
  role: ConversationTurn['role']
  userAvatarUrl: string | null
}) {
  const userSource = userAvatarUrl?.trim() || defaultUserAvatar
  return (
    <img
      className="workspace-turn__avatar"
      src={role === 'ASSISTANT' ? synvoAvatar : userSource}
      alt=""
      onError={role === 'USER' ? (event) => {
        if (event.currentTarget.getAttribute('src') !== defaultUserAvatar) {
          event.currentTarget.src = defaultUserAvatar
        }
      } : undefined}
    />
  )
}

type IconProps = { className?: string }

function Icon({ children, className }: IconProps & { children: ReactNode }) {
  return <svg className={className} aria-hidden="true" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">{children}</svg>
}

function PanelLeftIcon() { return <Icon><rect x="3" y="4" width="18" height="16" rx="2" /><path d="M9 4v16" /></Icon> }
function PlusIcon() { return <Icon><path d="M12 5v14M5 12h14" /></Icon> }
function ResearchIcon() { return <Icon><circle cx="11" cy="11" r="6.5" /><path d="m16 16 4 4M8.5 11h5M11 8.5v5" /></Icon> }
function MeetingIcon() { return <Icon><rect x="4" y="5" width="16" height="15" rx="2" /><path d="M8 3v4M16 3v4M4 10h16M8 14h3M8 17h6" /></Icon> }
function ConversationIcon() { return <Icon><path d="M5 5h14v11H9l-4 3V5Z" /></Icon> }
function TrashIcon() { return <Icon><path d="M4 7h16M9 7V4h6v3M7 7l1 13h8l1-13M10 11v5M14 11v5" /></Icon> }
function SettingsIcon() { return <Icon><circle cx="12" cy="12" r="3" /><path d="M19.4 15a1.7 1.7 0 0 0 .34 1.87l.06.06-2.83 2.83-.06-.06a1.7 1.7 0 0 0-1.87-.34 1.7 1.7 0 0 0-1.04 1.56V21h-4v-.08A1.7 1.7 0 0 0 8.96 19.36a1.7 1.7 0 0 0-1.87.34l-.06.06-2.83-2.83.06-.06A1.7 1.7 0 0 0 4.6 15a1.7 1.7 0 0 0-1.56-1.04H3v-4h.04A1.7 1.7 0 0 0 4.6 8.92a1.7 1.7 0 0 0-.34-1.87L4.2 7l2.83-2.83.06.06a1.7 1.7 0 0 0 1.87.34A1.7 1.7 0 0 0 10 3.04V3h4v.04a1.7 1.7 0 0 0 1.04 1.56 1.7 1.7 0 0 0 1.87-.34l.06-.06L19.8 7l-.06.05a1.7 1.7 0 0 0-.34 1.87A1.7 1.7 0 0 0 20.96 10H21v4h-.04A1.7 1.7 0 0 0 19.4 15Z" /></Icon> }
function ArtifactIcon() { return <Icon><path d="M6 3h9l4 4v14H6V3Z" /><path d="M15 3v5h5M9 13h7M9 17h5" /></Icon> }
function CloseIcon() { return <Icon><path d="m7 7 10 10M17 7 7 17" /></Icon> }
function ArrowLeftIcon() { return <Icon><path d="m10 6-6 6 6 6M4 12h16" /></Icon> }
function ArrowUpIcon() { return <Icon><path d="m6 10 6-6 6 6M12 4v16" /></Icon> }
function StopIcon() { return <Icon><rect x="7" y="7" width="10" height="10" rx="1.5" /></Icon> }
function CopyIcon() { return <Icon><rect x="8" y="8" width="11" height="11" rx="2" /><path d="M16 8V6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h2" /></Icon> }
function CheckIcon() { return <Icon><path d="m5 12 4 4L19 6" /></Icon> }
function BranchIcon() { return <Icon><path d="M6 4v5a3 3 0 0 0 3 3h9M14 8l4 4-4 4M6 20v-3a5 5 0 0 1 5-5" /></Icon> }
function FolderIcon() { return <Icon><path d="M3 7h7l2 2h9v10H3V7Z" /></Icon> }
function ShieldIcon() { return <Icon><path d="M12 3 5 6v5c0 4.4 2.8 8 7 10 4.2-2 7-5.6 7-10V6l-7-3Z" /><path d="m9 12 2 2 4-4" /></Icon> }
