import { useRef, useState } from 'react'
import type { BotConnection } from '../api/lark'
import type { ConversationApi } from '../api/conversations'
import type { CodexApi } from '../api/codex'
import { CodexWorkspace } from '../codex/CodexWorkspace'
import { ConversationView } from '../conversation/ConversationView'
import { useConversation } from '../conversation/useConversation'
import { ArtifactPanel } from './ArtifactPanel'
import { DeleteConversationDialog } from './DeleteConversationDialog'
import { SettingsView } from './SettingsView'
import { WorkspaceSidebar } from './WorkspaceSidebar'
import { ArrowLeftIcon, ArtifactIcon, FolderIcon } from './visuals'

interface WorkspaceProps {
  botConnection: BotConnection
  busy: boolean
  userAvatarUrl?: string | null
  onSignOut: () => void
  api?: ConversationApi
  workspaceAgentApi?: CodexApi
}

const connectionNotices: Partial<Record<BotConnection, string>> = {
  connecting: 'The assistant channel is connecting.',
  reconnecting: 'The assistant channel is reconnecting automatically.',
  failed: 'The assistant channel needs attention. Your Lark authorization is still protected.',
  disabled: 'The assistant channel is disabled in this environment.',
}

type WorkspaceView = 'conversation' | 'settings'

export function Workspace({
  botConnection,
  busy,
  userAvatarUrl = null,
  onSignOut,
  api,
  workspaceAgentApi,
}: WorkspaceProps) {
  if (workspaceAgentApi) {
    return (
      <CodexWorkspace
        botConnection={botConnection}
        busy={busy}
        userAvatarUrl={userAvatarUrl}
        onSignOut={onSignOut}
        conversationApi={api}
        codexApi={workspaceAgentApi}
      />
    )
  }
  return (
    <LegacyWorkspace
      botConnection={botConnection}
      busy={busy}
      userAvatarUrl={userAvatarUrl}
      onSignOut={onSignOut}
      api={api}
    />
  )
}

function LegacyWorkspace({
  botConnection,
  busy,
  userAvatarUrl = null,
  onSignOut,
  api,
}: WorkspaceProps) {
  const [sidebarCollapsed, setSidebarCollapsed] = useState(initialSidebarCollapsed)
  const [view, setView] = useState<WorkspaceView>('conversation')
  const [artifactOpen, setArtifactOpen] = useState(false)
  const newConversationRef = useRef<HTMLButtonElement>(null)
  const deleteTriggerRef = useRef<HTMLButtonElement | null>(null)

  const conversationState = useConversation({
    api,
    onSelectedConversationDeleted: () => {
      setArtifactOpen(false)
      setView('conversation')
    },
    onDeleteAnimationFinished: () => {
      queueMicrotask(() => newConversationRef.current?.focus())
    },
  })
  const {
    selectedConversation,
    recentConversations,
    turns,
    composerValue,
    setComposerValue,
    loadingConversation,
    conversationError,
    activeRun,
    presentations,
    deleteTarget,
    deletingConversation,
    deleteConversationError,
    exitingConversationId,
  } = conversationState

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
    setView('conversation')
    setArtifactOpen(false)
    collapseSidebarForNarrowViewport(setSidebarCollapsed)
    await conversationState.openConversation(id)
  }

  const closeDeleteDialog = () => {
    if (conversationState.closeDeleteDialog()) {
      queueMicrotask(() => deleteTriggerRef.current?.focus())
    }
  }

  return (
    <main
      className="workspace-shell"
      data-sidebar-collapsed={sidebarCollapsed}
      aria-label="Synvo AI workspace"
    >
      <WorkspaceSidebar
        collapsed={sidebarCollapsed}
        settingsActive={view === 'settings'}
        selectedConversation={selectedConversation}
        recentConversations={recentConversations}
        conversationBusy={activeRun !== null}
        loadingConversation={loadingConversation}
        exitingConversationId={exitingConversationId}
        assistantReady={assistantReady}
        assistantAvailability={assistantAvailability}
        newConversationRef={newConversationRef}
        deleteTriggerRef={deleteTriggerRef}
        onToggle={() => setSidebarCollapsed((collapsed) => !collapsed)}
        onOpenConversation={(conversationId) => void openConversation(conversationId)}
        onRequestDelete={conversationState.requestDeleteConversation}
        onFinishDelete={conversationState.finishDeleteAnimation}
        onOpenSettings={() => {
          setView('settings')
          setArtifactOpen(false)
          collapseSidebarForNarrowViewport(setSidebarCollapsed)
        }}
      />

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
              onSubmit={(content) => void conversationState.submitMessage(content)}
              onStop={() => void conversationState.stopRun()}
              onRetry={conversationState.retryTurn}
            />
          )}

          {view === 'conversation' && artifactOpen && (
            <ArtifactPanel
              presentations={presentations}
              onClose={() => setArtifactOpen(false)}
            />
          )}
        </div>
      </section>
      {deleteTarget && (
        <DeleteConversationDialog
          conversation={deleteTarget}
          deleting={deletingConversation}
          error={deleteConversationError}
          onCancel={closeDeleteDialog}
          onConfirm={() => void conversationState.confirmDeleteConversation()}
        />
      )}
    </main>
  )
}

function initialSidebarCollapsed() {
  return typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 760px)').matches
}

function collapseSidebarForNarrowViewport(setCollapsed: (collapsed: boolean) => void) {
  if (typeof window.matchMedia === 'function' && window.matchMedia('(max-width: 760px)').matches) {
    setCollapsed(true)
  }
}
