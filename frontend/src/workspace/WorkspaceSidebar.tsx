import type { RefObject } from 'react'
import type { ConversationSummary } from '../api/conversations'
import {
  ConversationIcon,
  MeetingIcon,
  PanelLeftIcon,
  PlusIcon,
  ResearchIcon,
  SettingsIcon,
  SynvoLogo,
  TrashIcon,
} from './visuals'

export function WorkspaceSidebar({
  collapsed,
  settingsActive,
  selectedConversation,
  recentConversations,
  conversationBusy,
  loadingConversation,
  exitingConversationId,
  assistantReady,
  assistantAvailability,
  newConversationRef,
  deleteTriggerRef,
  onToggle,
  onOpenConversation,
  onRequestDelete,
  onFinishDelete,
  onOpenSettings,
}: {
  collapsed: boolean
  settingsActive: boolean
  selectedConversation: string | null
  recentConversations: ConversationSummary[]
  conversationBusy: boolean
  loadingConversation: boolean
  exitingConversationId: string | null
  assistantReady: boolean
  assistantAvailability: string
  newConversationRef: RefObject<HTMLButtonElement | null>
  deleteTriggerRef: RefObject<HTMLButtonElement | null>
  onToggle: () => void
  onOpenConversation: (conversationId: string | null) => void
  onRequestDelete: (conversation: ConversationSummary) => void
  onFinishDelete: (conversationId: string) => void
  onOpenSettings: () => void
}) {
  return (
    <aside className="workspace-sidebar" aria-label="Synvo navigation">
      <div className="workspace-sidebar__brand">
        <SynvoLogo />
        <strong className="workspace-sidebar__label">Synvo</strong>
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
        ref={newConversationRef}
        className="workspace-new-button"
        type="button"
        aria-label="New conversation"
        disabled={conversationBusy}
        onClick={() => onOpenConversation(null)}
      >
        <PlusIcon />
        <span className="workspace-sidebar__label">New conversation</span>
      </button>

      <div className="workspace-sidebar__scroll">
        <nav
          className="workspace-sidebar__section workspace-workflows"
          aria-label={collapsed ? 'Workflows' : undefined}
          aria-labelledby={collapsed ? undefined : 'workflow-navigation-title'}
        >
          {!collapsed && (
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
          aria-label={collapsed ? 'Recent' : undefined}
          aria-labelledby={collapsed ? undefined : 'recent-navigation-title'}
        >
          {!collapsed && (
            <h2 id="recent-navigation-title" className="workspace-sidebar__section-title">Recent</h2>
          )}
          {recentConversations.map((conversation) => (
            <div
              key={conversation.conversationId}
              className="workspace-recent-item"
              data-active={!settingsActive && selectedConversation === conversation.conversationId}
              data-deleting={exitingConversationId === conversation.conversationId}
              onAnimationEnd={() => {
                if (exitingConversationId === conversation.conversationId) {
                  onFinishDelete(conversation.conversationId)
                }
              }}
            >
              <button
                className="workspace-nav-item workspace-recent-item__open"
                type="button"
                aria-label={conversation.title}
                aria-current={!settingsActive && selectedConversation === conversation.conversationId ? 'page' : undefined}
                disabled={conversationBusy || exitingConversationId === conversation.conversationId}
                onClick={() => onOpenConversation(conversation.conversationId)}
              >
                <ConversationIcon />
                <span className="workspace-sidebar__label">{conversation.title}</span>
              </button>
              {!collapsed && (
                <button
                  className="workspace-recent-item__delete"
                  type="button"
                  aria-label={`Delete chat “${conversation.title}”`}
                  title="Delete chat"
                  disabled={conversationBusy || loadingConversation || exitingConversationId === conversation.conversationId}
                  onClick={(event) => {
                    deleteTriggerRef.current = event.currentTarget
                    onRequestDelete(conversation)
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
          data-active={settingsActive}
          type="button"
          aria-label="Settings"
          aria-current={settingsActive ? 'page' : undefined}
          onClick={onOpenSettings}
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
  )
}
