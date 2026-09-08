import { act, cleanup, fireEvent, render, renderHook, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  CodexApi,
  CodexGoal,
  CodexInventory,
  CodexInteraction,
  CodexOperationEvent,
  CodexTask,
  CodexTaskDetail,
} from '../api/codex'
import type {
  ConversationApi,
  ConversationRun,
  ConversationStreamEvent,
} from '../api/conversations'
import { Workspace } from '../workspace/Workspace'
import { startAppearance } from '../appearance'
import { useCodexWorkspace } from './useCodexWorkspace'

describe('CodexWorkspace', () => {
  it('insets the reload status below the header without stretching its card', async () => {
    const codex = codexFlow(), conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)
    const loading = screen.getByText('Preparing Synvo AI Assistant…')
    expect(loading).toHaveAttribute('role', 'status')
    expect(loading.parentElement).toHaveClass('codex-startup-state')
    await waitFor(() => expect(screen.queryByText('Preparing Synvo AI Assistant…')).not.toBeInTheDocument())
  })
  afterEach(() => {
    cleanup()
    window.history.replaceState(null, '', '/')
    vi.restoreAllMocks()
  })

  it.each(['APPROVE_ONCE', 'DECLINE', 'CANCEL'] as const)('stores only a confirmed, minimal %s receipt', async (decision) => {
    const codex = codexFlow()
    vi.mocked(codex.api.decideInteraction).mockResolvedValue({ ...pendingInteraction(), status: 'DECIDED', decision })
    const { result } = renderHook(() => useCodexWorkspace({ api: codex.api }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    await act(async () => { await result.current.openTask('task-1', 'interaction-1') })
    await act(async () => { await result.current.decideInteraction(decision, { sample: 'never retain form content' }) })
    expect(result.current.decisionReceipts).toEqual([{ taskId: 'task-1', operationId: 'operation-1', interactionId: 'interaction-1', decision }])
    expect(result.current.interaction).toBeNull()
    expect(result.current.decisionAnnouncement).toBe(decision === 'APPROVE_ONCE' ? 'Approved once' : decision === 'DECLINE' ? 'Declined' : 'Cancellation requested')
    await act(async () => { await result.current.deleteTaskById('task-1') })
    expect(result.current.decisionReceipts).toEqual([])
  })

  it('bounds each task receipt history, deduplicates identities, and clears it on unmount', async () => {
    const codex = codexFlow()
    const { result, unmount } = renderHook(() => useCodexWorkspace({ api: codex.api }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    for (let index = 0; index < 22; index += 1) {
      const interaction = { ...pendingInteraction(), interactionId: `decision-${index}` }
      vi.mocked(codex.api.interaction).mockResolvedValue(interaction)
      vi.mocked(codex.api.decideInteraction).mockResolvedValue({ ...interaction, status: 'DECIDED', decision: 'APPROVE_ONCE' })
      await act(async () => { await result.current.openTask('task-1', interaction.interactionId) })
      await act(async () => { await result.current.decideInteraction('APPROVE_ONCE', {}) })
    }
    expect(result.current.decisionReceipts).toHaveLength(20)
    expect(result.current.decisionReceipts[0].interactionId).toBe('decision-2')
    await act(async () => { await result.current.openTask('task-1', 'decision-21') })
    await act(async () => { await result.current.decideInteraction('APPROVE_ONCE', {}) })
    expect(result.current.decisionReceipts).toHaveLength(20)
    unmount()
    const fresh = renderHook(() => useCodexWorkspace({ api: codex.api }))
    expect(fresh.result.current.decisionReceipts).toEqual([])
  })

  it('rechecks decision ownership after CSRF acquisition before sending', async () => {
    const codex = codexFlow()
    const csrf = deferred<string>()
    vi.mocked(codex.api.csrfToken).mockImplementation(() => csrf.promise)
    const { result } = renderHook(() => useCodexWorkspace({ api: codex.api }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    await act(async () => { await result.current.openTask('task-1', 'interaction-1') })
    let pending!: Promise<void>
    act(() => { pending = result.current.decideInteraction('APPROVE_ONCE', {}).catch(() => {}) })
    act(() => result.current.clearSelection())
    await act(async () => { csrf.resolve('csrf-token'); await pending })
    expect(codex.api.decideInteraction).not.toHaveBeenCalled()
    expect(result.current.decisionReceipts).toEqual([])
    expect(result.current.error).toBeNull()
  })

  it.each(['rejected', 'pending', 'expired', 'unknown-status', 'wrong-owner', 'missing-decision'] as const)('keeps recovery without a receipt for a %s decision response', async (failure) => {
    const codex = codexFlow()
    const response = { ...pendingInteraction(), status: 'DECIDED', decision: 'APPROVE_ONCE' as const }
    if (failure === 'rejected') vi.mocked(codex.api.decideInteraction).mockRejectedValue(new Error('Sample request failed'))
    else vi.mocked(codex.api.decideInteraction).mockResolvedValue({ ...response,
      ...(failure === 'pending' ? { status: 'PENDING' } : {}),
      ...(failure === 'expired' ? { status: 'EXPIRED' } : {}),
      ...(failure === 'unknown-status' ? { status: 'RESOLVED' } : {}),
      ...(failure === 'wrong-owner' ? { taskId: 'another-task' } : {}),
      ...(failure === 'missing-decision' ? { decision: null } : {}),
    })
    const { result } = renderHook(() => useCodexWorkspace({ api: codex.api }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    await act(async () => { await result.current.openTask('task-1', 'interaction-1') })
    await act(async () => { await result.current.decideInteraction('APPROVE_ONCE', {}).catch(() => {}) })
    expect(result.current.interaction?.interactionId).toBe('interaction-1')
    expect(result.current.decisionReceipts).toEqual([])
    expect(result.current.error).toBeTruthy()
  })

  it.each(['selection', 'replacement', 'replaced-error'] as const)('does not dismiss or announce over a %s during a delayed decision', async (race) => {
    const pending = deferred<CodexInteraction>()
    const codex = codexFlow()
    vi.mocked(codex.api.decideInteraction).mockImplementation(() => pending.promise)
    const { result } = renderHook(() => useCodexWorkspace({ api: codex.api }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    await act(async () => { await result.current.openTask('task-1', 'interaction-1') })
    let submitted!: Promise<void>
    act(() => { submitted = result.current.decideInteraction('APPROVE_ONCE', {}).catch(() => {}) })
    await waitFor(() => expect(codex.api.decideInteraction).toHaveBeenCalledOnce())
    await act(async () => { await result.current.decideInteraction('APPROVE_ONCE', {}).catch(() => {}) })
    expect(codex.api.decideInteraction).toHaveBeenCalledOnce()
    if (race === 'selection') act(() => result.current.clearSelection())
    else {
      vi.mocked(codex.api.interaction).mockResolvedValue({ ...pendingInteraction(), interactionId: 'interaction-2' })
      await act(async () => { await result.current.openTask('task-1', 'interaction-2') })
    }
    await act(async () => {
      if (race === 'replaced-error') pending.reject(new Error('Old request failed'))
      else pending.resolve({ ...pendingInteraction(), status: 'DECIDED', decision: 'DECLINE' })
      await submitted
    })
    expect(result.current.interaction?.interactionId ?? null).toBe(race === 'selection' ? null : 'interaction-2')
    expect(result.current.decisionAnnouncement).toBe('')
    expect(result.current.error).toBeNull()
    expect(result.current.decisionReceipts).toHaveLength(race === 'replaced-error' ? 0 : 1)
    if (race !== 'replaced-error') expect(result.current.decisionReceipts[0].decision).toBe('DECLINE')
  })

  it('opens the current pending interaction when a reload link names an already decided interaction', async () => {
    const current = { ...pendingInteraction(), interactionId: 'next-decision' }
    const codex = codexFlow({ detail: detail({ pendingInteractions: [current] }), interaction: current })
    const { result } = renderHook(() => useCodexWorkspace({ api: codex.api }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    await act(async () => { await result.current.openTask('task-1', 'old-decision') })
    expect(codex.api.interaction).toHaveBeenCalledExactlyOnceWith('next-decision', undefined)
    expect(result.current.interaction?.interactionId).toBe('next-decision')
    expect(new URLSearchParams(location.search).get('codexInteraction')).toBe('next-decision')
  })

  it('preserves a pending decision when task metadata omits it and rechecks its authoritative record', async () => {
    const codex = codexFlow()
    const { result } = renderHook(() => useCodexWorkspace({ api: codex.api }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    await act(async () => { await result.current.openTask('task-1', 'interaction-1') })
    await act(async () => { await result.current.synchronizeSelectedTask() })
    expect(result.current.interaction?.interactionId).toBe('interaction-1')
    expect(result.current.decisionReceipts).toEqual([])
    vi.mocked(codex.api.interaction).mockResolvedValue({ ...pendingInteraction(), status: 'DECIDED', decision: 'DECLINE' })
    await act(async () => { await result.current.synchronizeSelectedTask() })
    expect(result.current.interaction).toBeNull()
    expect(result.current.decisionReceipts).toEqual([])
  })

  it('uses authoritative terminal metadata rather than a projected running timestamp', async () => {
    const operation = activeOperation()
    const codex = codexFlow({ detail: detail({ activeOperation: operation, latestOperation: operation }) })
    const { result } = renderHook(() => useCodexWorkspace({ api: codex.api }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    await act(async () => { await result.current.openTask('task-1') })
    act(() => codex.emit({ kind: 'activity', sequence: 1, type: 'TURN_COMPLETED', terminalStatus: 'COMPLETED', label: 'Completed', text: null, truncated: false }))
    await act(async () => { await result.current.synchronizeSelectedTask() })
    expect(result.current.taskDetail?.latestOperation?.status).toBe('COMPLETED')
    expect(result.current.terminalTiming).toBeNull()
    vi.mocked(codex.api.task).mockResolvedValue(detail({ latestOperation: { ...operation, status: 'COMPLETED', updatedAt: '2026-09-05T12:00:10Z' } }))
    await act(async () => { await result.current.synchronizeSelectedTask() })
    expect(result.current.terminalTiming).toEqual({ operationId: 'operation-1', updatedAt: '2026-09-05T12:00:10Z' })
  })

  it('steers from the composer without opening details and retains terminal drafts', async () => {
    const operation = activeOperation()
    const codex = codexFlow({ detail: detail({ activeOperation: operation, latestOperation: operation }) })
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)
    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    const composer = await screen.findByRole('textbox', { name: 'Message Synvo' })
    expect(screen.queryByRole('complementary', { name: 'Codex task details' })).not.toBeInTheDocument()
    fireEvent.change(composer, { target: { value: 'Include the regional breakdown' } })
    fireEvent.click(screen.getByRole('button', { name: 'Update instructions' }))
    await waitFor(() => expect(codex.api.steer).toHaveBeenCalledWith('operation-1', 'Include the regional breakdown', 'csrf-token'))
    await waitFor(() => expect(composer).toHaveValue(''))
    fireEvent.change(composer, { target: { value: 'Retain this unsent update' } })
    act(() => codex.emit({ kind: 'activity', sequence: 8, type: 'TURN_COMPLETED', label: 'Completed', text: null, truncated: false, terminalStatus: 'COMPLETED' }))
    expect(await screen.findByRole('button', { name: 'Use as new message' })).toBeInTheDocument()
    fireEvent.keyDown(composer, { key: 'Enter' })
    expect(conversation.api.submit).not.toHaveBeenCalled()
    expect(composer).toHaveValue('Retain this unsent update')
    fireEvent.click(screen.getByRole('button', { name: 'Use as new message' }))
    expect(composer).toHaveValue('Retain this unsent update')
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))
    await waitFor(() => expect(conversation.api.submit).toHaveBeenCalledOnce())
  })

  it.each(['terminal', 'selection', 'roundtrip', 'decision'] as const)('rejects steering when %s changes during CSRF acquisition', async (race) => {
    const operation = activeOperation()
    const codex = codexFlow({ detail: detail({ activeOperation: operation, latestOperation: operation }) })
    const csrf = deferred<string>()
    vi.mocked(codex.api.csrfToken).mockImplementation(() => csrf.promise)
    const { result } = renderHook(() => useCodexWorkspace({ api: codex.api }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    await act(async () => { await result.current.openTask('task-1') })
    let steering!: Promise<unknown>
    act(() => { steering = result.current.steer('Keep the original source', 'operation-1').catch((error: Error) => error.message) })
    if (race === 'terminal') act(() => codex.emit({ kind: 'activity', sequence: 9, type: 'TURN_COMPLETED', label: 'Completed', text: null, truncated: false, terminalStatus: 'COMPLETED' }))
    if (race === 'selection') act(() => result.current.clearSelection())
    if (race === 'roundtrip') { act(() => result.current.clearSelection()); await act(async () => { await result.current.openTask('task-1') }) }
    if (race === 'decision') act(() => codex.emit({ kind: 'interaction_required', interactionId: 'interaction-1', taskId: 'task-1', operationId: 'operation-1', interactionKind: 'FILE_CHANGE_APPROVAL', category: 'file change', reason: 'Review the bounded change', permissionScope: 'once', expiresAt: '2099-01-01T00:00:00Z' }))
    await act(async () => csrf.resolve('csrf-token'))
    expect(await steering).toMatch(/no longer available/)
    expect(codex.api.steer).not.toHaveBeenCalled()
  })

  it('keeps an in-flight steering acknowledgement attached to its terminal operation and prevents duplicate clicks', async () => {
    const pending = deferred<void>()
    const operation = activeOperation()
    const codex = codexFlow({ detail: detail({ activeOperation: operation, latestOperation: operation }) })
    vi.mocked(codex.api.steer).mockImplementation(() => pending.promise)
    renderWorkspace(codex.api, conversationFlow().api)
    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    fireEvent.change(await screen.findByRole('textbox', { name: 'Message Synvo' }), { target: { value: 'Include sales by region' } })
    const send = screen.getByRole('button', { name: 'Update instructions' })
    fireEvent.click(send)
    fireEvent.click(send)
    await waitFor(() => expect(codex.api.steer).toHaveBeenCalledOnce())
    act(() => codex.emit({ kind: 'activity', sequence: 9, type: 'TURN_COMPLETED', label: 'Completed', text: null, truncated: false, terminalStatus: 'COMPLETED' }))
    await act(async () => pending.resolve())
    fireEvent.click(screen.getByRole('button', { name: 'Task details' }))
    const instruction = screen.getAllByText('Include sales by region')[0]
    expect(instruction.closest('li')).toHaveAttribute('data-status', 'completed')
  })

  it('shows unavailable account metadata honestly in Settings', async () => {
    const codex = codexFlow()
    const status = await codex.api.status()
    vi.mocked(codex.api.status).mockResolvedValue({ ...status, account: null })
    renderWorkspace(codex.api, conversationFlow().api)
    fireEvent.click(await screen.findByRole('button', { name: 'Settings' }))
    expect(await screen.findByText('Usage information unavailable')).toBeInTheDocument()
    expect(screen.getByText('H5 connection')).toBeInTheDocument()
    expect(screen.queryByText('Knowledge Sources')).not.toBeInTheDocument()
    expect(screen.queryByText('0%')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Quotation/ })).toBeDisabled()
  })

  it('starts once, uses read-only by default, and sends only after the created conversation loads', async () => {
    const creation = deferred<CodexTask>()
    const codex = codexFlow({ tasks: [] })
    vi.mocked(codex.api.createTask).mockImplementation(() => creation.promise)
    const conversation = conversationFlow()
    const loaded = deferred<Awaited<ReturnType<ConversationApi['get']>>>()
    const empty = await conversation.api.get('conversation-1')
    vi.mocked(conversation.api.get).mockImplementation(() => loaded.promise)
    renderWorkspace(codex.api, conversation.api)
    expect(await screen.findByRole('heading', { name: 'What would you like to work on?' })).toBeInTheDocument()
    expect(screen.getByText('GPT-5.6 Sol')).toBeInTheDocument()
    expect(screen.getByText('High effort')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: /Task title/ }).compareDocumentPosition(
      screen.getByRole('textbox', { name: 'Your request' }),
    ) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    const start = screen.getByRole('button', { name: 'Start task' })
    expect(start).toBeDisabled()
    expect(screen.getByRole('radio', { name: /Read Only/ })).toBeChecked()
    fireEvent.change(screen.getByRole('textbox', { name: 'Your request' }), { target: { value: 'Summarize the monthly sales report' } })
    fireEvent.click(start)
    fireEvent.click(start)
    await waitFor(() => expect(codex.api.createTask).toHaveBeenCalledExactlyOnceWith({ workspaceId: 'products', mode: 'READ_ONLY' }, 'csrf-token'))
    expect(conversation.api.submit).not.toHaveBeenCalled()
    await act(async () => creation.resolve(pilotTask()))
    expect(conversation.api.submit).not.toHaveBeenCalled()
    await act(async () => loaded.resolve(empty))
    await waitFor(() => expect(conversation.api.submit).toHaveBeenCalledWith(expect.objectContaining({ conversationId: 'conversation-1', content: 'Summarize the monthly sales report', reasoningEffort: 'high' }), 'csrf-token'))
    expect(conversation.api.submit).toHaveBeenCalledOnce()
  })

  it('keeps the configured workspace, optional title, and edit policy in the start request', async () => {
    const codex = codexFlow({ tasks: [] })
    renderWorkspace(codex.api, conversationFlow().api)
    fireEvent.change(await screen.findByRole('textbox', { name: 'Your request' }), { target: { value: 'Prepare a sales report' } })
    fireEvent.click(screen.getByRole('radio', { name: /Edit workspace files/ }))
    fireEvent.change(screen.getByRole('textbox', { name: /Task title/ }), { target: { value: 'Monthly report' } })
    fireEvent.click(screen.getByRole('button', { name: 'Start task' }))
    await waitFor(() => expect(codex.api.createTask).toHaveBeenCalledWith({ workspaceId: 'products', mode: 'WORKSPACE_WRITE', title: 'Monthly report' }, 'csrf-token'))
  })

  it('isolates effort overrides by task and sends the selected effort on a follow-up', async () => {
    const second = { ...pilotTask(), taskId: 'task-2', conversationId: 'conversation-2', title: 'Second task' }
    const codex = codexFlow({ tasks: [pilotTask(), second] })
    vi.mocked(codex.api.task).mockImplementation(async (id) => detail({ task: id === second.taskId ? second : pilotTask() }))
    const conversation = conversationFlow()
    const empty = await conversation.api.get('conversation-1')
    vi.mocked(conversation.api.get).mockImplementation(async (conversationId) => ({ ...empty, conversationId }))
    renderWorkspace(codex.api, conversation.api)
    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Reasoning' })).toBeEnabled())
    expect(screen.getByRole('combobox', { name: 'Reasoning' })).toHaveValue('high')
    fireEvent.change(screen.getByRole('combobox', { name: 'Reasoning' }), { target: { value: 'medium' } })
    fireEvent.click(screen.getByRole('button', { name: 'Second task' }))
    await waitFor(() => expect(screen.getByRole('heading', { name: 'Second task' })).toBeInTheDocument())
    expect(screen.getByRole('combobox', { name: 'Reasoning' })).toHaveValue('high')
    fireEvent.click(screen.getByRole('button', { name: 'Pilot task' }))
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Message Synvo' })).toBeEnabled())
    expect(screen.getByRole('combobox', { name: 'Reasoning' })).toHaveValue('medium')
    fireEvent.change(screen.getByRole('textbox', { name: 'Message Synvo' }), { target: { value: 'Summarize the report.' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))
    await waitFor(() => expect(conversation.api.submit).toHaveBeenCalledWith(expect.objectContaining({ reasoningEffort: 'medium' }), 'csrf-token'))
  })

  it('starts a new task with High even after another task selected Medium', async () => {
    const codex = codexFlow()
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)
    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await waitFor(() => expect(screen.getByRole('combobox', { name: 'Reasoning' })).toBeEnabled())
    fireEvent.change(screen.getByRole('combobox', { name: 'Reasoning' }), { target: { value: 'medium' } })
    fireEvent.click(screen.getByRole('button', { name: 'New Codex task' }))
    const request = await screen.findByRole('textbox', { name: 'Your request' })
    expect(screen.getByText('High effort')).toBeInTheDocument()
    fireEvent.change(request, { target: { value: 'Prepare a new report.' } })
    fireEvent.click(screen.getByRole('button', { name: 'Start task' }))
    await waitFor(() => expect(conversation.api.submit).toHaveBeenCalledWith(expect.objectContaining({ reasoningEffort: 'high' }), 'csrf-token'))
    expect(screen.getByRole('combobox', { name: 'Reasoning' })).toHaveValue('high')
  })

  it('shows and sends the supported fallback when High is unavailable', async () => {
    const codex = codexFlow({ tasks: [] })
    const status = await codex.api.status()
    vi.mocked(codex.api.status).mockResolvedValue({ ...status, reasoningEfforts: ['low'] })
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)
    const request = await screen.findByRole('textbox', { name: 'Your request' })
    expect(screen.getByText('Low effort')).toBeInTheDocument()
    expect(screen.getByText('High effort unavailable; using Low.')).toBeInTheDocument()
    fireEvent.change(request, { target: { value: 'Summarize the report.' } })
    fireEvent.click(screen.getByRole('button', { name: 'Start task' }))
    await waitFor(() => expect(conversation.api.submit).toHaveBeenCalledWith(expect.objectContaining({ reasoningEffort: 'low' }), 'csrf-token'))
  })

  it.each(['model', 'effort'])('does not claim a default or create when %s metadata is missing', async (missing) => {
    const codex = codexFlow({ tasks: [] })
    const status = await codex.api.status()
    vi.mocked(codex.api.status).mockResolvedValue({ ...status, ...(missing === 'model' ? { model: null } : { reasoningEfforts: [] }) })
    renderWorkspace(codex.api, conversationFlow().api)
    fireEvent.change(await screen.findByRole('textbox', { name: 'Your request' }), { target: { value: 'Summarize the report.' } })
    expect(screen.getByText(missing === 'model' ? 'Model unavailable' : 'Effort unavailable')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Start task' })).toBeDisabled()
    expect(codex.api.createTask).not.toHaveBeenCalled()
  })

  it('recovers a confirmed task whose detail failed to load without repeating creation', async () => {
    const codex = codexFlow({ tasks: [] })
    vi.mocked(codex.api.task).mockRejectedValueOnce(new Error('Task detail temporarily unavailable'))
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)
    fireEvent.change(await screen.findByRole('textbox', { name: 'Your request' }), { target: { value: 'Summarize sales' } })
    fireEvent.click(screen.getByRole('button', { name: 'Start task' }))
    expect(await screen.findByText('Your task was created. Load it to send your retained request.')).toBeInTheDocument()
    expect(conversation.api.submit).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Retry loading task' }))
    await waitFor(() => expect(conversation.api.submit).toHaveBeenCalledOnce())
    expect(codex.api.createTask).toHaveBeenCalledOnce()
  })

  it('opens phone navigation and task details as dismissible focus-restoring sheets', async () => {
    vi.stubGlobal('matchMedia', vi.fn((query: string) => ({ matches: /max-width/.test(query), media: query, addEventListener: vi.fn(), removeEventListener: vi.fn() })))
    try {
      const codex = codexFlow()
      window.history.replaceState(null, '', '/?codexTask=task-1')
      renderWorkspace(codex.api, conversationFlow().api)
      const menu = await screen.findByRole('button', { name: 'Open navigation' })
      menu.focus()
      fireEvent.click(menu)
      expect(screen.getByRole('dialog', { name: 'Synvo AI Assistant task navigation' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Close navigation' })).toHaveFocus()
      fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
      expect(menu).toHaveFocus()
      const details = await screen.findByRole('button', { name: 'Task details' })
      details.focus()
      fireEvent.click(details)
      expect(screen.getByRole('dialog', { name: 'Codex task details' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: 'Close task details' })).toHaveFocus()
      fireEvent.keyDown(document.activeElement!, { key: 'Escape' })
      expect(details).toHaveFocus()
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    } finally { vi.unstubAllGlobals() }
  })

  it('retains an unconfirmed creation draft and refreshes tasks without creating again', async () => {
    const codex = codexFlow({ tasks: [] })
    vi.mocked(codex.api.createTask).mockRejectedValue(new Error('Connection interrupted'))
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)
    const request = await screen.findByRole('textbox', { name: 'Your request' })
    fireEvent.change(request, { target: { value: 'Analyze the monthly report' } })
    fireEvent.click(screen.getByRole('button', { name: 'Start task' }))
    expect(await screen.findByText(/Could not confirm task creation/)).toBeInTheDocument()
    expect(request).toHaveValue('Analyze the monthly report')
    expect(screen.getByRole('button', { name: 'Start task' })).toBeDisabled()
    expect(codex.api.tasks).toHaveBeenCalledTimes(2)
    expect(codex.api.createTask).toHaveBeenCalledOnce()
    expect(conversation.api.submit).not.toHaveBeenCalled()
  })

  it('retries a failed first message in the created task without repeating creation', async () => {
    const codex = codexFlow({ tasks: [] })
    const conversation = conversationFlow()
    vi.mocked(conversation.api.submit).mockRejectedValueOnce(new Error('Please retry this request'))
    renderWorkspace(codex.api, conversation.api)
    fireEvent.change(await screen.findByRole('textbox', { name: 'Your request' }), { target: { value: 'Summarize sales' } })
    fireEvent.click(screen.getByRole('button', { name: 'Start task' }))
    fireEvent.click(await screen.findByRole('button', { name: 'Retry' }))
    await waitFor(() => expect(conversation.api.submit).toHaveBeenCalledTimes(2))
    expect(codex.api.createTask).toHaveBeenCalledOnce()
    expect(vi.mocked(conversation.api.submit).mock.calls[1][0]).toMatchObject({ conversationId: 'conversation-1', content: 'Summarize sales' })
  })

  it('keeps successful runtime and workspace data when the initial task list fails', async () => {
    const codex = codexFlow({ tasks: [] })
    vi.mocked(codex.api.tasks)
      .mockRejectedValueOnce(new Error('The task list is temporarily unavailable.'))
      .mockResolvedValue([])

    renderWorkspace(codex.api, conversationFlow().api)

    expect(await screen.findByText('Codex is ready')).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Synvo Workspaces/Finance/' })).toBeInTheDocument()
    expect(screen.queryByText('Checking Codex…')).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('polls a recovering runtime until it becomes ready without reloading H5', async () => {
    let runRecoveryPoll: (() => void) | null = null
    vi.spyOn(window, 'setInterval').mockImplementation(((handler: TimerHandler, timeout?: number) => {
      if (timeout === 3_000 && typeof handler === 'function') {
        runRecoveryPoll = () => { handler() }
      }
      return 1
    }) as typeof window.setInterval)
    const codex = codexFlow({ tasks: [] })
    const ready = await codex.api.status()
    vi.mocked(codex.api.status)
      .mockResolvedValueOnce({ ...ready, state: 'RECOVERING' })
      .mockResolvedValueOnce(ready)

    renderWorkspace(codex.api, conversationFlow().api)

    expect(await screen.findByText('Codex is reconnecting automatically…')).toBeInTheDocument()
    expect(runRecoveryPoll).not.toBeNull()
    await act(async () => runRecoveryPoll?.())
    expect(await screen.findByText('Codex is ready')).toBeInTheDocument()
  })

  it('replaces a failed initial status request with an unavailable state and self-recovers', async () => {
    let runRecoveryPoll: (() => void) | null = null
    vi.spyOn(window, 'setInterval').mockImplementation(((handler: TimerHandler, timeout?: number) => {
      if (timeout === 3_000 && typeof handler === 'function') {
        runRecoveryPoll = () => { handler() }
      }
      return 1
    }) as typeof window.setInterval)
    const codex = codexFlow({ tasks: [] })
    const ready = await codex.api.status()
    vi.mocked(codex.api.status)
      .mockRejectedValueOnce(new Error('Codex status is temporarily unavailable.'))
      .mockResolvedValueOnce(ready)

    renderWorkspace(codex.api, conversationFlow().api)

    expect(await screen.findByText('Codex is temporarily unavailable')).toBeInTheDocument()
    expect(screen.queryByText('Checking Codex…')).not.toBeInTheDocument()
    await act(async () => runRecoveryPoll?.())
    expect(await screen.findByText('Codex is ready')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('renders ordered activity and resolves a mandatory detail-rich H5 interaction', async () => {
    const operation = activeOperation()
    const codex = codexFlow({ detail: detail({ activeOperation: operation, latestOperation: operation }) })
    const conversation = conversationFlow()
    const storageSpy = vi.spyOn(Storage.prototype, 'setItem')
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await screen.findByRole('textbox', { name: 'Message Synvo' })
    fireEvent.click(screen.getByRole('button', { name: 'Task details' }))
    fireEvent.click(screen.getByText('Edit goal').closest('summary')!)
    fireEvent.click(screen.getByText('Task actions', { selector: 'summary' }))

    const technicalActivity = screen.getByText('Technical activity').closest('summary')
    expect(technicalActivity).not.toBeNull()
    expect(technicalActivity?.closest('details')).not.toHaveAttribute('open')
    fireEvent.click(technicalActivity!)

    act(() => codex.emit({
      kind: 'activity',
      sequence: 2,
      type: 'MESSAGE_DELTA',
      label: 'Writing the result',
      text: 'partial streamed fragment',
      truncated: false,
      terminalStatus: null,
    }))
    act(() => codex.emit({
      kind: 'activity',
      sequence: 3,
      type: 'MESSAGE_COMPLETED',
      label: 'Writing the result',
      text: 'Completed response already owned by the conversation',
      truncated: false,
      terminalStatus: null,
    }))
    act(() => codex.emit({
      kind: 'activity',
      sequence: 4,
      type: 'COMMAND_OUTPUT',
      label: 'Command produced output',
      text: '  bounded output\n',
      truncated: false,
      terminalStatus: null,
    }))
    expect(screen.getByText(/bounded output/)).toBeInTheDocument()
    expect(screen.getByText('Writing the result')).toBeInTheDocument()
    expect(screen.queryByText(/partial streamed fragment/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Completed response already owned/)).not.toBeInTheDocument()

    act(() => codex.emit({
      kind: 'interaction_required',
      interactionId: 'interaction-1',
      taskId: 'task-1',
      operationId: 'operation-1',
      interactionKind: 'FILE_CHANGE_APPROVAL',
      category: 'file change',
      reason: 'Change one bounded workspace file.',
      permissionScope: 'once',
      expiresAt: '2099-08-21T13:00:00Z',
    }))

    const dialog = await screen.findByRole('dialog', { name: 'Review file change' })
    expect(dialog).toHaveTextContent('Synvo pilot')
    expect(dialog).toHaveTextContent('src/codex/CodexWorkspace.test.tsx')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Approve once' })).toHaveFocus())
    fireEvent.click(screen.getByRole('button', { name: 'Approve once' }))

    await waitFor(() => expect(codex.api.decideInteraction).toHaveBeenCalledWith(
      'interaction-1', 'APPROVE_ONCE', {}, 'csrf-token',
    ))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(storageSpy).not.toHaveBeenCalled()
  })

  it('offers only a one-time approval for a bounded interaction', async () => {
    window.history.replaceState(null, '', '/?codexTask=task-1&codexInteraction=interaction-1')
    const codex = codexFlow()
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    expect(await screen.findByRole('button', { name: 'Approve once' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /session/i })).not.toBeInTheDocument()
  })

  it('renders live normalized activity inside the owning assistant response', async () => {
    const operation = activeOperation()
    const codex = codexFlow({ detail: detail({ activeOperation: operation, latestOperation: operation }) })
    const conversation = conversationFlow({ activeRun: true })
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    const assistant = await screen.findByLabelText('Synvo response')

    act(() => codex.emit({
      kind: 'activity',
      sequence: 1,
      type: 'TURN_STARTED',
      label: 'Codex started',
      text: null,
      truncated: false,
      terminalStatus: null,
    }))
    act(() => codex.emit({
      kind: 'activity',
      sequence: 2,
      type: 'PLAN_STARTED',
      label: 'Planning the task',
      text: null,
      truncated: false,
      terminalStatus: null,
    }))
    act(() => codex.emit({
      kind: 'activity',
      sequence: 3,
      type: 'PLAN_DELTA',
      label: 'Planning the task',
      text: 'Inspect the configured workspace first.',
      truncated: false,
      terminalStatus: null,
    }))

    act(() => conversation.emit({ sequence: 1, type: 'thinking', delta: null, message: null, presentation: null, action: null }))
    const timeline = within(assistant).getByRole('region', { name: 'Agent activity' })
    expect(within(timeline).getByText('Analyzing your request…', { selector: 'strong' })).toBeInTheDocument()
    expect(within(timeline).getByText('Inspect the configured workspace first.')).toBeInTheDocument()
    expect(within(assistant).queryByLabelText('Preparing a response…')).not.toBeInTheDocument()
  })

  it('keeps synchronizing until a submitted conversation is attached to its workspace operation', async () => {
    const operation = activeOperation()
    const codex = codexFlow()
    vi.mocked(codex.api.task)
      .mockResolvedValueOnce(detail())
      .mockResolvedValueOnce(detail())
      .mockResolvedValue(detail({ activeOperation: operation, latestOperation: operation }))
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    fireEvent.change(await screen.findByRole('textbox', { name: 'Message Synvo' }), {
      target: { value: 'Create and validate the requested report.' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))

    await screen.findByLabelText('Synvo response')
    await waitFor(() => expect(codex.api.task).toHaveBeenCalledTimes(3))
    await waitFor(() => expect(codex.api.subscribe).toHaveBeenCalledWith(
      'operation-1', expect.any(Function), expect.any(Function),
    ))
    act(() => codex.emit({
      kind: 'activity',
      sequence: 1,
      type: 'TURN_STARTED',
      label: 'Codex started',
      text: null,
      truncated: false,
      terminalStatus: null,
    }))
    act(() => codex.emit({
      kind: 'activity',
      sequence: 2,
      type: 'REASONING_DELTA',
      label: 'Analyzing the task',
      text: 'Checking the Finance sources and validation criteria.',
      truncated: false,
      terminalStatus: null,
    }))

    const timeline = within(screen.getByLabelText('Synvo response'))
      .getByRole('region', { name: 'Agent activity' })
    expect(await within(timeline).findByText('Task started')).toBeInTheDocument()
    expect(within(timeline).getByText('Checking the Finance sources and validation criteria.')).toBeInTheDocument()
    expect(within(timeline).getByText('2 milestones')).toBeInTheDocument()
    expect(within(timeline).queryByText(/normalized events/)).not.toBeInTheDocument()
  })

  it('supports task management, goals, review, steering, and stop through owning APIs', async () => {
    let currentDetail = detail()
    const codex = codexFlow({ detail: currentDetail })
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await screen.findByRole('textbox', { name: 'Message Synvo' })
    fireEvent.click(screen.getByRole('button', { name: 'Task details' }))
    fireEvent.click(screen.getByText('Edit goal').closest('summary')!)
    fireEvent.click(screen.getByText('Task actions', { selector: 'summary' }))

    expect(screen.getByRole('button', { name: 'Close task details' })).toHaveClass('codex-task-panel__close')
    const taskPanel = screen.getByRole('complementary', { name: 'Codex task details' })
    const resizeHandle = screen.getByRole('separator', { name: 'Resize Task details' })
    expect(taskPanel).toHaveStyle({ '--codex-task-panel-width': '480px' })
    expect(resizeHandle).toHaveAttribute('aria-valuemin', '384')
    expect(resizeHandle).toHaveAttribute('aria-valuemax', '640')
    fireEvent.keyDown(resizeHandle, { key: 'ArrowLeft' })
    expect(resizeHandle).toHaveAttribute('aria-valuenow', '504')
    fireEvent.keyDown(resizeHandle, { key: 'End' })
    expect(resizeHandle).toHaveAttribute('aria-valuenow', '640')
    fireEvent.keyDown(resizeHandle, { key: 'Home' })
    expect(resizeHandle).toHaveAttribute('aria-valuenow', '384')
    fireEvent.doubleClick(resizeHandle)
    expect(resizeHandle).toHaveAttribute('aria-valuenow', '480')
    fireEvent.pointerDown(resizeHandle, { button: 0, clientX: 600 })
    fireEvent.pointerMove(window, { clientX: 500 })
    await waitFor(() => expect(resizeHandle).toHaveAttribute('aria-valuenow', '580'))
    fireEvent.pointerMove(window, { clientX: 200 })
    await waitFor(() => expect(resizeHandle).toHaveAttribute('aria-valuenow', '640'))
    fireEvent.pointerUp(window)
    const accessModeSelect = screen.getByRole('combobox', { name: 'Access mode' })
    const reviewTargetSelect = screen.getByRole('combobox', { name: 'Target' })
    expect(accessModeSelect).toHaveClass('codex-panel-select')
    expect(reviewTargetSelect).toHaveClass('codex-panel-select')
    expect(accessModeSelect.closest('.codex-panel-select-wrap')?.querySelector('svg')).toBeInTheDocument()
    expect(reviewTargetSelect.closest('.codex-panel-select-wrap')?.querySelector('svg')).toBeInTheDocument()
    const titleInput = screen.getByRole('textbox', { name: 'Title' })
    const renameButton = screen.getByRole('button', { name: 'Rename' })
    expect(titleInput.closest('.codex-field-action')).toContainElement(renameButton)
    const taskActions = screen.getByRole('group', { name: 'Task actions' })
    expect(within(taskActions).getAllByRole('button').map(({ textContent }) => textContent)).toEqual([
      'Pin',
      'Archive',
      'Fork',
    ])
    fireEvent.change(titleInput, { target: { value: 'Renamed pilot task' } })
    fireEvent.click(renameButton)
    await waitFor(() => expect(codex.api.renameTask).toHaveBeenCalledWith(
      'task-1', 'Renamed pilot task', 'csrf-token',
    ))
    fireEvent.click(screen.getByRole('button', { name: 'Pin' }))
    await waitFor(() => expect(codex.api.pinTask).toHaveBeenCalledWith('task-1', true, 'csrf-token'))

    fireEvent.change(screen.getByRole('textbox', { name: 'Objective and completion criteria' }), {
      target: { value: 'Finish the focused vertical slice' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Set goal' }))
    await waitFor(() => expect(codex.api.setGoal).toHaveBeenCalledWith(
      'task-1', 'Finish the focused vertical slice', 'SAVE', 'csrf-token',
    ))
    expect(await screen.findByText('Objective saved. The goal status did not change.')).toBeInTheDocument()
    expect(screen.getByText('Saving the objective does not start work or modify files.')).toBeInTheDocument()

    vi.mocked(codex.api.review).mockResolvedValue({ ...activeOperation('REVIEW'), operationId: 'review-1' })
    fireEvent.click(screen.getByRole('button', { name: 'Start review' }))
    await waitFor(() => expect(codex.api.review).toHaveBeenCalledWith(
      'task-1', 'UNCOMMITTED_CHANGES', null, 'csrf-token',
    ))

    act(() => codex.emit({ kind: 'activity', sequence: 5, type: 'TURN_COMPLETED', label: 'Review completed', text: null, truncated: false, terminalStatus: 'COMPLETED' }))
    await waitFor(() => expect(screen.getByRole('button', { name: 'Send message' })).toBeInTheDocument())
    currentDetail = detail({ activeOperation: activeOperation(), latestOperation: activeOperation() })
    vi.mocked(codex.api.task).mockResolvedValue(currentDetail)
    fireEvent.change(screen.getByRole('textbox', { name: 'Message Synvo' }), {
      target: { value: 'Continue the implementation' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))
    await waitFor(() => expect(conversation.api.submit).toHaveBeenCalledOnce())
    await screen.findByRole('button', { name: 'Update instructions' })
    fireEvent.change(screen.getByRole('textbox', { name: 'Message Synvo' }), {
      target: { value: 'Run typecheck before finishing' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Update instructions' }))
    await waitFor(() => expect(codex.api.steer).toHaveBeenCalledWith(
      'operation-1', 'Run typecheck before finishing', 'csrf-token',
    ))
    fireEvent.click(screen.getByRole('button', { name: 'Stop' }))
    await waitFor(() => expect(conversation.api.stop).toHaveBeenCalledWith('run-1', 'csrf-token'))
    expect(codex.api.stopOperation).not.toHaveBeenCalled()
  })

  it('acknowledges steering submission and keeps failed instructions available for retry', async () => {
    const steering = deferred<void>()
    const operation = activeOperation()
    const codex = codexFlow({ detail: detail({ activeOperation: operation, latestOperation: operation }) })
    vi.mocked(codex.api.steer).mockImplementationOnce(() => steering.promise)
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await screen.findByRole('textbox', { name: 'Message Synvo' })
    fireEvent.click(screen.getByRole('button', { name: 'Task details' }))
    fireEvent.click(screen.getByText('Edit goal').closest('summary')!)
    fireEvent.click(screen.getByText('Task actions', { selector: 'summary' }))

    const taskPanel = within(screen.getByRole('complementary', { name: 'Codex task details' }))
    expect(taskPanel.getByRole('heading', { name: 'Current activity' })).toBeInTheDocument()
    expect(taskPanel.getByText('Codex is working')).toBeInTheDocument()
    expect(taskPanel.getByText('Update instructions or stop from the conversation composer.')).toBeInTheDocument()
    expect(screen.queryByText('turn · running')).not.toBeInTheDocument()
    expect(taskPanel.getByText('Instructions sent during this task')).toBeInTheDocument()
    expect(taskPanel.getByText('No steering instructions have been sent in this H5 session.')).toBeInTheDocument()

    const steeringInput = screen.getByRole('textbox', { name: 'Message Synvo' })
    fireEvent.change(steeringInput, { target: { value: 'Add the requested owners section' } })
    fireEvent.click(screen.getByRole('button', { name: 'Update instructions' }))

    expect(await screen.findByRole('button', { name: 'Sending…' })).toBeDisabled()
    expect(screen.getByText('Sending your update…')).toBeInTheDocument()
    expect(codex.api.steer).toHaveBeenCalledOnce()

    steering.resolve()
    expect(await screen.findByText('Steering sent')).toBeInTheDocument()
    expect(screen.getByText('Codex accepted your update. Delivery does not mean the work is complete.')).toBeInTheDocument()
    const steeringMilestone = (await screen.findByText('Instructions updated')).closest('li')
    expect(steeringMilestone).toHaveAttribute('data-status', 'steering')
    expect(screen.getByText('Your steering update was delivered to Codex.')).toBeInTheDocument()
    expect(steeringInput).toHaveValue('')

    const history = within(taskPanel.getByRole('heading', { name: 'Instructions sent during this task' }).closest('section')!)
    expect(history.getByText('Delivered')).toBeInTheDocument()
    const deliveredInstruction = history.getAllByText('Add the requested owners section')[0].closest('details')
    expect(deliveredInstruction).not.toHaveAttribute('open')
    fireEvent.click(deliveredInstruction!.querySelector('summary')!)
    expect(deliveredInstruction).toHaveAttribute('open')
    expect(within(deliveredInstruction!).getByText('Complete instruction')).toBeInTheDocument()

    vi.mocked(codex.api.steer).mockRejectedValueOnce(new Error('Operation finished'))
    fireEvent.change(steeringInput, { target: { value: 'Keep this instruction available' } })
    fireEvent.click(screen.getByRole('button', { name: 'Update instructions' }))

    const steeringFailure = await screen.findByText('Steering wasn’t sent')
    expect(steeringFailure.closest('[role="alert"]')).toBeInTheDocument()
    expect(screen.getByText('Your instruction is still in the box. Review the error above and try again.')).toBeInTheDocument()
    expect(steeringInput).toHaveValue('Keep this instruction available')
    expect(history.getByText('Failed')).toBeInTheDocument()
    expect(history.getAllByText('Keep this instruction available')).toHaveLength(2)
  })

  it('marks delivered steering history complete when its operation finishes', async () => {
    const operation = activeOperation()
    const currentDetail = detail({ activeOperation: operation, latestOperation: operation })
    const codex = codexFlow({ detail: currentDetail })
    vi.mocked(codex.api.task).mockImplementation(() => Promise.resolve(currentDetail))
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await screen.findByRole('textbox', { name: 'Message Synvo' })
    fireEvent.click(screen.getByRole('button', { name: 'Task details' }))
    fireEvent.click(screen.getByText('Edit goal').closest('summary')!)
    fireEvent.click(screen.getByText('Task actions', { selector: 'summary' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Message Synvo' }), {
      target: { value: 'Add a concise risk summary' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Update instructions' }))

    const taskPanel = within(screen.getByRole('complementary', { name: 'Codex task details' }))
    const historySection = taskPanel.getByRole('heading', { name: 'Instructions sent during this task' }).closest('section')!
    const history = within(historySection)
    const delivered = (await history.findAllByText('Add a concise risk summary'))[0]
    expect(delivered.closest('li')).toHaveAttribute('data-status', 'delivered')

    act(() => codex.emit({
      kind: 'activity',
      sequence: 9,
      type: 'TURN_COMPLETED',
      label: 'Codex task finished',
      text: null,
      truncated: false,
      terminalStatus: 'COMPLETED',
    }))

    await waitFor(() => expect(delivered.closest('li')).toHaveAttribute('data-status', 'completed'))
    expect(history.getByText('Task completed')).toBeInTheDocument()
    const currentActivity = taskPanel.getByRole('heading', { name: 'Current activity' }).closest('section')!
    const operationStatus = within(currentActivity).getByRole('status')
    expect(within(operationStatus).getByText('Task completed')).toBeInTheDocument()
    expect(within(operationStatus).getByText('Codex finished the latest work in this task.')).toBeInTheDocument()
    expect(within(operationStatus).queryByText('Codex is working')).not.toBeInTheDocument()
    expect(screen.getByText('Steering sent')).toBeInTheDocument()
    fireEvent.change(screen.getByRole('textbox', { name: 'Message Synvo' }), {
      target: { value: 'Now summarize the next reporting period' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))
    await waitFor(() => expect(conversation.api.submit).toHaveBeenCalledOnce())
    expect(screen.queryByText('Steering sent')).not.toBeInTheDocument()
    expect(delivered.closest('li')).toHaveAttribute('data-status', 'completed')
  })

  it('explains a saved goal, its tracked progress, and unsaved changes', async () => {
    const goal: CodexGoal = {
      objective: 'Maintain verified Sales workspace reports while preserving all source files.',
      status: 'active',
      tokensUsed: 32_106,
      timeUsedSeconds: 28,
    }
    const codex = codexFlow({ goal })
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await screen.findByRole('textbox', { name: 'Message Synvo' })
    fireEvent.click(screen.getByRole('button', { name: 'Task details' }))
    fireEvent.click(screen.getByText('Edit goal').closest('summary')!)
    fireEvent.click(screen.getByText('Task actions', { selector: 'summary' }))

    const heading = screen.getByRole('heading', { name: 'Task goal' })
    const goalSection = heading.closest('section')
    expect(goalSection).not.toBeNull()
    const goalView = within(goalSection!)
    expect(goalView.getByRole('status')).toHaveTextContent('Goal is in progress')
    expect(goalView.getByText('Codex will keep this outcome in mind as you continue this task.')).toBeInTheDocument()
    expect(goalView.getByText('32,106 tokens')).toBeInTheDocument()
    expect(goalView.getByText('28 seconds')).toBeInTheDocument()
    expect(goalView.getByText('Send a message in the conversation to continue working toward this goal.')).toBeInTheDocument()
    expect(goalView.getByRole('button', { name: 'Pause goal' })).toBeEnabled()

    fireEvent.change(goalView.getByRole('textbox', { name: 'Objective and completion criteria' }), {
      target: { value: 'Maintain verified Finance reports.' },
    })
    expect(goalView.getByText('Unsaved changes')).toBeInTheDocument()
    expect(goalView.getByRole('button', { name: 'Save changes' })).toBeEnabled()
    expect(goalView.getByRole('button', { name: 'Pause goal' })).toBeDisabled()
    expect(goalView.getByText('Save your objective changes to enable this action.')).toBeInTheDocument()
  })

  it('keeps a completed goal visible and clears stale save feedback after a turn', async () => {
    const initialGoal: CodexGoal = {
      objective: 'Maintain verified Sales reports.',
      status: 'active',
      tokensUsed: 0,
      timeUsedSeconds: 0,
    }
    const savedGoal = {
      ...initialGoal,
      objective: 'Maintain verified Sales reports and reconcile calculations within 0.01.',
    }
    const completedGoal: CodexGoal = {
      ...savedGoal,
      status: 'complete',
      tokensUsed: 12_345,
      timeUsedSeconds: 42,
    }
    const running = activeOperation()
    const codex = codexFlow({ goal: initialGoal })
    vi.mocked(codex.api.goal)
      .mockResolvedValueOnce(initialGoal)
      .mockResolvedValueOnce(savedGoal)
      .mockResolvedValue(completedGoal)
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await screen.findByRole('textbox', { name: 'Message Synvo' })
    fireEvent.click(screen.getByRole('button', { name: 'Task details' }))
    fireEvent.click(screen.getByText('Edit goal').closest('summary')!)
    fireEvent.click(screen.getByText('Task actions', { selector: 'summary' }))
    const goalView = within(screen.getByRole('heading', { name: 'Task goal' }).closest('section')!)

    fireEvent.change(goalView.getByRole('textbox', { name: 'Objective and completion criteria' }), {
      target: { value: savedGoal.objective },
    })
    fireEvent.click(goalView.getByRole('button', { name: 'Save changes' }))
    expect(await goalView.findByText('Objective saved. The goal status did not change.')).toBeInTheDocument()

    vi.mocked(codex.api.task).mockResolvedValue(detail({ activeOperation: running, latestOperation: running }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Message Synvo' }), {
      target: { value: 'Validate the existing report.' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))
    await waitFor(() => expect(codex.api.task).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(
      goalView.queryByText('Objective saved. The goal status did not change.'),
    ).not.toBeInTheDocument())

    vi.mocked(codex.api.task).mockResolvedValue(detail({
      activeOperation: null,
      latestOperation: { ...running, status: 'COMPLETED' },
    }))
    act(() => codex.emit({
      kind: 'activity',
      sequence: 9,
      type: 'TURN_COMPLETED',
      label: 'Completed',
      text: null,
      truncated: false,
      terminalStatus: 'COMPLETED',
    }))

    expect(await goalView.findByText('Goal is complete')).toBeInTheDocument()
    expect(goalView.getByDisplayValue(savedGoal.objective)).toBeInTheDocument()
    expect(goalView.getByText('12,345 tokens')).toBeInTheDocument()
    expect(goalView.getByText('42 seconds')).toBeInTheDocument()
    expect(goalView.getByRole('button', { name: 'Restart goal' })).toBeEnabled()
  })

  it('explains a blocked goal and resumes it without hiding the saved objective', async () => {
    const blockedGoal: CodexGoal = {
      objective: 'Maintain verified Sales workspace reports while preserving all source files.',
      status: 'blocked',
      tokensUsed: 41_831,
      timeUsedSeconds: 47,
    }
    const resumedGoal = { ...blockedGoal, status: 'active' as const }
    const codex = codexFlow({ goal: blockedGoal })
    vi.mocked(codex.api.goal)
      .mockResolvedValueOnce(blockedGoal)
      .mockResolvedValueOnce(resumedGoal)
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await screen.findByRole('textbox', { name: 'Message Synvo' })
    fireEvent.click(screen.getByRole('button', { name: 'Task details' }))
    fireEvent.click(screen.getByText('Edit goal').closest('summary')!)
    fireEvent.click(screen.getByText('Task actions', { selector: 'summary' }))

    const goalView = within(screen.getByRole('heading', { name: 'Task goal' }).closest('section')!)
    expect(goalView.getByText('Needs attention')).toBeInTheDocument()
    expect(goalView.getByText('Goal needs your attention')).toBeInTheDocument()
    expect(goalView.getByText(/stopped because it could not make more progress/)).toBeInTheDocument()
    expect(goalView.getByText(/Review or edit the objective/)).toBeInTheDocument()

    fireEvent.click(goalView.getByRole('button', { name: 'Resume goal' }))
    await waitFor(() => expect(codex.api.setGoal).toHaveBeenCalledWith(
      'task-1', blockedGoal.objective, 'RESUME', 'csrf-token',
    ))
    expect(await goalView.findByText('Goal resumed. Send a message when you are ready for Codex to continue.')).toBeInTheDocument()
    expect(goalView.getByText('In progress')).toBeInTheDocument()
  })

  it('pauses an active goal with an explicit lifecycle action', async () => {
    const activeGoal: CodexGoal = {
      objective: 'Maintain verified Sales reports.',
      status: 'active',
      tokensUsed: 100,
      timeUsedSeconds: 10,
    }
    const pausedGoal = { ...activeGoal, status: 'paused' as const }
    const codex = codexFlow({ goal: activeGoal })
    vi.mocked(codex.api.goal)
      .mockResolvedValueOnce(activeGoal)
      .mockResolvedValueOnce(pausedGoal)
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await screen.findByRole('textbox', { name: 'Message Synvo' })
    fireEvent.click(screen.getByRole('button', { name: 'Task details' }))
    fireEvent.click(screen.getByText('Edit goal').closest('summary')!)
    fireEvent.click(screen.getByText('Task actions', { selector: 'summary' }))
    const goalView = within(screen.getByRole('heading', { name: 'Task goal' }).closest('section')!)

    fireEvent.click(goalView.getByRole('button', { name: 'Pause goal' }))
    await waitFor(() => expect(codex.api.setGoal).toHaveBeenCalledWith(
      'task-1', activeGoal.objective, 'PAUSE', 'csrf-token',
    ))
    expect(await goalView.findByText('Goal paused. Your objective and progress are still saved.')).toBeInTheDocument()
    expect(goalView.getByText('Paused')).toBeInTheDocument()
  })

  it('routes sidebar actions to the hovered task without changing the selected task', async () => {
    const selected = pilotTask()
    const secondary = {
      ...pilotTask(),
      taskId: 'task-2',
      conversationId: 'conversation-2',
      title: 'Quarterly plan',
    }
    const codex = codexFlow({ tasks: [selected, secondary] })
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await screen.findByRole('textbox', { name: 'Message Synvo' })
    fireEvent.click(screen.getByRole('button', { name: 'Rename Quarterly plan' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Rename Quarterly plan' }), {
      target: { value: 'Q3 operating plan' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Save rename for Quarterly plan' }))

    await waitFor(() => expect(codex.api.renameTask).toHaveBeenCalledWith(
      'task-2', 'Q3 operating plan', 'csrf-token',
    ))
    expect(screen.getByRole('heading', { name: 'Pilot task' })).toBeInTheDocument()
  })

  it('opens an owning task and interaction from a native-Lark H5 deep link', async () => {
    window.history.replaceState(null, '', '/?codexTask=task-1&codexInteraction=interaction-1')
    const codex = codexFlow()
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    expect(await screen.findByRole('dialog', { name: 'Review file change' })).toBeInTheDocument()
    expect(codex.api.task).toHaveBeenCalledWith('task-1', expect.any(AbortSignal))
    expect(codex.api.interaction).toHaveBeenCalledWith('interaction-1', expect.any(AbortSignal))
    await waitFor(() => expect(conversation.api.get).toHaveBeenCalledWith('conversation-1'))
  })

  it('opens persisted task history when replaceable engine metadata is unavailable after restart', async () => {
    const codex = codexFlow()
    vi.mocked(codex.api.inventory).mockRejectedValue(new Error('The Codex task is unavailable.'))
    vi.mocked(codex.api.goal).mockRejectedValue(new Error('The Codex task is unavailable.'))
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))

    expect(await screen.findByRole('textbox', { name: 'Message Synvo' })).toBeEnabled()
    await waitFor(() => expect(conversation.api.get).toHaveBeenCalledWith('conversation-1'))
    expect(screen.queryByText('The Codex task is unavailable.')).not.toBeInTheDocument()
  })

  it('renders bounded MCP elicitation fields and submits their normalized values', async () => {
    window.history.replaceState(null, '', '/?codexTask=task-1&codexInteraction=interaction-form')
    const interaction: CodexInteraction = {
      ...pendingInteraction(),
      interactionId: 'interaction-form',
      kind: 'MCP_ELICITATION',
      category: 'MCP request',
      detail: {
        command: null,
        workingDirectory: null,
        affectedPaths: [],
        mcpServer: 'synvo_safe_fixture',
        mcpTool: 'write_fixture_marker',
        message: 'Create the fixed harmless verification marker?',
        inputMode: 'form',
        elicitationUrl: null,
        fields: [
          { name: 'confirm', label: 'Confirm', type: 'BOOLEAN', required: true, options: [], maxLength: 0 },
          { name: 'profile', label: 'Profile', type: 'SELECT', required: true, options: ['safe', 'strict'], maxLength: 0 },
        ],
      },
    }
    const codex = codexFlow({ interaction })
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    const dialog = await screen.findByRole('dialog', { name: 'Review mcp request' })
    const approve = screen.getByRole('button', { name: 'Approve once' })
    expect(dialog).toHaveTextContent('write_fixture_marker')
    expect(approve).toBeDisabled()
    fireEvent.click(screen.getByRole('checkbox', { name: 'Confirm (required)' }))
    fireEvent.change(screen.getByRole('combobox', { name: 'Profile (required)' }), {
      target: { value: 'strict' },
    })
    fireEvent.click(approve)

    await waitFor(() => expect(codex.api.decideInteraction).toHaveBeenCalledWith(
      'interaction-form', 'APPROVE_ONCE', { confirm: 'true', profile: 'strict' }, 'csrf-token',
    ))
  })

  it('opens the owning H5 approval from the shared conversation lifecycle', async () => {
    const codex = codexFlow()
    let resolveInventory: (() => void) | null = null
    codex.api.inventory = vi.fn(() => new Promise<CodexInventory>((resolve) => {
      resolveInventory = () => resolve({ skills: [], mcpServers: [] })
    }))
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    fireEvent.change(await screen.findByRole('textbox', { name: 'Message Synvo' }), {
      target: { value: 'Apply the bounded file change' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))
    await waitFor(() => expect(conversation.api.subscribe).toHaveBeenCalledOnce())

    act(() => conversation.emit({
      sequence: 4,
      type: 'action_required',
      message: 'Open in H5 to review and approve.',
      delta: null,
      presentation: null,
      action: {
        taskId: 'task-1',
        interactionId: 'interaction-1',
        category: 'file change',
        workspaceName: 'Synvo pilot',
        reason: 'Change one bounded workspace file.',
        permissionScope: 'once',
      },
    }))

    expect(await screen.findByRole('dialog', { name: 'Review file change' })).toBeInTheDocument()
    expect(codex.api.interaction).toHaveBeenCalledWith('interaction-1', undefined)
    expect(screen.getByText('A decision is required.')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Message Synvo' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Cancel task' })).toBeEnabled()
    act(() => resolveInventory?.())
  })

  it('reconnects the owning visible conversation run after an H5 refresh', async () => {
    const operation = activeOperation()
    const codex = codexFlow({ detail: detail({ activeOperation: operation, latestOperation: operation }) })
    const conversation = conversationFlow({ activeRun: true })
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await waitFor(() => expect(conversation.api.subscribe).toHaveBeenCalledWith(
      'run-1', expect.any(Function), expect.any(Function),
    ))

    act(() => conversation.emit({
      sequence: 4,
      type: 'content_delta',
      message: null,
      delta: 'reconnected result',
      presentation: null,
      action: null,
    }))
    expect(await screen.findByText('reconnected result')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Stop' }))
    await waitFor(() => expect(conversation.api.stop).toHaveBeenCalledWith('run-1', 'csrf-token'))
  })

  it('preserves an active stream, unsent steering, focus and decision ownership across appearance changes', async () => {
    let light = false
    const listeners = new Set<() => void>()
    vi.stubGlobal('matchMedia', (query: string) => ({
      get matches() { return query.includes('prefers-color-scheme') && (query.includes(': light') ? light : !light) },
      addEventListener: (_: string, callback: () => void) => listeners.add(callback),
      removeEventListener: (_: string, callback: () => void) => listeners.delete(callback),
    }))
    const dispose = startAppearance()
    try {
      const operation = activeOperation()
      const codex = codexFlow({ detail: detail({ activeOperation: operation, latestOperation: operation }) })
      const conversation = conversationFlow({ activeRun: true, partialContent: 'The total is ' })
      renderWorkspace(codex.api, conversation.api)
      fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
      await waitFor(() => expect(conversation.api.subscribe).toHaveBeenCalledOnce())
      const draft = screen.getByRole('textbox', { name: 'Message Synvo' }) as HTMLTextAreaElement
      fireEvent.change(draft, { target: { value: 'Keep this unsent update' } })
      draft.focus()
      draft.setSelectionRange(5, 9)
      const switchAppearance = () => act(() => {
        light = !light
        listeners.forEach(callback => callback())
      })
      switchAppearance()
      expect(document.documentElement.dataset.appearance).toBe('light')
      expect(screen.getByRole('textbox', { name: 'Message Synvo' })).toBe(draft)
      expect(draft).toHaveValue('Keep this unsent update')
      expect(document.activeElement).toBe(draft)
      expect([draft.selectionStart, draft.selectionEnd]).toEqual([5, 9])
      expect(conversation.api.subscribe).toHaveBeenCalledOnce()
      expect(codex.close).not.toHaveBeenCalled()
      act(() => {
        conversation.emit({ sequence: 4, type: 'content_delta', message: null, delta: 'The total is ', presentation: null, action: null })
        conversation.emit({ sequence: 5, type: 'content_delta', message: null, delta: '42.', presentation: null, action: null })
      })
      expect(await screen.findByText('The total is 42.')).toBeInTheDocument()
      act(() => conversation.emit({ sequence: 6, type: 'action_required', message: 'Review this decision.', delta: null, presentation: null,
        action: { taskId: 'task-1', interactionId: 'interaction-1', category: 'file change', workspaceName: 'Synvo pilot', reason: 'Review a bounded change.', permissionScope: 'once' },
      }))
      const dialog = await screen.findByRole('dialog', { name: 'Review file change' })
      const pending = deferred<CodexInteraction>()
      vi.mocked(codex.api.decideInteraction).mockReturnValueOnce(pending.promise)
      switchAppearance()
      expect(screen.getByRole('dialog', { name: 'Review file change' })).toBe(dialog)
      fireEvent.click(screen.getByRole('button', { name: 'Approve once' }))
      await waitFor(() => expect(codex.api.decideInteraction).toHaveBeenCalledOnce())
      switchAppearance()
      expect(screen.getByRole('dialog', { name: 'Review file change' })).toBe(dialog)
      expect(codex.api.decideInteraction).toHaveBeenCalledOnce()
      expect(conversation.api.subscribe).toHaveBeenCalledOnce()
      expect(conversation.api.submit).not.toHaveBeenCalled()
      expect(codex.api.steer).not.toHaveBeenCalled()
      await act(async () => pending.resolve({ ...pendingInteraction(), status: 'DECIDED', decision: 'APPROVE_ONCE' }))
      expect(await screen.findByText('Approved once', { selector: '.codex-decision-receipt' })).toBeInTheDocument()
      expect(draft).toHaveValue('Keep this unsent update')
    } finally {
      dispose()
      document.documentElement.removeAttribute('data-appearance')
      document.documentElement.removeAttribute('style')
      vi.unstubAllGlobals()
    }
  })

  it('replays an active answer without duplicating its saved partial content', async () => {
    const operation = activeOperation()
    const codex = codexFlow({ detail: detail({ activeOperation: operation, latestOperation: operation }) })
    const conversation = conversationFlow({ activeRun: true, partialContent: 'The total is ' })
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await waitFor(() => expect(conversation.api.subscribe).toHaveBeenCalled())
    act(() => {
      conversation.emit({ sequence: 4, type: 'content_delta', message: null, delta: 'The total is ', presentation: null, action: null })
      conversation.emit({ sequence: 5, type: 'content_delta', message: null, delta: '42.', presentation: null, action: null })
      conversation.emit({ sequence: 6, type: 'completed', message: 'Response complete.', delta: null, presentation: null, action: null })
    })

    expect(await screen.findByText('The total is 42.')).toBeInTheDocument()
    expect(screen.queryByText('The total is The total is 42.')).not.toBeInTheDocument()
  })

  it('ignores failure from a task that is no longer selected', async () => {
    const previous = deferred<CodexTaskDetail>()
    const current = detail({ task: { ...pilotTask(), taskId: 'task-2', title: 'Current task' } })
    const codex = codexFlow()
    vi.mocked(codex.api.task).mockImplementation((id) => id === 'task-1' ? previous.promise : Promise.resolve(current))
    const { result } = renderHook(() => useCodexWorkspace({ api: codex.api }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    let pending!: ReturnType<typeof result.current.openTask>
    act(() => { pending = result.current.openTask('task-1') })
    await act(async () => { await result.current.openTask('task-2') })
    await act(async () => {
      previous.reject(new Error('Previous task is unavailable'))
      await pending
    })

    expect(result.current.taskDetail).toEqual(current)
    expect(result.current.error).toBeNull()
  })

  it('discards a delayed approval after changing the selected task', async () => {
    const approval = deferred<CodexInteraction>()
    const previous = detail({ activeOperation: activeOperation(), pendingInteractions: [pendingInteraction()] })
    const current = detail({ task: { ...pilotTask(), taskId: 'task-2' } })
    const codex = codexFlow()
    vi.mocked(codex.api.task).mockImplementation((id) => Promise.resolve(id === 'task-1' ? previous : current))
    vi.mocked(codex.api.interaction).mockReturnValue(approval.promise)
    const { result } = renderHook(() => useCodexWorkspace({ api: codex.api }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    let pending!: ReturnType<typeof result.current.openTask>
    act(() => { pending = result.current.openTask('task-1') })
    await waitFor(() => expect(codex.api.interaction).toHaveBeenCalled())
    await act(async () => { await result.current.openTask('task-2') })
    await act(async () => {
      approval.resolve(pendingInteraction())
      await pending
    })

    expect(result.current.taskDetail).toEqual(current)
    expect(result.current.interaction).toBeNull()
  })

  it('ignores a queued event from the previous task without closing the current stream', async () => {
    const previous = detail({ latestOperation: { ...activeOperation(), status: 'COMPLETED' } })
    const current = detail({
      task: { ...pilotTask(), taskId: 'task-2' },
      activeOperation: { ...activeOperation(), taskId: 'task-2', operationId: 'operation-2' },
    })
    const codex = codexFlow()
    const currentClose = vi.fn()
    vi.mocked(codex.api.task).mockImplementation((id) => Promise.resolve(id === 'task-1' ? previous : current))
    vi.mocked(codex.api.subscribe).mockImplementation((id) => ({ close: id === 'operation-2' ? currentClose : vi.fn() }))
    const { result } = renderHook(() => useCodexWorkspace({ api: codex.api }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    await act(async () => { await result.current.openTask('task-1') })
    const oldReceive = vi.mocked(codex.api.subscribe).mock.calls[0][1]
    await act(async () => { await result.current.openTask('task-2') })
    act(() => oldReceive({
      kind: 'activity', sequence: 10, type: 'TURN_COMPLETED', label: 'Previous work completed',
      text: null, truncated: false, terminalStatus: 'COMPLETED',
    }))

    expect(result.current.activity).toEqual([])
    expect(currentClose).not.toHaveBeenCalled()
  })

  it('closes a completed task activity stream instead of reconnecting it', async () => {
    const operation = activeOperation('REVIEW')
    const codex = codexFlow({ detail: detail({
      activeOperation: operation,
      latestOperation: operation,
      pendingInteractions: [pendingInteraction()],
    }) })
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await waitFor(() => expect(codex.api.subscribe).toHaveBeenCalledOnce())
    expect(await screen.findByRole('dialog', { name: 'Review file change' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Task details' }))
    fireEvent.click(screen.getByText('Edit goal').closest('summary')!)
    fireEvent.click(screen.getByText('Task actions', { selector: 'summary' }))
    expect(screen.getByRole('button', { name: 'Stop' })).toBeInTheDocument()

    act(() => codex.emit({
      kind: 'activity',
      sequence: 9,
      type: 'TURN_COMPLETED',
      label: 'Review completed',
      text: null,
      truncated: false,
      terminalStatus: 'COMPLETED',
    }))

    expect(codex.close).toHaveBeenCalledOnce()
    expect(screen.queryByText('Reconnecting to Codex activity…')).not.toBeInTheDocument()
    await waitFor(() => expect(codex.api.task).toHaveBeenCalledTimes(2))
    await waitFor(() => expect(codex.api.subscribe).toHaveBeenCalledTimes(2))
    expect(screen.queryByRole('button', { name: 'Stop' })).not.toBeInTheDocument()
    expect(screen.queryByRole('dialog', { name: 'Review file change' })).not.toBeInTheDocument()
    const taskPanel = within(screen.getByRole('complementary', { name: 'Codex task details' }))
    const currentActivity = taskPanel.getByRole('heading', { name: 'Current activity' }).closest('section')!
    const operationStatus = within(currentActivity).getByRole('status')
    expect(within(operationStatus).getByText('Review completed')).toBeInTheDocument()
    expect(within(operationStatus).getByText('Codex finished the latest work in this task.')).toBeInTheDocument()
    expect(within(operationStatus).queryByText('Codex is reviewing')).not.toBeInTheDocument()
  })
})

function renderWorkspace(codexApi: CodexApi, conversationApi: ConversationApi) {
  return render(
    <Workspace
      botConnection="connected"
      busy={false}
      onSignOut={vi.fn()}
      api={conversationApi}
      workspaceAgentApi={codexApi}
    />,
  )
}

function codexFlow(overrides: {
  tasks?: CodexTask[]
  detail?: CodexTaskDetail
  interaction?: CodexInteraction
  goal?: CodexGoal | null
} = {}) {
  let receive: ((event: CodexOperationEvent) => void) | null = null
  const close = vi.fn()
  const task = pilotTask()
  const sourceTasks = overrides.tasks ?? [task]
  const taskById = (taskId: string) => sourceTasks.find((candidate) => candidate.taskId === taskId) ?? task
  const taskDetail = overrides.detail ?? detail()
  const interaction = overrides.interaction ?? pendingInteraction()
  const api: CodexApi = {
    status: vi.fn().mockResolvedValue({
      state: 'READY',
      model: 'gpt-5.6-sol',
      runtimeVersion: '0.148.0',
      reasoningEfforts: ['medium', 'high'],
      account: {
        authentication: 'chatgpt',
        authenticationRequired: false,
        plan: 'Pro',
        usedPercent: 12,
        resetsAt: null,
      },
    }),
    workspaces: vi.fn().mockResolvedValue([
      {
        id: 'finance',
        displayName: 'Finance',
        nativeChatDefault: false,
        writeEnabled: true,
        repositoryLabel: 'Synvo Workspaces/Finance',
      },
      {
        id: 'products',
        displayName: 'Products',
        nativeChatDefault: true,
        writeEnabled: true,
        repositoryLabel: 'Synvo Workspaces/Products',
      },
      {
        id: 'sales',
        displayName: 'Sales',
        nativeChatDefault: false,
        writeEnabled: true,
        repositoryLabel: 'Synvo Workspaces/Sales',
      },
    ]),
    tasks: vi.fn().mockResolvedValue(sourceTasks),
    task: vi.fn().mockResolvedValue(taskDetail),
    createTask: vi.fn().mockResolvedValue(task),
    forkTask: vi.fn().mockResolvedValue({ ...task, taskId: 'task-2', conversationId: 'conversation-2', title: 'Fork of Pilot task' }),
    renameTask: vi.fn(async (taskId, title) => ({ ...taskById(taskId), title })),
    pinTask: vi.fn(async (taskId, enabled) => ({ ...taskById(taskId), pinned: enabled })),
    archiveTask: vi.fn(async (taskId, enabled) => ({ ...taskById(taskId), archived: enabled })),
    changeMode: vi.fn(async (taskId, mode) => ({ ...taskById(taskId), mode })),
    deleteTask: vi.fn().mockResolvedValue(undefined),
    stopOperation: vi.fn().mockResolvedValue({ stopped: true }),
    steer: vi.fn().mockResolvedValue(undefined),
    interaction: vi.fn().mockResolvedValue(interaction),
    decideInteraction: vi.fn().mockResolvedValue({ ...interaction, status: 'DECIDED', decision: 'APPROVE_ONCE' }),
    inventory: vi.fn().mockResolvedValue({
      skills: [{ name: 'test-skill', description: 'Runs the configured focused test workflow.' }],
      mcpServers: [{ name: 'fixture', authenticationStatus: 'ready', tools: ['read_fixture'] }],
    }),
    goal: vi.fn().mockResolvedValue(overrides.goal ?? null),
    setGoal: vi.fn().mockResolvedValue(undefined),
    clearGoal: vi.fn().mockResolvedValue(undefined),
    review: vi.fn().mockResolvedValue(activeOperation('REVIEW')),
    csrfToken: vi.fn().mockResolvedValue('csrf-token'),
    subscribe: vi.fn((_operationId, onEvent) => {
      receive = onEvent
      return { close }
    }),
  }
  return { api, emit: (event: CodexOperationEvent) => receive?.(event), close }
}

function conversationFlow(options: { activeRun?: boolean; partialContent?: string } = {}) {
  let receive: ((event: ConversationStreamEvent) => void) | null = null
  const run: ConversationRun = {
    requestId: 'request-1',
    conversationId: 'conversation-1',
    runId: 'run-1',
    userTurnId: 'user-1',
    assistantTurnId: 'assistant-1',
    intent: 'DIRECT_ANSWER',
    status: 'RUNNING',
    replayed: false,
  }
  const api: ConversationApi = {
    list: vi.fn().mockResolvedValue([]),
    get: vi.fn().mockResolvedValue({
      conversationId: 'conversation-1',
      title: 'Pilot task',
      updatedAt: '2026-08-21T12:00:00Z',
      turns: options.activeRun ? [{
        turnId: 'assistant-1',
        role: 'ASSISTANT',
        content: options.partialContent ?? '',
        status: options.partialContent ? 'STREAMING' : 'PENDING',
        createdAt: '2026-08-21T12:00:00Z',
        updatedAt: '2026-08-21T12:00:00Z',
      }] : [],
      activeRun: options.activeRun ? {
        requestId: run.requestId,
        conversationId: run.conversationId,
        runId: run.runId,
        userTurnId: run.userTurnId,
        assistantTurnId: run.assistantTurnId,
        intent: run.intent,
        status: run.status,
      } : null,
    }),
    csrfToken: vi.fn().mockResolvedValue('csrf-token'),
    remove: vi.fn().mockResolvedValue(undefined),
    submit: vi.fn().mockResolvedValue(run),
    stop: vi.fn().mockResolvedValue({ stopped: true, status: 'RUNNING' }),
    subscribe: vi.fn((_runId, onEvent) => {
      receive = onEvent
      return { close: vi.fn() }
    }),
  }
  return { api, emit: (event: ConversationStreamEvent) => receive?.(event) }
}

function pilotTask(): CodexTask {
  return {
    taskId: 'task-1',
    conversationId: 'conversation-1',
    title: 'Pilot task',
    workspaceId: 'pilot',
    workspaceName: 'Synvo pilot',
    mode: 'WORKSPACE_WRITE',
    pinned: false,
    archived: false,
    createdAt: '2026-08-21T12:00:00Z',
    updatedAt: '2026-08-21T12:00:00Z',
  }
}

function detail(overrides: Partial<CodexTaskDetail> = {}): CodexTaskDetail {
  return {
    task: pilotTask(),
    activeOperation: null,
    latestOperation: null,
    pendingInteractions: [],
    ...overrides,
  }
}

function activeOperation(type: 'TURN' | 'REVIEW' = 'TURN') {
  return {
    operationId: 'operation-1',
    taskId: 'task-1',
    type,
    status: 'RUNNING' as const,
    createdAt: '2026-08-21T12:00:00Z',
    updatedAt: '2026-08-21T12:00:00Z',
  }
}

function deferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void
  let reject!: (reason?: unknown) => void
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise
    reject = rejectPromise
  })
  return { promise, resolve, reject }
}

function pendingInteraction(): CodexInteraction {
  return {
    interactionId: 'interaction-1',
    taskId: 'task-1',
    operationId: 'operation-1',
    workspaceId: 'pilot',
    workspaceName: 'Synvo pilot',
    kind: 'FILE_CHANGE_APPROVAL',
    category: 'file change',
    reason: 'Change one bounded workspace file.',
    permissionScope: 'once',
    availableDecisions: ['APPROVE_ONCE', 'DECLINE', 'CANCEL'],
    status: 'PENDING',
    decision: null,
    expiresAt: '2099-08-21T13:00:00Z',
    detail: {
      command: null,
      workingDirectory: null,
      affectedPaths: ['src/codex/CodexWorkspace.test.tsx'],
      mcpServer: null,
      mcpTool: null,
      message: null,
      inputMode: null,
      elicitationUrl: null,
      fields: [],
    },
  }
}
