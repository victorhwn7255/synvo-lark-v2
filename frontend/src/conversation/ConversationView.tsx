import { useEffect, useLayoutEffect, useRef, useState, type FormEvent, type KeyboardEvent, type ReactNode } from 'react'
import type { ConversationTurn } from '../api/conversations'
import { AssistantMarkdown } from '../components/AssistantMarkdown'
import type { ActiveRun, RunPhase } from './useConversation'
import {
  ArrowUpIcon,
  BranchIcon,
  CheckIcon,
  ConversationAvatar,
  CopyIcon,
  StopIcon,
  SynvoLogo,
} from '../workspace/visuals'

const COMPOSER_MIN_HEIGHT_PX = 44
const COMPOSER_MAX_HEIGHT_PX = 200
const CONVERSATION_BOTTOM_THRESHOLD_PX = 96
const SINGAPORE_TIME_ZONE = 'Asia/Singapore'

export function ConversationView({
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
  onBranch,
  composerControls,
  activityPresentation,
  composerDisabled = false,
  composerPlaceholder,
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
  onBranch?: () => void
  composerControls?: ReactNode
  activityPresentation?: ReactNode
  composerDisabled?: boolean
  composerPlaceholder?: string
}) {
  const composerRef = useRef<HTMLTextAreaElement>(null)
  const streamViewportRef = useRef<HTMLDivElement>(null)
  const lastUserTurnIdRef = useRef<string | null>(null)
  const stickToBottomRef = useRef(true)

  const lastUserTurnId = turns.findLast(({ role }) => role === 'USER')?.turnId ?? null
  const activityTurnId = activeRun?.assistantTurnId
    ?? turns.findLast(({ role }) => role === 'ASSISTANT')?.turnId
    ?? null

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
        className="workspace-conversation__stream workspace-themed-scrollbar"
        aria-live="polite"
        onScroll={updateScrollPreference}
      >
        {loading && <div className="workspace-history-state" role="status">Loading conversation…</div>}
        {!loading && error && <div className="workspace-history-state workspace-history-state--error" role="alert">{error}</div>}
        {!loading && turns.length === 0 && !activityPresentation && (
          <div className="workspace-empty-state">
            <SynvoLogo large />
            <h2>How can Synvo help?</h2>
            <p>Ask a general question, continue a conversation, or describe what you want to accomplish in natural language.</p>
          </div>
        )}
        {!loading && (turns.length > 0 || activityPresentation) && (
          <div className="workspace-turn-list">
            {turns.map((turn) => (
              <ConversationTurnView
                key={turn.turnId}
                turn={turn}
                userAvatarUrl={userAvatarUrl}
                active={activeRun?.assistantTurnId === turn.turnId}
                activity={activeRun?.assistantTurnId === turn.turnId ? activityLabel(activeRun.phase) : null}
                activityPresentation={activityTurnId === turn.turnId ? activityPresentation : null}
                onRetry={() => onRetry(turn.turnId)}
                onBranch={onBranch}
              />
            ))}
            {activityPresentation && activityTurnId === null && (
              <ConversationActivityPresentation>{activityPresentation}</ConversationActivityPresentation>
            )}
          </div>
        )}
      </div>

      <form className="workspace-composer" onSubmit={submit} aria-label="Message composer">
        {composerControls}
        <label className="sr-only" htmlFor="synvo-message">Message Synvo</label>
        <textarea
          ref={composerRef}
          id="synvo-message"
          value={composerValue}
          disabled={activeRun !== null || composerDisabled}
          placeholder={activeRun ? 'Synvo is responding…' : composerPlaceholder ?? 'Message Synvo…'}
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
            <button type="submit" disabled={composerDisabled || !composerValue.trim()} aria-label="Send message"><ArrowUpIcon /></button>
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
  activityPresentation,
  onRetry,
  onBranch,
}: {
  turn: ConversationTurn
  userAvatarUrl: string | null
  active: boolean
  activity: string | null
  activityPresentation: ReactNode
  onRetry: () => void
  onBranch?: () => void
}) {
  const assistantWaiting = turn.role === 'ASSISTANT'
    && !turn.content
    && !activityPresentation
    && (turn.status === 'PENDING' || active)
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
        ) : turn.content ? (
          <div className="workspace-turn__content">
            {turn.role === 'ASSISTANT' ? <AssistantMarkdown>{turn.content}</AssistantMarkdown> : turn.content}
          </div>
        ) : null}
        {activityPresentation}
        {active && turn.content && activity && !activityPresentation && (
          <div className="workspace-turn__activity" role="status">{activity}</div>
        )}
        {showAssistantActions && <AssistantTurnActions turn={turn} onBranch={onBranch} />}
        {turn.status === 'FAILED' && (
          <button className="workspace-retry-button" type="button" onClick={onRetry}>Retry</button>
        )}
      </div>
    </article>
  )
}

function ConversationActivityPresentation({ children }: { children: ReactNode }) {
  return (
    <article className="workspace-turn" data-role="assistant" data-status="pending" aria-label="Synvo activity">
      <div className="workspace-turn__identity" aria-hidden="true">
        <ConversationAvatar role="ASSISTANT" userAvatarUrl={null} />
      </div>
      <div className="workspace-turn__body">{children}</div>
    </article>
  )
}

function AssistantTurnActions({ turn, onBranch }: { turn: ConversationTurn; onBranch?: () => void }) {
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
        aria-label={onBranch ? 'Fork task from this result' : 'Branch in new chat — coming soon'}
        title={onBranch ? 'Fork task from this result' : 'Branch in new chat — coming soon'}
        disabled={!onBranch}
        onClick={onBranch}
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

function activityLabel(phase: RunPhase) {
  switch (phase) {
    case 'accepted': return 'Starting…'
    case 'thinking': return 'Preparing a response…'
    case 'streaming': return 'Responding…'
    case 'tool_running': return 'Running an approved operation…'
    case 'action_required': return 'Waiting for your approval in H5…'
    case 'reconnecting': return 'Reconnecting to the response…'
    case 'stopping': return 'Stopping…'
  }
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
