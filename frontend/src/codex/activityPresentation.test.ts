import { describe, expect, it } from 'vitest'
import type { CodexActivity } from '../api/codex'
import { projectAgentActivity } from './activityPresentation'

const event = (sequence: number, type: string, terminalStatus: CodexActivity['terminalStatus'] = null): CodexActivity => ({
  kind: 'activity', sequence, type, terminalStatus, label: type, text: null, truncated: false,
})
const base = { active: true, operationStatus: 'RUNNING' as const, reconnecting: false, interaction: null, activity: [] }

describe('agent activity presentation', () => {
  it('lets authoritative terminal failures override waiting, stopping, and reconnecting', () => {
    const view = projectAgentActivity({ ...base, operationStatus: 'WAITING_FOR_INTERACTION', reconnecting: true,
      phase: 'stopping', activity: [event(9, 'TURN_COMPLETED', 'TIMEOUT'), event(1, 'COMMAND_STARTED')] })
    expect(view.title).toBe('Timed out')
    expect(view.streaming).toBe(false)
    expect(view.tools[0].state).toBe('Interrupted')
  })

  it('presents alternating phases without inventing a fixed plan', () => {
    expect(projectAgentActivity({ ...base, phase: 'accepted' }).title).toBe('Starting…')
    expect(projectAgentActivity({ ...base, phase: 'thinking' }).title).toBe('Analyzing your request…')
    expect(projectAgentActivity({ ...base, phase: 'tool_running', activity: [event(1, 'MCP_STARTED')] }).title).toBe('Using a connected tool')
    expect(projectAgentActivity({ ...base, phase: 'streaming', activity: [event(1, 'MCP_COMPLETED')] }).streaming).toBe(true)
    expect(projectAgentActivity({ ...base, phase: 'stopping', reconnecting: true }).title).toBe('Stopping…')
    expect(projectAgentActivity({ ...base, phase: 'streaming', reconnecting: true }).streaming).toBe(false)
    expect(projectAgentActivity({ ...base, phase: 'streaming', operationStatus: 'WAITING_FOR_INTERACTION' }).title).toBe('Action required')
    expect(projectAgentActivity({ ...base, activity: [event(1, 'UNKNOWN_TOOL_STARTED')] }).tools).toEqual([])
    expect(projectAgentActivity({ ...base, phase: 'thinking', activity: [event(1, 'COMMAND_STARTED')] }).title).toBe('Working in the workspace')
    expect(projectAgentActivity(base).title).toBe('Working…')
  })

  it('removes stale action-required state after the owning acknowledgement while a new unacknowledged decision still needs action', () => {
    const input = { ...base, operationStatus: 'WAITING_FOR_INTERACTION' as const, phase: 'action_required' as const, interactionAcknowledged: true }
    expect(projectAgentActivity(input).title).toBe('Working…')
    expect(projectAgentActivity(input).streaming).toBe(false)
    expect(projectAgentActivity({ ...input, interactionAcknowledged: false }).title).toBe('Action required')
  })

  it('deduplicates and orders sequences while retaining every unfinished category', () => {
    const view = projectAgentActivity({ ...base, activity: [event(3, 'FILE_CHANGE_STARTED'), event(1, 'COMMAND_STARTED'), event(2, 'MCP_STARTED'), event(1, 'COMMAND_STARTED')] })
    expect(view.tools.map(({ label, started, state }) => [label, started, state])).toEqual([
      ['Workspace commands', 1, 'In progress'], ['Connected tools', 1, 'In progress'], ['File operations', 1, 'In progress'],
    ])
    expect(view.steps.filter(({ id }) => id.startsWith('workspace')).every(({ status }) => status !== 'completed')).toBe(true)
  })

  it('does not claim tool success from completion or unmatched starts at terminal', () => {
    const view = projectAgentActivity({ ...base, activity: [event(1, 'COMMAND_COMPLETED'), event(2, 'MCP_STARTED'), event(3, 'TURN_COMPLETED', 'COMPLETED')] })
    expect(view.tools.map(({ state }) => state)).toEqual(['Activity finished', 'Unconfirmed'])
    expect(view.tools[0]).toMatchObject({ started: 0, finished: 1 })
    expect(view.steps.find(({ id }) => id === 'workspace-work')?.status).toBe('unconfirmed')
  })
})
