import type { CodexSkill } from '../api/codex'

export function CodexComposerControls({
  reasoningEfforts,
  reasoningEffort,
  skills,
  skillName,
  disabled,
  onReasoningEffortChange,
  onSkillNameChange,
}: {
  reasoningEfforts: string[]
  reasoningEffort: string
  skills: CodexSkill[]
  skillName: string
  disabled: boolean
  onReasoningEffortChange: (value: string) => void
  onSkillNameChange: (value: string) => void
}) {
  return (
    <div className="codex-composer-controls" aria-label="Codex turn options">
      <label>
        <span>Reasoning</span>
        <span className="codex-composer-select-wrap">
          <select
            value={reasoningEffort}
            disabled={disabled || reasoningEfforts.length === 0}
            onChange={(event) => onReasoningEffortChange(event.target.value)}
          >
            {reasoningEfforts.map((effort) => <option key={effort} value={effort}>{label(effort)}</option>)}
          </select>
          <SelectChevronIcon />
        </span>
      </label>
      <label>
        <span>Skill</span>
        <span className="codex-composer-select-wrap">
          <select value={skillName} disabled={disabled} onChange={(event) => onSkillNameChange(event.target.value)}>
            <option value="">Automatic</option>
            {skills.map((skill) => <option key={skill.name} value={skill.name}>{skill.name}</option>)}
          </select>
          <SelectChevronIcon />
        </span>
      </label>
    </div>
  )
}

function label(value: string) {
  return value.replaceAll('_', ' ').replace(/^./, (character) => character.toUpperCase())
}

function SelectChevronIcon() {
  return <svg viewBox="0 0 20 20" fill="none" aria-hidden="true"><path d="m5 7.5 5 5 5-5" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" /></svg>
}
