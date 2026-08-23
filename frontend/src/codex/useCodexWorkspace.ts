import { useCallback, useEffect, useRef, useState } from 'react'
import {
  type CodexActivity,
  type CodexApi,
  type CodexGoal,
  type CodexGoalCommand,
  type CodexInteraction,
  type CodexInteractionDecision,
  type CodexInventory,
  type CodexOperation,
  type CodexReviewKind,
  type CodexRunMode,
  type CodexStatus,
  type CodexSubscription,
  type CodexTask,
  type CodexTaskDetail,
  type CodexWorkspace,
} from '../api/codex'

interface UseCodexWorkspaceOptions {
  api: CodexApi
}

export function useCodexWorkspace({ api }: UseCodexWorkspaceOptions) {
  const [status, setStatus] = useState<CodexStatus | null>(null)
  const [workspaces, setWorkspaces] = useState<CodexWorkspace[]>([])
  const [tasks, setTasks] = useState<CodexTask[]>([])
  const [archived, setArchived] = useState(false)
  const [search, setSearch] = useState('')
  const [selectedTaskId, setSelectedTaskId] = useState<string | null>(null)
  const [taskDetail, setTaskDetail] = useState<CodexTaskDetail | null>(null)
  const [activity, setActivity] = useState<CodexActivity[]>([])
  const [inventory, setInventory] = useState<CodexInventory>({ skills: [], mcpServers: [] })
  const [goal, setGoalState] = useState<CodexGoal | null>(null)
  const [interaction, setInteraction] = useState<CodexInteraction | null>(null)
  const [loading, setLoading] = useState(true)
  const [loadingTask, setLoadingTask] = useState(false)
  const [submitting, setSubmitting] = useState<string | null>(null)
  const [reconnecting, setReconnecting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const subscriptionRef = useRef<CodexSubscription | null>(null)
  const operationIdRef = useRef<string | null>(null)
  const selectedTaskIdRef = useRef<string | null>(null)
  const csrfTokenRef = useRef<string | null>(null)
  const mutationInFlightRef = useRef(false)
  const initialLinkRef = useRef(readDeepLink())

  useEffect(() => {
    selectedTaskIdRef.current = selectedTaskId
  }, [selectedTaskId])

  const csrfToken = useCallback(async () => {
    if (csrfTokenRef.current) return csrfTokenRef.current
    const token = await api.csrfToken()
    csrfTokenRef.current = token
    return token
  }, [api])

  const loadInteraction = useCallback(async (interactionId: string, signal?: AbortSignal) => {
    try {
      const detail = await api.interaction(interactionId, signal)
      if (!signal?.aborted) {
        setInteraction(detail)
      }
    } catch (failure: unknown) {
      if (!signal?.aborted) setError(safeMessage(failure))
    }
  }, [api])

  const attachOperation = useCallback((operation: CodexOperation | null) => {
    const sameOperation = operationIdRef.current === operation?.operationId
    const terminalReplay = operation !== null && isTerminalOperation(operation.status)
    if (sameOperation && !terminalReplay) return
    subscriptionRef.current?.close()
    subscriptionRef.current = null
    operationIdRef.current = operation?.operationId ?? null
    if (!sameOperation) setActivity([])
    setReconnecting(false)
    if (!operation) return
    subscriptionRef.current = api.subscribe(
      operation.operationId,
      (event) => {
        setReconnecting(false)
        if (event.kind === 'interaction_required') {
          void loadInteraction(event.interactionId)
          return
        }
        setActivity((current) => upsertActivity(current, event))
        if (event.terminalStatus !== null) {
          subscriptionRef.current?.close()
          subscriptionRef.current = null
          setReconnecting(false)
        }
      },
      () => setReconnecting(true),
    )
  }, [api, loadInteraction])

  const refreshTasks = useCallback(async (signal?: AbortSignal) => {
    try {
      const next = await api.tasks(archived, search, signal)
      if (!signal?.aborted) setTasks(next)
    } catch (failure: unknown) {
      if (!signal?.aborted) setError(safeMessage(failure))
    }
  }, [api, archived, search])

  const refreshTaskAuxiliary = useCallback(async (taskId: string, signal?: AbortSignal) => {
    const [nextInventory, nextGoal] = await Promise.allSettled([
      api.inventory(taskId, signal),
      api.goal(taskId, signal),
    ])
    if (signal?.aborted || selectedTaskIdRef.current !== taskId) return
    setInventory(nextInventory.status === 'fulfilled'
      ? nextInventory.value
      : { skills: [], mcpServers: [] })
    setGoalState(nextGoal.status === 'fulfilled' ? nextGoal.value : null)
  }, [api])

  const openTask = useCallback(async (
    taskId: string,
    requestedInteractionId?: string | null,
    signal?: AbortSignal,
  ) => {
    selectedTaskIdRef.current = taskId
    setSelectedTaskId(taskId)
    setLoadingTask(true)
    setError(null)
    setInteraction(null)
    try {
      const detail = await api.task(taskId, signal)
      if (signal?.aborted || selectedTaskIdRef.current !== taskId) return
      setTaskDetail(detail)
      attachOperation(detail.activeOperation ?? detail.latestOperation)
      const interactionId = requestedInteractionId
        ?? detail.pendingInteractions[0]?.interactionId
        ?? null
      updateDeepLink(taskId, interactionId)
      if (interactionId) await loadInteraction(interactionId, signal)
      void refreshTaskAuxiliary(taskId, signal)
      return detail
    } catch (failure: unknown) {
      if (!signal?.aborted) {
        setTaskDetail(null)
        setError(safeMessage(failure))
      }
      return null
    } finally {
      if (!signal?.aborted) setLoadingTask(false)
    }
  }, [api, attachOperation, loadInteraction, refreshTaskAuxiliary])

  const synchronizeSelectedTask = useCallback(async () => {
    const taskId = selectedTaskIdRef.current
    if (!taskId) return null
    try {
      const detail = await api.task(taskId)
      if (selectedTaskIdRef.current !== taskId) return null
      setTaskDetail(detail)
      setTasks((current) => upsertTask(current, detail.task))
      attachOperation(detail.activeOperation ?? detail.latestOperation)
      const pending = detail.pendingInteractions[0]
      if (pending) {
        await loadInteraction(pending.interactionId)
      } else {
        setInteraction(null)
      }
      return detail
    } catch (failure: unknown) {
      setError(safeMessage(failure))
      return null
    }
  }, [api, attachOperation, loadInteraction])

  const refreshSelectedTask = useCallback(async () => {
    const detail = await synchronizeSelectedTask()
    if (detail) await refreshTaskAuxiliary(detail.task.taskId)
    return detail
  }, [refreshTaskAuxiliary, synchronizeSelectedTask])

  useEffect(() => {
    const controller = new AbortController()
    const initialize = async () => {
      setLoading(true)
      setError(null)
      try {
        const [nextStatus, nextWorkspaces, nextTasks] = await Promise.all([
          api.status(controller.signal),
          api.workspaces(controller.signal),
          api.tasks(false, undefined, controller.signal),
        ])
        if (controller.signal.aborted) return
        setStatus(nextStatus)
        setWorkspaces(nextWorkspaces)
        setTasks(nextTasks)
        const link = initialLinkRef.current
        if (link.taskId) await openTask(link.taskId, link.interactionId, controller.signal)
      } catch (failure: unknown) {
        if (!controller.signal.aborted) setError(safeMessage(failure))
      } finally {
        if (!controller.signal.aborted) setLoading(false)
      }
    }
    void initialize()
    return () => {
      controller.abort()
      subscriptionRef.current?.close()
    }
  }, [api, openTask])

  useEffect(() => {
    if (loading) return
    const controller = new AbortController()
    const timeout = window.setTimeout(() => void refreshTasks(controller.signal), 180)
    return () => {
      window.clearTimeout(timeout)
      controller.abort()
    }
  }, [archived, loading, refreshTasks, search])

  const clearSelection = useCallback(() => {
    subscriptionRef.current?.close()
    subscriptionRef.current = null
    operationIdRef.current = null
    selectedTaskIdRef.current = null
    setSelectedTaskId(null)
    setTaskDetail(null)
    setActivity([])
    setInventory({ skills: [], mcpServers: [] })
    setGoalState(null)
    setInteraction(null)
    setReconnecting(false)
    updateDeepLink(null, null)
  }, [])

  const mutate = useCallback(async <T,>(name: string, action: (token: string) => Promise<T>) => {
    if (mutationInFlightRef.current) throw new Error('Another Codex action is already being submitted.')
    mutationInFlightRef.current = true
    setSubmitting(name)
    setError(null)
    try {
      return await action(await csrfToken())
    } catch (failure: unknown) {
      setError(safeMessage(failure))
      throw failure
    } finally {
      mutationInFlightRef.current = false
      setSubmitting(null)
    }
  }, [csrfToken])

  const createTask = useCallback(async (
    workspaceId: string,
    mode: CodexRunMode,
    title?: string,
  ) => {
    const created = await mutate('create-task', (token) => api.createTask(
      { workspaceId, mode, ...(title?.trim() ? { title: title.trim() } : {}) },
      token,
    ))
    setTasks((current) => upsertTask(current, created))
    await openTask(created.taskId)
    return created
  }, [api, mutate, openTask])

  const updateTaskById = useCallback(async (
    name: string,
    taskId: string,
    action: (taskId: string, token: string) => Promise<CodexTask>,
  ) => {
    const updated = await mutate(name, (token) => action(taskId, token))
    setTasks((current) => upsertTask(current, updated))
    setTaskDetail((current) => current?.task.taskId === taskId
      ? { ...current, task: updated }
      : current)
    return updated
  }, [mutate])

  const renameTaskById = useCallback((taskId: string, title: string) => updateTaskById(
    'rename-task', taskId, (targetTaskId, token) => api.renameTask(targetTaskId, title, token),
  ), [api, updateTaskById])

  const renameTask = useCallback((title: string) => {
    const taskId = selectedTaskIdRef.current
    return taskId ? renameTaskById(taskId, title) : Promise.resolve(null)
  }, [renameTaskById])

  const pinTask = useCallback((enabled: boolean) => {
    const taskId = selectedTaskIdRef.current
    return taskId ? updateTaskById(
      'pin-task', taskId, (targetTaskId, token) => api.pinTask(targetTaskId, enabled, token),
    ) : Promise.resolve(null)
  }, [api, updateTaskById])

  const changeMode = useCallback((mode: CodexRunMode) => {
    const taskId = selectedTaskIdRef.current
    return taskId ? updateTaskById(
      'change-mode', taskId, (targetTaskId, token) => api.changeMode(targetTaskId, mode, token),
    ) : Promise.resolve(null)
  }, [api, updateTaskById])

  const archiveTaskById = useCallback(async (taskId: string, enabled: boolean) => {
    const selected = selectedTaskIdRef.current === taskId
    const updated = await updateTaskById(
      enabled ? 'archive-task' : 'unarchive-task',
      taskId,
      (targetTaskId, token) => api.archiveTask(targetTaskId, enabled, token),
    )
    if (updated) {
      setTasks((current) => current.filter((task) => task.taskId !== taskId))
      if (selected) clearSelection()
      await refreshTasks()
    }
    return updated
  }, [api, clearSelection, refreshTasks, updateTaskById])

  const archiveTask = useCallback((enabled: boolean) => {
    const taskId = selectedTaskIdRef.current
    return taskId ? archiveTaskById(taskId, enabled) : Promise.resolve(null)
  }, [archiveTaskById])

  const forkTask = useCallback(async (title: string) => {
    const taskId = selectedTaskIdRef.current
    if (!taskId) return null
    const forked = await mutate('fork-task', (token) => api.forkTask(taskId, title, token))
    setTasks((current) => upsertTask(current, forked))
    await openTask(forked.taskId)
    return forked
  }, [api, mutate, openTask])

  const deleteTaskById = useCallback(async (taskId: string) => {
    const selected = selectedTaskIdRef.current === taskId
    await mutate('delete-task', (token) => api.deleteTask(taskId, token))
    setTasks((current) => current.filter((task) => task.taskId !== taskId))
    if (selected) clearSelection()
  }, [api, clearSelection, mutate])

  const deleteTask = useCallback(() => {
    const taskId = selectedTaskIdRef.current
    return taskId ? deleteTaskById(taskId) : Promise.resolve()
  }, [deleteTaskById])

  const decideInteraction = useCallback(async (
    decision: CodexInteractionDecision,
    formValues: Record<string, string>,
  ) => {
    const interactionId = interaction?.interactionId
    if (!interactionId) return
    await mutate('interaction-decision', (token) => api.decideInteraction(
      interactionId, decision, formValues, token,
    ))
    setInteraction(null)
    updateDeepLink(selectedTaskIdRef.current, null)
    await refreshSelectedTask()
  }, [api, interaction?.interactionId, mutate, refreshSelectedTask])

  const steer = useCallback(async (content: string) => {
    const operationId = taskDetail?.activeOperation?.operationId
    if (!operationId) return
    await mutate('steer', (token) => api.steer(operationId, content, token))
  }, [api, mutate, taskDetail?.activeOperation?.operationId])

  const stopOperation = useCallback(async () => {
    const operationId = taskDetail?.activeOperation?.operationId
    if (!operationId) return
    await mutate('stop-operation', (token) => api.stopOperation(operationId, token))
  }, [api, mutate, taskDetail?.activeOperation?.operationId])

  const updateGoal = useCallback(async (objective: string, command: CodexGoalCommand) => {
    const taskId = selectedTaskIdRef.current
    if (!taskId) return
    await mutate('set-goal', (token) => api.setGoal(taskId, objective, command, token))
    const nextGoal = await api.goal(taskId).catch(() => null)
    if (selectedTaskIdRef.current === taskId) {
      setGoalState((current) => nextGoal ?? {
        objective,
        status: command === 'RESUME' ? 'active' : command === 'PAUSE' ? 'paused' : current?.status ?? 'active',
        tokensUsed: current?.tokensUsed ?? 0,
        timeUsedSeconds: current?.timeUsedSeconds ?? 0,
      })
    }
  }, [api, mutate])

  const clearGoal = useCallback(async () => {
    const taskId = selectedTaskIdRef.current
    if (!taskId) return
    await mutate('clear-goal', (token) => api.clearGoal(taskId, token))
    setGoalState(null)
  }, [api, mutate])

  const startReview = useCallback(async (kind: CodexReviewKind, value: string | null) => {
    const taskId = selectedTaskIdRef.current
    if (!taskId) return
    const operation = await mutate('start-review', (token) => api.review(taskId, kind, value, token))
    setTaskDetail((current) => current
      ? { ...current, activeOperation: operation, latestOperation: operation }
      : current)
    attachOperation(operation)
  }, [api, attachOperation, mutate])

  return {
    status,
    workspaces,
    tasks,
    archived,
    setArchived,
    search,
    setSearch,
    selectedTaskId,
    taskDetail,
    activity,
    inventory,
    goal,
    interaction,
    loading,
    loadingTask,
    submitting,
    reconnecting,
    error,
    setError,
    openTask,
    clearSelection,
    synchronizeSelectedTask,
    refreshSelectedTask,
    createTask,
    renameTask,
    renameTaskById,
    pinTask,
    archiveTask,
    archiveTaskById,
    changeMode,
    forkTask,
    deleteTask,
    deleteTaskById,
    decideInteraction,
    steer,
    stopOperation,
    updateGoal,
    clearGoal,
    startReview,
  }
}

function upsertTask(tasks: CodexTask[], task: CodexTask) {
  return [task, ...tasks.filter(({ taskId }) => taskId !== task.taskId)]
    .sort((left, right) => Number(right.pinned) - Number(left.pinned)
      || right.updatedAt.localeCompare(left.updatedAt))
}

function upsertActivity(activity: CodexActivity[], next: CodexActivity) {
  return [...activity.filter(({ sequence }) => sequence !== next.sequence), next]
    .sort((left, right) => left.sequence - right.sequence)
}

function readDeepLink() {
  if (typeof window === 'undefined') return { taskId: null, interactionId: null }
  const query = new URLSearchParams(window.location.search)
  return {
    taskId: query.get('codexTask'),
    interactionId: query.get('codexInteraction'),
  }
}

function updateDeepLink(taskId: string | null, interactionId: string | null) {
  if (typeof window === 'undefined' || typeof window.history?.replaceState !== 'function') return
  const url = new URL(window.location.href)
  if (taskId) url.searchParams.set('codexTask', taskId)
  else url.searchParams.delete('codexTask')
  if (interactionId) url.searchParams.set('codexInteraction', interactionId)
  else url.searchParams.delete('codexInteraction')
  window.history.replaceState(null, '', `${url.pathname}${url.search}${url.hash}`)
}

function safeMessage(error: unknown) {
  return error instanceof Error ? error.message : 'The Codex task action could not be completed.'
}

function isTerminalOperation(status: CodexOperation['status']) {
  return status === 'COMPLETED' || status === 'FAILED' || status === 'STOPPED'
}
