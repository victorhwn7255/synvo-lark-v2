import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type {
  ConversationApi,
  ConversationDetail,
  ConversationRun,
  ConversationStreamEvent,
  ConversationSummary,
} from '../api/conversations'
import type { WorkflowPresentation } from '../api/presentations'
import { Workspace } from './Workspace'

const run: ConversationRun = {
  requestId: 'request-1',
  conversationId: 'conversation-1',
  runId: 'run-1',
  userTurnId: 'user-turn-1',
  assistantTurnId: 'assistant-turn-1',
  intent: 'DIRECT_ANSWER',
  status: 'RUNNING',
  replayed: false,
}

describe('Workspace', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('renders the approved workspace shell without fake workflow controls or duplicate identity', () => {
    const flow = conversationFlow()
    renderWorkspace(flow.api)

    expect(screen.getByRole('complementary', { name: 'Synvo navigation' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'New conversation' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Enterprise Research/ })).toBeDisabled()
    expect(screen.getByRole('button', { name: /Meeting to Execution/ })).toBeDisabled()
    expect(screen.getByRole('textbox', { name: 'Message Synvo' })).toBeEnabled()
    expect(screen.getByRole('button', { name: 'Send message' })).toBeDisabled()
    expect(screen.getAllByRole('button', { name: /sidebar/i })).toHaveLength(1)
    expect(document.querySelector('.workspace-topbar__folder')).toBeInTheDocument()
    expect(document.querySelector('.workspace-sidebar__brand img')?.getAttribute('src')).toContain('logo.jpg')
    expect(screen.queryByRole('searchbox')).not.toBeInTheDocument()
    expect(screen.queryByText('Synvo AI Assistant')).not.toBeInTheDocument()
    expect(screen.queryByText('Victor')).not.toBeInTheDocument()
  })

  it('collapses and expands the sidebar using native buttons', () => {
    renderWorkspace(conversationFlow().api)
    const workspace = screen.getByRole('main', { name: 'Synvo AI workspace' })
    expect(screen.getByRole('heading', { name: 'Workflows' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Recent' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Hide sidebar' }))
    expect(workspace).toHaveAttribute('data-sidebar-collapsed', 'true')
    expect(screen.getAllByRole('button', { name: /sidebar/i })).toHaveLength(1)
    expect(screen.queryByRole('heading', { name: 'Workflows' })).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Recent' })).not.toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Workflows' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Recent' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Expand sidebar' }))
    expect(workspace).toHaveAttribute('data-sidebar-collapsed', 'false')
    expect(screen.getAllByRole('button', { name: /sidebar/i })).toHaveLength(1)
    expect(screen.getByRole('heading', { name: 'Workflows' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Recent' })).toBeInTheDocument()
  })

  it('loads and opens persisted recent conversations', async () => {
    const recents: ConversationSummary[] = [
      { conversationId: 'conversation-1', title: 'Launch strategy research', updatedAt: '2026-08-18T01:00:00Z' },
      { conversationId: 'conversation-2', title: 'Monday product meeting', updatedAt: '2026-08-18T02:00:00Z' },
    ]
    const detail: ConversationDetail = {
      ...recents[1],
      activeRun: null,
      turns: [
        turn('user-2', 'USER', 'What did we decide?', 'COMPLETED'),
        turn('assistant-2', 'ASSISTANT', 'The persisted answer.', 'COMPLETED'),
      ],
    }
    const flow = conversationFlow({
      list: vi.fn().mockResolvedValue(recents),
      get: vi.fn().mockResolvedValue(detail),
    })
    renderWorkspace(flow.api)

    expect(await screen.findByRole('button', { name: 'Monday product meeting' })).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Monday product meeting' }))
    expect(await screen.findByText('The persisted answer.')).toBeInTheDocument()
    expect(flow.api.get).toHaveBeenCalledWith('conversation-2')

    const activeConversation = screen.getByRole('button', { name: 'Monday product meeting' })
    const inactiveConversation = screen.getByRole('button', { name: 'Launch strategy research' })
    expect(activeConversation).toHaveAttribute('aria-current', 'page')
    expect(activeConversation.closest('.workspace-recent-item')).toHaveAttribute('data-active', 'true')
    expect(inactiveConversation.closest('.workspace-recent-item')).toHaveAttribute('data-active', 'false')

    fireEvent.click(screen.getByRole('button', { name: 'Hide sidebar' }))
    expect(screen.getByLabelText('Synvo AI workspace')).toHaveAttribute('data-sidebar-collapsed', 'true')
    expect(activeConversation.closest('.workspace-recent-item')).toHaveAttribute('data-active', 'true')

    fireEvent.click(screen.getByRole('button', { name: 'New conversation' }))
    expect(activeConversation).not.toHaveAttribute('aria-current')
    expect(activeConversation.closest('.workspace-recent-item')).toHaveAttribute('data-active', 'false')
  })

  it('confirms and permanently deletes a recent chat without opening it accidentally', async () => {
    const recent: ConversationSummary = {
      conversationId: 'conversation-2',
      title: 'Monday product meeting',
      updatedAt: '2026-08-18T02:00:00Z',
    }
    const remove = vi.fn().mockResolvedValue(undefined)
    const flow = conversationFlow({
      list: vi.fn().mockResolvedValue([recent]),
      get: vi.fn().mockResolvedValue({ ...recent, turns: [], activeRun: null }),
      remove,
    })
    renderWorkspace(flow.api)

    const deleteButton = await screen.findByRole('button', {
      name: 'Delete chat “Monday product meeting”',
    })
    fireEvent.click(deleteButton)
    expect(flow.api.get).not.toHaveBeenCalled()
    expect(screen.getByRole('alertdialog', { name: 'Delete this chat?' })).toHaveTextContent(
      'This action cannot be undone.',
    )

    fireEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    expect(remove).not.toHaveBeenCalled()
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    await waitFor(() => expect(deleteButton).toHaveFocus())

    fireEvent.click(screen.getByRole('button', { name: 'Monday product meeting' }))
    await waitFor(() => expect(flow.api.get).toHaveBeenCalledWith('conversation-2'))
    const confirmedDeleteButton = screen.getByRole('button', { name: 'Delete chat “Monday product meeting”' })
    const recentRow = confirmedDeleteButton.closest('.workspace-recent-item')
    fireEvent.click(confirmedDeleteButton)
    fireEvent.click(screen.getByRole('button', { name: 'Delete chat' }))

    await waitFor(() => expect(remove).toHaveBeenCalledWith('conversation-2', 'csrf-token'))
    expect(screen.queryByRole('alertdialog')).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'New conversation' })).toBeInTheDocument()
    expect(recentRow).toHaveAttribute('data-deleting', 'true')
    fireEvent.animationEnd(recentRow as HTMLElement)
    await waitFor(() => expect(
      screen.queryByRole('button', { name: 'Monday product meeting' }),
    ).not.toBeInTheDocument())
    await waitFor(() => expect(screen.getByRole('button', { name: 'New conversation' })).toHaveFocus())
  })

  it('keeps the chat and confirmation open when deletion fails safely', async () => {
    const recent: ConversationSummary = {
      conversationId: 'conversation-2',
      title: 'Protected conversation',
      updatedAt: '2026-08-18T02:00:00Z',
    }
    const flow = conversationFlow({
      list: vi.fn().mockResolvedValue([recent]),
      remove: vi.fn().mockRejectedValue(new Error('Stop the active response before deleting this chat.')),
    })
    renderWorkspace(flow.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Delete chat “Protected conversation”' }))
    fireEvent.click(screen.getByRole('button', { name: 'Delete chat' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'Stop the active response before deleting this chat.',
    )
    expect(screen.getByRole('button', { name: 'Protected conversation' })).toBeInTheDocument()
    expect(screen.getByRole('alertdialog')).toBeInTheDocument()
  })

  it('copies a completed assistant response and exposes its completion actions', async () => {
    const clipboard = { writeText: vi.fn().mockResolvedValue(undefined) }
    vi.stubGlobal('navigator', { ...window.navigator, clipboard })
    const recent: ConversationSummary = {
      conversationId: 'conversation-2',
      title: 'Monday product meeting',
      updatedAt: '2026-08-18T02:00:00Z',
    }
    const flow = conversationFlow({
      list: vi.fn().mockResolvedValue([recent]),
      get: vi.fn().mockResolvedValue({
        ...recent,
        activeRun: null,
        turns: [turn('assistant-2', 'ASSISTANT', '1. **Keep the Markdown.**', 'COMPLETED')],
      }),
    })
    const { container } = renderWorkspace(flow.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Monday product meeting' }))
    await screen.findByText('Keep the Markdown.')

    fireEvent.click(screen.getByRole('button', { name: 'Copy response' }))
    await waitFor(() => expect(clipboard.writeText).toHaveBeenCalledWith('1. **Keep the Markdown.**'))
    expect(screen.getByRole('button', { name: 'Response copied' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Branch in new chat — coming soon' })).toBeDisabled()
    expect(container.querySelector('time')).toHaveAttribute('datetime', '2026-08-18T01:00:00Z')
    expect(container.querySelector('time')).toHaveTextContent('09:00')
    expect(container.querySelector('time')).toHaveAttribute('title', '18 Aug 2026, 09:00 SGT')
  })

  it('grows the composer with its content and caps its height', () => {
    renderWorkspace(conversationFlow().api)
    const composer = screen.getByRole('textbox', { name: 'Message Synvo' })

    Object.defineProperty(composer, 'scrollHeight', { configurable: true, value: 92 })
    fireEvent.change(composer, { target: { value: 'A longer prompt that wraps across several lines.' } })
    expect(composer).toHaveStyle({ height: '92px', overflowY: 'hidden' })

    Object.defineProperty(composer, 'scrollHeight', { configurable: true, value: 280 })
    fireEvent.change(composer, { target: { value: 'A very long prompt that needs the capped composer height.' } })
    expect(composer).toHaveStyle({ height: '200px', overflowY: 'auto' })

    Object.defineProperty(composer, 'scrollHeight', { configurable: true, value: 24 })
    fireEvent.change(composer, { target: { value: 'Short again' } })
    expect(composer).toHaveStyle({ height: '44px', overflowY: 'hidden' })
  })

  it('reveals every new prompt and follows streaming only while the user stays near the bottom', async () => {
    const flow = conversationFlow()
    const { container } = renderWorkspace(flow.api)
    const viewport = container.querySelector('.workspace-conversation__stream') as HTMLDivElement
    Object.defineProperties(viewport, {
      scrollHeight: { configurable: true, value: 1_200 },
      clientHeight: { configurable: true, value: 400 },
      scrollTop: { configurable: true, writable: true, value: 100 },
    })

    fireEvent.scroll(viewport)
    submitPrompt('Keep this new prompt visible above the composer')
    expect(viewport.scrollTop).toBe(800)
    await waitFor(() => expect(flow.api.subscribe).toHaveBeenCalledOnce())

    viewport.scrollTop = 200
    fireEvent.scroll(viewport)
    act(() => flow.emit(streamEvent(4, 'content_delta', null, 'First streamed section')))
    expect(viewport.scrollTop).toBe(200)

    viewport.scrollTop = 720
    fireEvent.scroll(viewport)
    act(() => flow.emit(streamEvent(5, 'content_delta', null, ' followed by more content')))
    expect(viewport.scrollTop).toBe(800)
  })

  it('does not show response actions for pending, streaming, or failed assistant turns', async () => {
    const recent: ConversationSummary = {
      conversationId: 'conversation-2',
      title: 'Incomplete response states',
      updatedAt: '2026-08-18T02:00:00Z',
    }
    const flow = conversationFlow({
      list: vi.fn().mockResolvedValue([recent]),
      get: vi.fn().mockResolvedValue({
        ...recent,
        activeRun: null,
        turns: [
          turn('streaming', 'ASSISTANT', 'Still responding', 'STREAMING'),
          turn('failed', 'ASSISTANT', 'Could not complete', 'FAILED'),
        ],
      }),
    })
    renderWorkspace(flow.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Incomplete response states' }))
    await screen.findByText('Still responding')

    expect(screen.queryByRole('button', { name: 'Copy response' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Branch in new chat — coming soon' })).not.toBeInTheDocument()
  })

  it('renders one immediate pending assistant turn and streams ordered deltas into it', async () => {
    const pending = deferred<ConversationRun>()
    const flow = conversationFlow({ submit: vi.fn().mockReturnValue(pending.promise) })
    renderWorkspace(flow.api, vi.fn(), 'https://example.com/victor.png')

    fireEvent.change(screen.getByRole('textbox', { name: 'Message Synvo' }), {
      target: { value: 'Hello Synvo' },
    })
    fireEvent.click(screen.getByRole('button', { name: 'Send message' }))

    expect(screen.getByText('Hello Synvo')).toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(2)
    expect(screen.getByRole('article', { name: 'Your message' })).toBeInTheDocument()
    expect(screen.getByRole('article', { name: 'Synvo response' })).toBeInTheDocument()
    expect(document.querySelector('.workspace-turn__label')).not.toBeInTheDocument()
    const userAvatar = screen.getByRole('article', { name: 'Your message' }).querySelector('img')
    const synvoAvatar = screen.getByRole('article', { name: 'Synvo response' }).querySelector('img')
    expect(userAvatar).toHaveAttribute('src', 'https://example.com/victor.png')
    expect(synvoAvatar?.getAttribute('src')).toContain('logo.jpg')
    fireEvent.error(userAvatar as HTMLImageElement)
    expect(userAvatar?.getAttribute('src')).toContain('user.png')
    expect(screen.getByRole('status', { name: 'Starting…' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Message Synvo' })).toBeDisabled()

    await act(async () => pending.resolve(run))
    await waitFor(() => expect(flow.api.subscribe).toHaveBeenCalledWith(
      'run-1', expect.any(Function), expect.any(Function),
    ))

    act(() => flow.emit(streamEvent(4, 'content_delta', null, 'Hello')))
    expect(screen.getByText('Hello')).toBeInTheDocument()
    expect(screen.queryByRole('status', { name: 'Starting…' })).not.toBeInTheDocument()

    act(() => flow.emit(streamEvent(5, 'content_delta', null, ', Victor.')))
    expect(screen.getByText('Hello, Victor.')).toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(2)

    act(() => flow.emit(streamEvent(6, 'completed', 'Response complete.', null)))
    await waitFor(() => expect(screen.getByRole('textbox', { name: 'Message Synvo' })).toBeEnabled())
    expect(screen.getByText('Hello, Victor.')).toBeInTheDocument()
  })

  it('uses the local default avatar when Lark has no user profile picture', async () => {
    const flow = conversationFlow()
    renderWorkspace(flow.api)

    submitPrompt('Hello without a profile image')

    const userAvatar = screen.getByRole('article', { name: 'Your message' }).querySelector('img')
    expect(userAvatar?.getAttribute('src')).toContain('user.png')
    await waitFor(() => expect(flow.api.subscribe).toHaveBeenCalledOnce())
  })

  it('reconnects the same stream, supports stop, and reaches a safe failed state', async () => {
    const flow = conversationFlow()
    renderWorkspace(flow.api)
    submitPrompt('Please write a long answer')
    await waitFor(() => expect(flow.api.subscribe).toHaveBeenCalledOnce())

    act(() => flow.connectionError())
    expect(screen.getByText('Reconnecting to the response…')).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Stop response' }))
    await waitFor(() => expect(flow.api.stop).toHaveBeenCalledWith('run-1', 'csrf-token'))

    act(() => flow.emit(streamEvent(4, 'failed', 'The response was stopped.', null)))
    expect(screen.getByText('The response was stopped.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Message Synvo' })).toBeEnabled()
  })

  it('replaces a failed turn in place when retrying', async () => {
    const submit = vi.fn()
      .mockResolvedValueOnce(run)
      .mockResolvedValueOnce({
        ...run,
        requestId: 'request-2',
        runId: 'run-2',
        userTurnId: 'user-turn-2',
        assistantTurnId: 'assistant-turn-2',
      })
    const flow = conversationFlow({ submit })
    renderWorkspace(flow.api)
    submitPrompt('Try this request')
    await waitFor(() => expect(submit).toHaveBeenCalledOnce())

    act(() => flow.emit(streamEvent(3, 'failed', 'Synvo could not complete that response.', null)))
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }))

    await waitFor(() => expect(submit).toHaveBeenCalledTimes(2))
    expect(submit.mock.calls[1]?.[0]).toMatchObject({
      content: 'Try this request',
      replaceFailedAssistantTurnId: 'assistant-turn-1',
    })
    expect(screen.getAllByText('Try this request')).toHaveLength(1)
    expect(screen.queryByText('Synvo could not complete that response.')).not.toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(2)

    act(() => flow.emit(streamEvent(4, 'content_delta', null, 'Discarded partial response')))
    act(() => flow.emit(streamEvent(5, 'content_reset', null, null)))
    expect(screen.queryByText('Discarded partial response')).not.toBeInTheDocument()
    act(() => flow.emit(streamEvent(6, 'content_delta', null, 'Replacement response')))
    act(() => flow.emit(streamEvent(7, 'completed', 'Response complete.', null)))

    expect(screen.getByText('Replacement response')).toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(2)
  })

  it('prevents rapid duplicate submission before React state settles', async () => {
    const pending = deferred<ConversationRun>()
    const submit = vi.fn().mockReturnValue(pending.promise)
    const flow = conversationFlow({ submit })
    renderWorkspace(flow.api)

    const composer = screen.getByRole('textbox', { name: 'Message Synvo' })
    fireEvent.change(composer, { target: { value: 'Only once' } })
    fireEvent.keyDown(composer, { key: 'Enter' })
    fireEvent.keyDown(composer, { key: 'Enter' })

    await waitFor(() => expect(submit).toHaveBeenCalledOnce())
    await act(async () => pending.resolve(run))
  })

  it('renders workflow presentation only after a validated backend stream event', async () => {
    const flow = conversationFlow()
    renderWorkspace(flow.api)

    fireEvent.click(screen.getByRole('button', { name: 'Artifacts' }))
    expect(screen.getByText('No artifacts yet')).toBeInTheDocument()

    submitPrompt('Prepare a research view')
    await waitFor(() => expect(flow.api.subscribe).toHaveBeenCalledOnce())
    act(() => flow.emit(streamEvent(3, 'tool_running', 'Reading a configured source', null, {
      id: 'source-1',
      kind: 'source',
      sourceName: 'Launch plan',
      sourceType: 'document',
      summary: 'A source inside the configured folder.',
      url: 'https://synvo.larksuite.com/docx/launch-plan',
    })))

    expect(screen.getByRole('heading', { name: 'Launch plan' })).toBeInTheDocument()
    expect(screen.queryByText('No artifacts yet')).not.toBeInTheDocument()
  })

  it('opens and closes the empty artifact shell without fabricating output', () => {
    renderWorkspace(conversationFlow().api)

    fireEvent.click(screen.getByRole('button', { name: 'Artifacts' }))
    expect(screen.getByRole('complementary', { name: 'Artifacts' })).toHaveTextContent('No artifacts yet')
    expect(screen.getByText(/will appear here when their workflows are available/i)).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Close artifact panel' }))
    expect(screen.queryByRole('complementary', { name: 'Artifacts' })).not.toBeInTheDocument()
  })

  it('keeps sources, permissions, and disconnect behavior inside one Settings entry', () => {
    const onSignOut = vi.fn()
    renderWorkspace(conversationFlow().api, onSignOut)

    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))

    expect(screen.getByRole('heading', { name: 'Sources and permissions' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Knowledge Sources' })).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Lark Permissions' })).toBeInTheDocument()
    expect(screen.getByText('One configured Drive folder')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Disconnect Synvo' }))
    expect(onSignOut).toHaveBeenCalledOnce()
  })

  it('returns from Settings to the previously selected conversation without reloading it', async () => {
    const recent: ConversationSummary = {
      conversationId: 'conversation-2',
      title: 'Monday product meeting',
      updatedAt: '2026-08-18T02:00:00Z',
    }
    const flow = conversationFlow({
      list: vi.fn().mockResolvedValue([recent]),
      get: vi.fn().mockResolvedValue({
        ...recent,
        activeRun: null,
        turns: [turn('assistant-2', 'ASSISTANT', 'The persisted answer.', 'COMPLETED')],
      }),
    })
    renderWorkspace(flow.api)

    fireEvent.click(await screen.findByRole('button', { name: 'Monday product meeting' }))
    expect(await screen.findByText('The persisted answer.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))

    const backButton = screen.getByRole('button', { name: 'Back to conversation' })
    expect(backButton).toHaveAttribute('title', 'Back to Monday product meeting')
    fireEvent.click(backButton)

    expect(screen.getByText('The persisted answer.')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Monday product meeting' })).toBeInTheDocument()
    expect(flow.api.get).toHaveBeenCalledOnce()
  })

  it('shows connection health only when attention is required', () => {
    const flow = conversationFlow()
    const { container, rerender } = render(
      <Workspace botConnection="connected" busy={false} onSignOut={vi.fn()} api={flow.api} />,
    )
    expect(screen.queryByText(/assistant channel is/i)).not.toBeInTheDocument()
    expect(container.querySelector('.workspace-settings-availability')).toHaveAttribute(
      'data-state',
      'connected',
    )
    expect(screen.getByText('Synvo AI Assistant is connected and ready.')).toBeInTheDocument()

    rerender(<Workspace botConnection="reconnecting" busy={false} onSignOut={vi.fn()} api={flow.api} />)
    expect(screen.getByText(/reconnecting automatically/i)).toBeInTheDocument()
    expect(container.querySelector('.workspace-settings-availability')).toHaveAttribute(
      'data-state',
      'disconnected',
    )
    expect(screen.getByText('Synvo AI Assistant is not connected.')).toBeInTheDocument()
  })

  it('starts compact and preserves real recents at a mobile-sized viewport preference', async () => {
    vi.stubGlobal('matchMedia', vi.fn().mockReturnValue({ matches: true }))
    const recent = { conversationId: 'conversation-1', title: 'Launch strategy research', updatedAt: '2026-08-18T01:00:00Z' }
    const flow = conversationFlow({ list: vi.fn().mockResolvedValue([recent]) })
    renderWorkspace(flow.api)

    const workspace = screen.getByRole('main', { name: 'Synvo AI workspace' })
    expect(workspace).toHaveAttribute('data-sidebar-collapsed', 'true')
    expect(await screen.findByRole('button', { name: 'Launch strategy research' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Delete chat “Launch strategy research”' })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Expand sidebar' }))
    expect(workspace).toHaveAttribute('data-sidebar-collapsed', 'false')
    expect(screen.getByRole('button', { name: 'Delete chat “Launch strategy research”' })).toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: 'Settings' }))
    expect(workspace).toHaveAttribute('data-sidebar-collapsed', 'true')
    expect(screen.getByRole('heading', { name: 'Sources and permissions' })).toBeInTheDocument()
  })
})

function renderWorkspace(
  api: ConversationApi,
  onSignOut = vi.fn(),
  userAvatarUrl: string | null = null,
) {
  return render(
    <Workspace
      botConnection="connected"
      busy={false}
      userAvatarUrl={userAvatarUrl}
      onSignOut={onSignOut}
      api={api}
    />,
  )
}

function submitPrompt(content: string) {
  fireEvent.change(screen.getByRole('textbox', { name: 'Message Synvo' }), {
    target: { value: content },
  })
  fireEvent.click(screen.getByRole('button', { name: 'Send message' }))
}

function conversationFlow(overrides: Partial<ConversationApi> = {}) {
  let receive: ((event: ConversationStreamEvent) => void) | undefined
  let connectionFailed: (() => void) | undefined
  const api: ConversationApi = {
    list: vi.fn().mockResolvedValue([]),
    get: vi.fn(),
    csrfToken: vi.fn().mockResolvedValue('csrf-token'),
    remove: vi.fn().mockResolvedValue(undefined),
    submit: vi.fn().mockResolvedValue(run),
    stop: vi.fn().mockResolvedValue({ stopped: true, status: 'RUNNING' }),
    subscribe: vi.fn((_runId, onEvent, onConnectionError) => {
      receive = onEvent
      connectionFailed = onConnectionError
      return { close: vi.fn() }
    }),
    ...overrides,
  }
  return {
    api,
    emit: (event: ConversationStreamEvent) => receive?.(event),
    connectionError: () => connectionFailed?.(),
  }
}

function streamEvent(
  sequence: number,
  type: ConversationStreamEvent['type'],
  message: string | null,
  delta: string | null,
  presentation: WorkflowPresentation | null = null,
): ConversationStreamEvent {
  return { sequence, type, message, delta, presentation, action: null }
}

function turn(
  turnId: string,
  role: 'USER' | 'ASSISTANT',
  content: string,
  status: 'PENDING' | 'STREAMING' | 'COMPLETED' | 'FAILED',
) {
  return {
    turnId,
    role,
    content,
    status,
    createdAt: '2026-08-18T01:00:00Z',
    updatedAt: '2026-08-18T01:00:00Z',
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((complete) => { resolve = complete })
  return { promise, resolve }
}
