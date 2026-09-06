import { useEffect, useState, type FormEvent } from 'react'
import type { CodexRunMode, CodexStatus, CodexWorkspace } from '../api/codex'
import { SynvoLogo } from '../workspace/visuals'
import codexLogo from '../../assets/codex.png'

export function CodexTaskSetup({
  status,
  defaultReasoningEffort,
  workspaces,
  submitting,
  error,
  onCreate,
  creationUncertain = false,
  retainedRequest = '',
  onReviewTasks,
  onConfirmRetry,
}: {
  status: CodexStatus | null
  defaultReasoningEffort: string
  workspaces: CodexWorkspace[]
  submitting: boolean
  error: string | null
  onCreate: (workspaceId: string, mode: CodexRunMode, request: string, title?: string) => Promise<void>
  creationUncertain?: boolean
  retainedRequest?: string
  onReviewTasks?: () => void
  onConfirmRetry?: () => void
}) {
  const [workspaceId, setWorkspaceId] = useState('')
  const [mode, setMode] = useState<CodexRunMode>('READ_ONLY')
  const [title, setTitle] = useState('')
  const [request, setRequest] = useState(retainedRequest)
  const selectedWorkspace = workspaces.find(({ id }) => id === workspaceId) ?? null
  const defaultsAvailable = Boolean(status?.model && defaultReasoningEffort)
  const effortLabel = defaultReasoningEffort.replaceAll('_', ' ').replace(/^./, (character) => character.toUpperCase())

  useEffect(() => {
    if (!workspaceId && workspaces[0]) {
      const preferredWorkspace = workspaces.find(({ nativeChatDefault }) => nativeChatDefault)
      setWorkspaceId((preferredWorkspace ?? workspaces[0]).id)
    }
  }, [workspaceId, workspaces])

  useEffect(() => {
    if (mode === 'WORKSPACE_WRITE' && selectedWorkspace && !selectedWorkspace.writeEnabled) {
      setMode('READ_ONLY')
    }
  }, [mode, selectedWorkspace])

  const submit = (event: FormEvent) => {
    event.preventDefault()
    if (!submitting && !creationUncertain && status?.state === 'READY' && defaultsAvailable && selectedWorkspace && request.trim()) {
      void onCreate(workspaceId, mode, request.trim(), title || undefined).catch(() => {})
    }
  }

  const ready = status?.state === 'READY'
  return (
    <section className="codex-task-setup" aria-labelledby="codex-task-setup-title">
      <div className="codex-task-setup__identity" role="img" aria-label="Synvo with Codex">
        <span className="codex-task-setup__brand">
          <SynvoLogo large />
          <span>Synvo</span>
        </span>
        <span className="codex-task-setup__connector" aria-hidden="true">+</span>
        <span className="codex-task-setup__brand">
          <img className="codex-task-setup__codex-logo" src={codexLogo} alt="" />
          <span>Codex</span>
        </span>
      </div>
      <h2 id="codex-task-setup-title">What would you like to work on?</h2>
      <div className="codex-runtime-status" data-state={status?.state.toLowerCase() ?? 'loading'} role="status">
        <strong>{runtimeLabel(status)}</strong>
        {ready && <span className="codex-runtime-status__defaults">
          <span>{status.model === 'gpt-5.6-sol' ? 'GPT-5.6 Sol' : status.model || 'Model unavailable'}</span>
          <span>{effortLabel ? `${effortLabel} effort` : 'Effort unavailable'}</span>
        </span>}
        {ready && defaultReasoningEffort && defaultReasoningEffort !== 'high' && (
          <small className="codex-runtime-status__fallback">High effort unavailable; using {effortLabel}.</small>
        )}
      </div>

      {error && <div className="codex-inline-error" role="alert">{error}</div>}

      {creationUncertain && <div className="codex-start-recovery" role="status">
        <p>Could not confirm task creation. Check the refreshed task list before trying again.</p>
        <button type="button" onClick={onReviewTasks}>Review tasks</button>
        <button type="button" onClick={onConfirmRetry}>I checked — allow another creation</button>
      </div>}
      <form onSubmit={submit} aria-label="Start a task">
        <label>
          <span>Task title <small>optional</small></span>
          <input
            value={title}
            maxLength={160}
            disabled={!ready || submitting}
            placeholder="New Codex task"
            onChange={(event) => setTitle(event.target.value)}
          />
        </label>
        <label className="codex-task-setup__request">
          <span>Your request</span>
          <textarea value={request} rows={3} maxLength={20_000} required disabled={submitting}
            placeholder="Summarize this month’s sales and highlight what changed."
            onChange={(event) => setRequest(event.target.value)} />
        </label>
        <label>
          <span>Workspace</span>
          <span className="codex-task-setup__select-wrap">
            <select
              className="codex-task-setup__select"
              value={workspaceId}
              disabled={!ready || submitting || workspaces.length === 0}
              onChange={(event) => setWorkspaceId(event.target.value)}
            >
              {workspaces.map((workspace) => (
                <option key={workspace.id} value={workspace.id}>{workspaceOptionLabel(workspace)}</option>
              ))}
            </select>
            <SelectChevronIcon />
          </span>
        </label>
        <fieldset disabled={!ready || submitting || !workspaceId}>
          <legend>Access mode</legend>
          <div className="codex-task-setup__access-options">
            <label data-selected={mode === 'READ_ONLY'}>
              <input
                type="radio"
                name="codex-mode"
                value="READ_ONLY"
                checked={mode === 'READ_ONLY'}
                onChange={() => setMode('READ_ONLY')}
              />
              <span><strong>Read Only</strong><small>Inspect and analyze without changing files.</small></span>
            </label>
            <label data-selected={mode === 'WORKSPACE_WRITE'}>
              <input
                type="radio"
                name="codex-mode"
                value="WORKSPACE_WRITE"
                checked={mode === 'WORKSPACE_WRITE'}
                disabled={!selectedWorkspace?.writeEnabled}
                onChange={() => setMode('WORKSPACE_WRITE')}
              />
              <span><strong>Edit workspace files</strong><small>Permitted edits and commands stay inside this workspace. External access remains blocked.</small></span>
            </label>
          </div>
        </fieldset>
        <button type="submit" disabled={!ready || !defaultsAvailable || !selectedWorkspace || !request.trim() || submitting || creationUncertain}>
          {submitting ? 'Starting task…' : 'Start task'}
        </button>
      </form>
    </section>
  )
}

function workspaceOptionLabel(workspace: CodexWorkspace) {
  return workspace.repositoryLabel
    ? `${workspace.repositoryLabel.replace(/\/$/, '')}/`
    : workspace.displayName
}

function SelectChevronIcon() {
  return (
    <svg viewBox="0 0 20 20" fill="none" aria-hidden="true">
      <path d="m5.5 7.75 4.5 4.5 4.5-4.5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  )
}

function runtimeLabel(status: CodexStatus | null) {
  switch (status?.state) {
    case 'READY': return 'Codex is ready'
    case 'RECOVERING': return 'Codex is reconnecting automatically…'
    case 'AUTHENTICATION_REQUIRED': return 'Codex login is required on the runner host'
    case 'DISABLED': return 'Codex is disabled in this environment'
    case 'PROTOCOL_INCOMPATIBLE': return 'The pinned Codex runtime is incompatible'
    case 'UNAVAILABLE': return 'Codex is temporarily unavailable'
    default: return 'Checking Codex…'
  }
}
