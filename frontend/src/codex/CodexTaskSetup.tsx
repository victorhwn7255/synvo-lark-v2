import { useEffect, useState, type FormEvent } from 'react'
import type { CodexRunMode, CodexStatus, CodexWorkspace } from '../api/codex'
import { SynvoLogo } from '../workspace/visuals'
import codexLogo from '../../assets/codex.png'

export function CodexTaskSetup({
  status,
  workspaces,
  submitting,
  error,
  onCreate,
}: {
  status: CodexStatus | null
  workspaces: CodexWorkspace[]
  submitting: boolean
  error: string | null
  onCreate: (workspaceId: string, mode: CodexRunMode, title?: string) => Promise<void>
}) {
  const [workspaceId, setWorkspaceId] = useState('')
  const [mode, setMode] = useState<CodexRunMode>('READ_ONLY')
  const [title, setTitle] = useState('')
  const selectedWorkspace = workspaces.find(({ id }) => id === workspaceId) ?? null

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
    if (workspaceId) void onCreate(workspaceId, mode, title || undefined)
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
      <h2 id="codex-task-setup-title">Create a New Task</h2>
      <p>Select a folder directory and access mode for this task.</p>

      <div className="codex-runtime-status" data-state={status?.state.toLowerCase() ?? 'loading'} role="status">
        <strong>{runtimeLabel(status)}</strong>
        {status?.model && <span>{status.model} · App Server {status.runtimeVersion}</span>}
      </div>

      {error && <div className="codex-inline-error" role="alert">{error}</div>}

      <form onSubmit={submit}>
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
              <span><strong>Full Edit</strong><small>Edit files and run commands inside this folder automatically. Access outside stays blocked.</small></span>
            </label>
          </div>
        </fieldset>
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
        <button type="submit" disabled={!ready || !workspaceId || submitting}>
          {submitting ? 'Creating task…' : 'Create task'}
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
    case 'AUTHENTICATION_REQUIRED': return 'Codex login is required on the runner host'
    case 'DISABLED': return 'Codex is disabled in this environment'
    case 'UNAVAILABLE': return 'Codex is temporarily unavailable'
    default: return 'Checking Codex…'
  }
}
