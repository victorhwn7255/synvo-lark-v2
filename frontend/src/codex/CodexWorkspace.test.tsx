import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
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

describe('CodexWorkspace', () => {
  afterEach(() => {
    cleanup()
    window.history.replaceState(null, '', '/')
    vi.restoreAllMocks()
  })

  it('creates a configured task before enabling free-form conversation options', async () => {
    const codex = codexFlow({ tasks: [] })
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    expect(await screen.findByRole('heading', { name: 'Create a New Task' })).toBeInTheDocument()
    const taskSetup = screen.getByRole('region', { name: 'Create a New Task' })
    expect(within(taskSetup).getByRole('img', { name: 'Synvo with Codex' })).toBeInTheDocument()
    expect(within(taskSetup).queryByText('Codex in Lark')).not.toBeInTheDocument()
    expect(within(taskSetup).getByText('Select a folder directory and access mode for this task.')).toBeInTheDocument()
    const workspaceSelect = screen.getByRole('combobox', { name: 'Workspace' })
    expect(workspaceSelect).toHaveClass('codex-task-setup__select')
    expect(workspaceSelect.closest('.codex-task-setup__select-wrap')?.querySelector('svg')).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Synvo Workspaces/Finance/' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Synvo Workspaces/Products/' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Synvo Workspaces/Sales/' })).toBeInTheDocument()
    await waitFor(() => expect(workspaceSelect).toHaveValue('products'))
    expect(screen.getByText('gpt-5.6-sol · App Server 0.148.0')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('radio', { name: /Full Edit/ }))
    fireEvent.change(screen.getByRole('textbox', { name: /Task title/ }), {
      target: { value: 'Fix the pilot build' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Create task' }))

    await waitFor(() => expect(codex.api.createTask).toHaveBeenCalledWith({
      workspaceId: 'products',
      mode: 'WORKSPACE_WRITE',
      title: 'Fix the pilot build',
    }, 'csrf-token'))
    expect(await screen.findByRole('textbox', { name: 'Message Synvo' })).toBeEnabled()

    fireEvent.change(screen.getByRole('combobox', { name: 'Reasoning' }), { target: { value: 'high' } })
    fireEvent.change(screen.getByRole('combobox', { name: 'Skill' }), { target: { value: 'test-skill' } })
    fireEvent.change(screen.getByRole('textbox', { name: 'Message Synvo' }), {
      target: { value: 'Run the focused frontend tests' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))

    await waitFor(() => expect(conversation.api.submit).toHaveBeenCalledWith(
      expect.objectContaining({
        conversationId: 'conversation-1',
        content: 'Run the focused frontend tests',
        reasoningEffort: 'high',
        skillName: 'test-skill',
      }),
      'csrf-token',
    ))
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
    expect(screen.getByRole('button', { name: 'Approve once' })).toHaveFocus()
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

    const timeline = within(assistant).getByRole('region', { name: 'Agent activity' })
    expect(within(timeline).getByText('Codex is working')).toBeInTheDocument()
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
    expect(within(timeline).getByText(/Safe technical details/)).toHaveTextContent('2 normalized events summarized.')
  })

  it('supports task management, goals, review, steering, and stop through owning APIs', async () => {
    let currentDetail = detail()
    const codex = codexFlow({ detail: currentDetail })
    const conversation = conversationFlow()
    renderWorkspace(codex.api, conversation.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Pilot task' }))
    await screen.findByRole('textbox', { name: 'Message Synvo' })
    fireEvent.click(screen.getByRole('button', { name: 'Task details' }))

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

    fireEvent.click(screen.getByRole('button', { name: 'Start review' }))
    await waitFor(() => expect(codex.api.review).toHaveBeenCalledWith(
      'task-1', 'UNCOMMITTED_CHANGES', null, 'csrf-token',
    ))

    currentDetail = detail({ activeOperation: activeOperation(), latestOperation: activeOperation() })
    vi.mocked(codex.api.task).mockResolvedValue(currentDetail)
    fireEvent.change(screen.getByRole('textbox', { name: 'Message Synvo' }), {
      target: { value: 'Continue the implementation' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))
    await waitFor(() => expect(conversation.api.submit).toHaveBeenCalledOnce())
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Steer active work' })).toBeInTheDocument())
    fireEvent.change(screen.getByRole('textbox', { name: 'Steer active work' }), {
      target: { value: 'Run typecheck before finishing' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send steering' }))
    await waitFor(() => expect(codex.api.steer).toHaveBeenCalledWith(
      'operation-1', 'Run typecheck before finishing', 'csrf-token',
    ))
    fireEvent.click(screen.getByRole('button', { name: 'Stop current work' }))
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

    const taskPanel = within(screen.getByRole('complementary', { name: 'Codex task details' }))
    expect(taskPanel.getByRole('heading', { name: 'Current activity' })).toBeInTheDocument()
    expect(taskPanel.getByText('Codex is working')).toBeInTheDocument()
    expect(taskPanel.getByText('You can send an update below or stop the current work.')).toBeInTheDocument()
    expect(screen.queryByText('turn · running')).not.toBeInTheDocument()
    expect(taskPanel.getByText('Instructions sent during this task')).toBeInTheDocument()
    expect(taskPanel.getByText('No steering instructions have been sent in this H5 session.')).toBeInTheDocument()

    const steeringInput = screen.getByRole('textbox', { name: 'Steer active work' })
    fireEvent.change(steeringInput, { target: { value: 'Add the requested owners section' } })
    fireEvent.click(screen.getByRole('button', { name: 'Send steering' }))

    expect(await screen.findByRole('button', { name: 'Sending…' })).toBeDisabled()
    expect(screen.getByText('Sending your update…')).toBeInTheDocument()
    expect(codex.api.steer).toHaveBeenCalledOnce()

    steering.resolve()
    expect(await screen.findByText('Steering sent')).toBeInTheDocument()
    expect(screen.getByText('Codex accepted your update and will apply it to the current task.')).toBeInTheDocument()
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
    fireEvent.click(screen.getByRole('button', { name: 'Send steering' }))

    const steeringFailure = await within(screen.getByRole('complementary', { name: 'Codex task details' }))
      .findByText('Steering wasn’t sent')
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
    fireEvent.change(screen.getByRole('textbox', { name: 'Steer active work' }), {
      target: { value: 'Add a concise risk summary' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send steering' }))

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
    expect(screen.getByText('Waiting for your approval in H5…')).toBeInTheDocument()
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

    fireEvent.click(screen.getByRole('button', { name: 'Stop response' }))
    await waitFor(() => expect(conversation.api.stop).toHaveBeenCalledWith('run-1', 'csrf-token'))
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
    expect(screen.getByRole('button', { name: 'Stop current work' })).toBeInTheDocument()

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
    expect(screen.queryByRole('button', { name: 'Stop current work' })).not.toBeInTheDocument()
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
    decideInteraction: vi.fn().mockResolvedValue({ ...interaction, status: 'RESOLVED', decision: 'APPROVE_ONCE' }),
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

function conversationFlow(options: { activeRun?: boolean } = {}) {
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
        content: '',
        status: 'PENDING',
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
