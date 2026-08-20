import type { WorkflowPresentation } from '../api/presentations'
import { WorkflowPresentationList } from '../components/WorkflowPresentation'
import { ArtifactIcon, CloseIcon } from './visuals'

export function ArtifactPanel({
  presentations,
  onClose,
}: {
  presentations: WorkflowPresentation[]
  onClose: () => void
}) {
  return (
    <aside id="artifact-panel" className="workspace-artifact-panel" aria-label="Artifacts">
      <div className="workspace-artifact-panel__header">
        <div>
          <p>Conversation context</p>
          <h2>Artifacts</h2>
        </div>
        <button
          className="workspace-icon-button"
          type="button"
          aria-label="Close artifact panel"
          onClick={onClose}
        >
          <CloseIcon />
        </button>
      </div>
      {presentations.length === 0 ? (
        <div className="workspace-artifact-panel__empty">
          <ArtifactIcon />
          <h3>No artifacts yet</h3>
          <p>Cited research and reviewable execution plans will appear here when their workflows are available.</p>
        </div>
      ) : (
        <WorkflowPresentationList presentations={presentations} />
      )}
    </aside>
  )
}
