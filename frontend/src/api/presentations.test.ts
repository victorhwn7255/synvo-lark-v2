import { describe, expect, it } from 'vitest'
import { isSafeLarkUrl, isWorkflowPresentation } from './presentations'

describe('workflow presentation contracts', () => {
  it.each([
    {
      id: 'activity-1',
      kind: 'activity',
      label: 'Reading configured sources',
      status: 'running',
      operationLabel: 'Lark Drive read',
    },
    {
      id: 'citation-1',
      kind: 'citation',
      sourceName: 'Launch plan',
      url: 'https://synvo.larksuite.com/docx/launch-plan',
      excerpt: 'Launch criteria and current status.',
    },
    {
      id: 'source-1',
      kind: 'source',
      sourceName: 'Risk tracker',
      sourceType: 'sheet',
      summary: 'Risks inside the configured folder.',
      url: 'https://synvo.larksuite.com/sheets/risk-tracker',
    },
    {
      id: 'artifact-1',
      kind: 'artifact',
      title: 'Launch readiness',
      artifactType: 'research',
      status: 'partial',
      summary: 'A partial, reviewable synthesis.',
      sections: [{ heading: 'Blockers', body: 'Two blockers need owners.' }],
    },
    {
      id: 'confirmation-1',
      kind: 'confirmation',
      title: 'Review proposed tasks',
      description: 'Review before any future write is enabled.',
      operationLabel: 'Create Lark tasks',
      confirmLabel: 'Confirm tasks',
      status: 'review_required',
    },
    { id: 'unavailable-1', kind: 'unavailable', title: 'Research unavailable', description: 'Available in a later phase.' },
    { id: 'error-1', kind: 'error', title: 'Source unavailable', message: 'Try again later.' },
  ])('accepts a valid $kind contract', (presentation) => {
    expect(isWorkflowPresentation(presentation)).toBe(true)
  })

  it('covers every artifact lifecycle state without accepting malformed sections', () => {
    for (const status of ['loading', 'partial', 'completed', 'failed', 'unavailable']) {
      expect(isWorkflowPresentation({
        id: `artifact-${status}`,
        kind: 'artifact',
        title: 'Execution plan',
        artifactType: 'execution_plan',
        status,
        summary: 'A safe workflow summary.',
        sections: [],
      })).toBe(true)
    }

    expect(isWorkflowPresentation({
      id: 'artifact-invalid',
      kind: 'artifact',
      title: 'Execution plan',
      artifactType: 'execution_plan',
      status: 'completed',
      summary: 'Invalid section data.',
      sections: [{ heading: '', body: 'Hidden' }],
    })).toBe(false)
  })

  it('allows only HTTPS Lark and Feishu resource links', () => {
    expect(isSafeLarkUrl('https://synvo.larksuite.com/docx/a')).toBe(true)
    expect(isSafeLarkUrl('https://example.feishu.cn/wiki/a')).toBe(true)
    expect(isSafeLarkUrl('http://synvo.larksuite.com/docx/a')).toBe(false)
    expect(isSafeLarkUrl('https://larksuite.com.attacker.example/docx/a')).toBe(false)
    expect(isSafeLarkUrl('javascript:alert(1)')).toBe(false)
  })
})
