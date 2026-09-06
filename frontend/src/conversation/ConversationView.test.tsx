import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ConversationView } from './ConversationView'
import type { ConversationTurn } from '../api/conversations'
import type { ActiveRun, RunPhase } from './useConversation'

const run: ActiveRun = { requestId: 'request', runId: 'run', assistantTurnId: 'answer', phase: 'streaming' }
const answer = (content: string, status: ConversationTurn['status'] = 'STREAMING'): ConversationTurn => ({ turnId: 'answer', role: 'ASSISTANT', content, status, createdAt: '2026-09-05T12:00:00Z', updatedAt: '2026-09-05T12:00:10Z' })
const props = { userAvatarUrl: null, composerValue: '', loading: false, error: null, onComposerChange: vi.fn(), onSubmit: vi.fn(), onStop: vi.fn(), onRetry: vi.fn() }

afterEach(() => { cleanup(); vi.restoreAllMocks() })
describe('streaming presentation', () => {
  it('keeps the growing response mounted and its decorative cue separate from Markdown and copied content', async () => {
    const copy = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: copy } })
    const { container, rerender } = render(<ConversationView {...props} turns={[answer('Hello ')]} activeRun={run} />)
    const block = container.querySelector('.workspace-turn__content')
    const cursor = container.querySelector('.workspace-streaming-cursor')
    expect(cursor).toHaveAttribute('aria-hidden', 'true')
    expect(container.querySelector('.workspace-conversation__stream')).not.toHaveAttribute('aria-live')
    const content = 'Hello 世界 👋\n\n| Item | Count |\n| --- | ---: |\n| Sample | 2 |\n\n```text\n  exact  spaces\n```\n\n[Source](https://example.com)'
    rerender(<ConversationView {...props} turns={[answer(content)]} activeRun={run} />)
    expect(container.querySelector('.workspace-turn__content')).toBe(block)
    expect(container.querySelector('.workspace-streaming-cursor')).toBe(cursor)
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Source' })).toHaveAttribute('href', 'https://example.com')
    rerender(<ConversationView {...props} turns={[answer(content, 'COMPLETED')]} activeRun={null} />)
    expect(container.querySelector('.workspace-streaming-cursor')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Copy response' }))
    await waitFor(() => expect(copy).toHaveBeenCalledExactlyOnceWith(content))
  })

  it.each(['thinking', 'tool_running', 'action_required', 'reconnecting', 'stopping'] satisfies RunPhase[])('removes the cursor while %s without discarding the received answer', (phase) => {
    const { container } = render(<ConversationView {...props} turns={[answer('Received fragment')]} activeRun={{ ...run, phase }} />)
    expect(screen.getByText('Received fragment')).toBeInTheDocument()
    expect(container.querySelector('.workspace-streaming-cursor')).toBeNull()
  })

  it('lets the owning activity suppress stale streaming and handles content reset/replay', () => {
    const { container, rerender } = render(<ConversationView {...props} turns={[answer('Partial')]} activeRun={run} responseStreaming={false} activityPresentation={<div>Action required</div>} />)
    expect(container.querySelector('.workspace-streaming-cursor')).toBeNull()
    expect(container.querySelectorAll('[role="status"]')).toHaveLength(0)
    rerender(<ConversationView {...props} turns={[answer('', 'PENDING')]} activeRun={{ ...run, phase: 'thinking' }} />)
    expect(screen.queryByText('Partial')).not.toBeInTheDocument()
    expect(container.querySelector('.workspace-streaming-cursor')).toBeNull()
    rerender(<ConversationView {...props} turns={[answer('Saved response', 'COMPLETED')]} activeRun={null} />)
    expect(container.querySelector('.workspace-turn__content')).toHaveAttribute('data-live', 'false')
    expect(container.querySelector('.workspace-streaming-cursor')).toBeNull()
  })
})
