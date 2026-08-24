import { cleanup, fireEvent, render, screen, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { CodexActivity, CodexInteraction } from '../api/codex'
import { CodexActivityTimeline } from './CodexActivityTimeline'

describe('CodexActivityTimeline', () => {
  afterEach(cleanup)

  it('projects ordered normalized events into compact semantic milestones', () => {
    render(
      <CodexActivityTimeline
        active
        operationStatus="RUNNING"
        reconnecting={false}
        interaction={null}
        activity={[
          event(7, 'COMMAND_STARTED', 'Running a workspace command'),
          event(1, 'TURN_STARTED', 'Codex started'),
          event(4, 'MESSAGE_DELTA', 'Writing the result', 'duplicated response fragment'),
          event(3, 'PLAN_DELTA', 'Planning the task', 'Inspect the repository structure.'),
          event(2, 'PLAN_STARTED', 'Planning the task'),
          event(5, 'REASONING_STARTED', 'Reasoning about the task'),
          event(6, 'REASONING_DELTA', 'Reasoning about the task', 'Checking the project boundaries.'),
          event(8, 'COMMAND_OUTPUT', 'Command produced output', 'unrestricted command output'),
        ]}
      />,
    )

    const timeline = screen.getByRole('region', { name: 'Agent activity' })
    expect(within(timeline).getByText('Codex is working')).toBeInTheDocument()
    expect(within(timeline).getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      expect.stringContaining('Task started'),
      expect.stringContaining('Analyzing the taskChecking the project boundaries.'),
      expect.stringContaining('Working in the workspace1 command'),
    ])
    expect(within(timeline).getAllByRole('listitem')[0]).toHaveAttribute('data-status', 'completed')
    expect(within(timeline).queryByText(/duplicated response fragment/)).not.toBeInTheDocument()
    expect(within(timeline).queryByText(/unrestricted command output/)).not.toBeInTheDocument()
    expect(within(timeline).getByText('8 normalized events summarized. Safe technical details remain available in Task details.')).toBeInTheDocument()
    expect(within(timeline).getByText('Activity and model-provided summaries—not private chain-of-thought.')).toBeInTheDocument()
  })

  it('makes an H5 approval request the current actionable step without exposing detail', () => {
    render(
      <CodexActivityTimeline
        active
        operationStatus="WAITING_FOR_INTERACTION"
        reconnecting={false}
        interaction={pendingInteraction()}
        activity={[
          event(1, 'TURN_STARTED', 'Codex started'),
          event(2, 'COMMAND_STARTED', 'Running a workspace command'),
        ]}
      />,
    )

    const timeline = screen.getByRole('region', { name: 'Agent activity' })
    expect(within(timeline).getByText('Needs your approval')).toBeInTheDocument()
    expect(within(timeline).getByText('Review file change')).toBeInTheDocument()
    expect(within(timeline).getByText('Change one bounded workspace file.')).toBeInTheDocument()
    expect(within(timeline).getByText('Review in the approval panel')).toBeInTheDocument()
  })

  it('shows a delivered steering update in yellow and marks it complete with the turn', () => {
    const { rerender } = render(
      <CodexActivityTimeline
        active
        operationStatus="RUNNING"
        reconnecting={false}
        interaction={null}
        activity={[event(1, 'TURN_STARTED', 'Codex started')]}
        steeringStatus="delivered"
      />,
    )

    const delivered = screen.getByText('Instructions updated').closest('li')
    expect(delivered).toHaveAttribute('data-step', 'steering-update')
    expect(delivered).toHaveAttribute('data-status', 'steering')
    expect(screen.getByText('Your steering update was delivered to Codex.')).toBeInTheDocument()

    rerender(
      <CodexActivityTimeline
        active={false}
        operationStatus="COMPLETED"
        reconnecting={false}
        interaction={null}
        activity={[
          event(1, 'TURN_STARTED', 'Codex started'),
          event(2, 'TURN_COMPLETED', 'Codex task finished', null, 'COMPLETED'),
        ]}
        steeringStatus="completed"
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Show agent activity' }))
    expect(screen.getByText('Instructions updated').closest('li')).toHaveAttribute('data-status', 'completed')
  })

  it('collapses completed work into a reopenable step summary', () => {
    render(
      <CodexActivityTimeline
        active={false}
        operationStatus="COMPLETED"
        reconnecting={false}
        interaction={null}
        activity={[
          event(1, 'TURN_STARTED', 'Codex started'),
          event(2, 'COMMAND_STARTED', 'Running a workspace command'),
          event(3, 'COMMAND_COMPLETED', 'Workspace command completed'),
          event(4, 'TURN_COMPLETED', 'Codex task finished', null, 'COMPLETED'),
        ]}
      />,
    )

    const toggle = screen.getByRole('button', { name: 'Show agent activity' })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(screen.getByText('Completed · 3 milestones')).toBeInTheDocument()
    expect(screen.queryByRole('list')).not.toBeInTheDocument()

    fireEvent.click(toggle)

    expect(screen.getByRole('button', { name: 'Hide agent activity' })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
    expect(screen.getByText('Completed')).toBeInTheDocument()
  })

  it('shows a terminal milestone from persisted operation status during an activity replay race', () => {
    render(
      <CodexActivityTimeline
        active={false}
        operationStatus="COMPLETED"
        reconnecting={false}
        interaction={null}
        activity={[event(1, 'TURN_STARTED', 'Codex started')]}
      />,
    )

    expect(screen.getByText('Completed · 2 milestones')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Show agent activity' }))
    expect(screen.getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      'Task started',
      'Completed',
    ])
  })

  it('condenses a repetitive 56-event run without hiding approvals or workspace changes', () => {
    const activity: CodexActivity[] = [event(1, 'TURN_STARTED', 'Codex started')]
    let sequence = 2
    for (let cycle = 0; cycle < 9; cycle += 1) {
      activity.push(event(sequence++, 'REASONING_STARTED', 'Reasoning about the task'))
      activity.push(event(sequence++, 'REASONING_COMPLETED', 'Reasoning completed', `Analyzed source group ${cycle + 1}.`))
      activity.push(event(sequence++, 'COMMAND_STARTED', 'Running a workspace command'))
      activity.push(event(sequence++, 'INTERACTION_RESOLVED', 'Decision applied'))
      activity.push(event(sequence++, 'COMMAND_COMPLETED', 'Workspace command completed'))
    }
    activity.push(event(sequence++, 'FILE_CHANGE_STARTED', 'Preparing workspace changes'))
    activity.push(event(sequence++, 'INTERACTION_RESOLVED', 'Decision applied'))
    activity.push(event(sequence++, 'FILE_CHANGE_COMPLETED', 'Workspace change completed'))
    activity.push(event(sequence++, 'MCP_STARTED', 'Calling an MCP tool'))
    activity.push(event(sequence++, 'MCP_PROGRESS', 'Calling an MCP tool'))
    activity.push(event(sequence++, 'MCP_COMPLETED', 'MCP tool completed'))
    activity.push(event(sequence++, 'COMPACTED', 'Context compacted'))
    activity.push(event(sequence++, 'REASONING_STARTED', 'Reasoning about the task'))
    activity.push(event(sequence++, 'REASONING_COMPLETED', 'Reasoning completed', 'Prepared the final result.'))
    activity.push(event(sequence, 'TURN_COMPLETED', 'Codex task finished', null, 'COMPLETED'))

    expect(activity).toHaveLength(56)
    render(
      <CodexActivityTimeline
        active={false}
        operationStatus="COMPLETED"
        reconnecting={false}
        interaction={null}
        activity={activity}
      />,
    )

    fireEvent.click(screen.getByRole('button', { name: 'Show agent activity' }))
    const timeline = screen.getByRole('region', { name: 'Agent activity' })
    const milestones = within(timeline).getAllByRole('listitem')
    expect(milestones).toHaveLength(6)
    expect(within(timeline).getByText('Analysis completed')).toBeInTheDocument()
    expect(within(timeline).getByText('Workspace work completed')).toBeInTheDocument()
    expect(within(timeline).getByText('9 commands · 1 MCP tool')).toBeInTheDocument()
    expect(within(timeline).getByText('Workspace files updated')).toBeInTheDocument()
    expect(within(timeline).getByText('1 file operation')).toBeInTheDocument()
    expect(within(timeline).getByText('10 approvals resolved')).toBeInTheDocument()
    expect(within(timeline).getByText('56 normalized events summarized. Safe technical details remain available in Task details.')).toBeInTheDocument()
    expect(within(timeline).queryByText('Reasoning completed')).not.toBeInTheDocument()
    expect(within(timeline).queryByText('Decision applied')).not.toBeInTheDocument()
    expect(within(timeline).queryByText('Workspace command completed')).not.toBeInTheDocument()
  })

  it('keeps failures expanded so the terminal outcome cannot be overlooked', () => {
    render(
      <CodexActivityTimeline
        active={false}
        operationStatus="FAILED"
        reconnecting={false}
        interaction={null}
        activity={[
          event(1, 'TURN_STARTED', 'Codex started'),
          event(2, 'COMMAND_STARTED', 'Running a workspace command'),
          event(3, 'TURN_COMPLETED', 'Codex task finished', null, 'ENGINE_UNAVAILABLE'),
        ]}
      />,
    )

    expect(screen.getByRole('button', { name: 'Hide agent activity' })).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getAllByText('Codex unavailable')).toHaveLength(2)
  })
})

function event(
  sequence: number,
  type: string,
  label: string,
  text: string | null = null,
  terminalStatus: CodexActivity['terminalStatus'] = null,
): CodexActivity {
  return { kind: 'activity', sequence, type, label, text, truncated: false, terminalStatus }
}

function pendingInteraction(): CodexInteraction {
  return {
    interactionId: 'interaction-1',
    taskId: 'task-1',
    operationId: 'operation-1',
    workspaceId: 'pilot',
    workspaceName: 'Pilot workspace',
    kind: 'FILE_CHANGE_APPROVAL',
    category: 'file change',
    reason: 'Change one bounded workspace file.',
    permissionScope: 'once',
    availableDecisions: ['APPROVE_ONCE', 'DECLINE'],
    status: 'PENDING',
    decision: null,
    expiresAt: '2026-08-21T17:00:00Z',
    detail: {
      command: null,
      workingDirectory: null,
      affectedPaths: [],
      mcpServer: null,
      mcpTool: null,
      message: null,
      inputMode: null,
      elicitationUrl: null,
      fields: [],
    },
  }
}
