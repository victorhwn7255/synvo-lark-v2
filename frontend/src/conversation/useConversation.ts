import { useCallback, useEffect, useRef, useState } from 'react'
import type { WorkflowPresentation } from '../api/presentations'
import {
  conversationApi,
  type ConversationApi,
  type ConversationStreamEvent,
  type ConversationSubscription,
  type ConversationSummary,
  type ConversationTurn,
  type ActionHandoff,
} from '../api/conversations'

export type RunPhase =
  | 'accepted'
  | 'thinking'
  | 'streaming'
  | 'tool_running'
  | 'action_required'
  | 'reconnecting'
  | 'stopping'

export interface ActiveRun {
  requestId: string
  runId: string | null
  assistantTurnId: string
  phase: RunPhase
}

interface RetryTarget {
  userTurn: ConversationTurn
  assistantTurn: ConversationTurn
}

interface UseConversationOptions {
  api?: ConversationApi
  onSelectedConversationDeleted?: () => void
  onDeleteAnimationFinished?: () => void
}

export interface TurnOptions {
  reasoningEffort?: string
  skillName?: string
}

export function useConversation({
  api = conversationApi,
  onSelectedConversationDeleted,
  onDeleteAnimationFinished,
}: UseConversationOptions = {}) {
  const [selectedConversation, setSelectedConversation] = useState<string | null>(null)
  const [recentConversations, setRecentConversations] = useState<ConversationSummary[]>([])
  const [turns, setTurns] = useState<ConversationTurn[]>([])
  const [composerValue, setComposerValue] = useState('')
  const [loadingConversation, setLoadingConversation] = useState(false)
  const [conversationError, setConversationError] = useState<string | null>(null)
  const [activeRun, setActiveRun] = useState<ActiveRun | null>(null)
  const [presentations, setPresentations] = useState<WorkflowPresentation[]>([])
  const [interactionHandoff, setInteractionHandoff] = useState<ActionHandoff | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<ConversationSummary | null>(null)
  const [deletingConversation, setDeletingConversation] = useState(false)
  const [deleteConversationError, setDeleteConversationError] = useState<string | null>(null)
  const [exitingConversationId, setExitingConversationId] = useState<string | null>(null)
  const streamRef = useRef<ConversationSubscription | null>(null)
  const applyStreamEventRef = useRef<(
    event: ConversationStreamEvent,
    assistantTurnId: string,
  ) => void>(() => {})
  const runInFlightRef = useRef(false)
  const lastTurnOptionsRef = useRef<TurnOptions>({})
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

  const openConversation = useCallback(async (id: string | null) => {
    if (activeRun) return
    streamRef.current?.close()
    streamRef.current = null
    setSelectedConversation(id)
    setPresentations([])
    setInteractionHandoff(null)
    setConversationError(null)
    if (id === null) {
      setTurns([])
      return
    }
    setLoadingConversation(true)
    try {
      const detail = await api.get(id)
      setTurns(detail.turns)
      const reconnect = detail.activeRun
      if (reconnect?.assistantTurnId) {
        runInFlightRef.current = true
        setActiveRun({
          requestId: reconnect.requestId,
          runId: reconnect.runId,
          assistantTurnId: reconnect.assistantTurnId,
          phase: 'reconnecting',
        })
        let subscription: ConversationSubscription | null = null
        subscription = api.subscribe(
          reconnect.runId,
          (event) => {
            applyStreamEventRef.current(event, reconnect.assistantTurnId!)
            if (event.type === 'completed' || event.type === 'failed') subscription?.close()
          },
          () => setActiveRun((current) => current
            ? { ...current, phase: 'reconnecting' }
            : current),
        )
        streamRef.current = subscription
      }
    } catch (error: unknown) {
      setConversationError(safeMessage(error))
      setTurns([])
    } finally {
      setLoadingConversation(false)
    }
  }, [activeRun, api])

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
    if (event.presentation) {
      setPresentations((current) => upsertPresentation(current, event.presentation!))
    }
    if (event.type === 'action_required' && event.action) {
      setInteractionHandoff(event.action)
      setActiveRun((current) => current ? { ...current, phase: 'action_required' } : current)
      return
    }
    if (event.type === 'content_delta' && event.delta) {
      setInteractionHandoff(null)
      setTurns((current) => current.map((turn) => turn.turnId === assistantTurnId
        ? { ...turn, content: turn.content + event.delta, status: 'STREAMING' }
        : turn))
      setActiveRun((current) => current ? { ...current, phase: 'streaming' } : current)
      return
    }
    if (event.type === 'content_reset') {
      setInteractionHandoff(null)
      setTurns((current) => current.map((turn) => turn.turnId === assistantTurnId
        ? { ...turn, content: '', status: 'PENDING' }
        : turn))
      setActiveRun((current) => current ? { ...current, phase: 'thinking' } : current)
      return
    }
    if (event.type === 'failed') {
      setInteractionHandoff(null)
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
      setInteractionHandoff(null)
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
  applyStreamEventRef.current = applyStreamEvent

  const submitMessage = useCallback(async (
    rawContent: string,
    retryTarget?: RetryTarget,
    options: TurnOptions = {},
  ) => {
    const content = rawContent.trim()
    if (!content || activeRun || runInFlightRef.current) return
    runInFlightRef.current = true
    if (!retryTarget) lastTurnOptionsRef.current = options
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
    setInteractionHandoff(null)
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
          ...(options.reasoningEffort ? { reasoningEffort: options.reasoningEffort } : {}),
          ...(options.skillName ? { skillName: options.skillName } : {}),
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

  const stopRun = useCallback(async () => {
    if (!activeRun?.runId) return
    setActiveRun({ ...activeRun, phase: 'stopping' })
    try {
      const csrfToken = await api.csrfToken()
      await api.stop(activeRun.runId, csrfToken)
    } catch (error: unknown) {
      setConversationError(safeMessage(error))
      setActiveRun((current) => current ? { ...current, phase: 'reconnecting' } : current)
    }
  }, [activeRun, api])

  const retryTurn = useCallback((failedTurnId: string) => {
    const failedIndex = turns.findIndex(({ turnId }) => turnId === failedTurnId)
    const priorUserTurn = turns.slice(0, failedIndex).reverse().find(({ role }) => role === 'USER')
    const failedTurn = turns[failedIndex]
    if (priorUserTurn && failedTurn?.role === 'ASSISTANT' && failedTurn.status === 'FAILED') {
      void submitMessage(priorUserTurn.content, {
        userTurn: priorUserTurn,
        assistantTurn: failedTurn,
      }, lastTurnOptionsRef.current)
    }
  }, [submitMessage, turns])

  const requestDeleteConversation = useCallback((conversation: ConversationSummary) => {
    setDeleteTarget(conversation)
    setDeleteConversationError(null)
  }, [])

  const closeDeleteDialog = useCallback(() => {
    if (deletingConversation) return false
    setDeleteTarget(null)
    setDeleteConversationError(null)
    return true
  }, [deletingConversation])

  const finishDeleteAnimation = useCallback((conversationId: string) => {
    if (deleteExitTimerRef.current !== null) {
      window.clearTimeout(deleteExitTimerRef.current)
      deleteExitTimerRef.current = null
    }
    setRecentConversations((current) => current.filter(
      (conversation) => conversation.conversationId !== conversationId,
    ))
    setExitingConversationId((current) => current === conversationId ? null : current)
    onDeleteAnimationFinished?.()
  }, [onDeleteAnimationFinished])

  const confirmDeleteConversation = useCallback(async () => {
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
        setInteractionHandoff(null)
        setConversationError(null)
        onSelectedConversationDeleted?.()
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
  }, [
    activeRun,
    api,
    deleteTarget,
    deletingConversation,
    finishDeleteAnimation,
    onSelectedConversationDeleted,
    selectedConversation,
  ])

  return {
    selectedConversation,
    recentConversations,
    turns,
    composerValue,
    setComposerValue,
    loadingConversation,
    conversationError,
    activeRun,
    presentations,
    interactionHandoff,
    deleteTarget,
    deletingConversation,
    deleteConversationError,
    exitingConversationId,
    openConversation,
    submitMessage,
    stopRun,
    retryTurn,
    requestDeleteConversation,
    closeDeleteDialog,
    confirmDeleteConversation,
    finishDeleteAnimation,
  }
}

function createRequestId() {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID()
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`
}

function safeMessage(error: unknown) {
  return error instanceof Error ? error.message : 'Synvo could not complete that request. Please try again.'
}

function upsertPresentation(
  current: WorkflowPresentation[],
  next: WorkflowPresentation,
) {
  const index = current.findIndex(({ id }) => id === next.id)
  if (index < 0) return [...current, next]
  return current.map((presentation, position) => position === index ? next : presentation)
}
