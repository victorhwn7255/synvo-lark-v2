import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import type { WorkflowPresentation } from '../api/presentations'
import { WorkflowPresentationList } from './WorkflowPresentation'

describe('WorkflowPresentationList', () => {
  afterEach(cleanup)

  it('renders activity, citations, sources, unavailable, and error states accessibly', () => {
    render(<WorkflowPresentationList presentations={[
      presentation({ id: 'activity', kind: 'activity', label: 'Reading configured sources', status: 'running', operationLabel: 'Lark Drive read' }),
      presentation({ id: 'citation', kind: 'citation', sourceName: 'Launch plan', url: 'https://synvo.larksuite.com/docx/launch-plan', excerpt: 'Launch criteria.' }),
      presentation({ id: 'source', kind: 'source', sourceName: 'Risk tracker', sourceType: 'sheet', summary: 'The active project risks.', url: 'https://synvo.larksuite.com/sheets/risks' }),
      presentation({ id: 'unavailable', kind: 'unavailable', title: 'Research unavailable', description: 'Available in a later phase.' }),
      presentation({ id: 'error', kind: 'error', title: 'Source unavailable', message: 'Try again later.' }),
    ]} />)

    expect(screen.getByRole('heading', { name: 'Reading configured sources' })).toBeInTheDocument()
    expect(screen.getByText('Lark Drive read')).toBeInTheDocument()
    expect(screen.getByRole('heading', { name: 'Launch plan' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Open Launch plan in Lark' })).toHaveAttribute(
      'href',
      'https://synvo.larksuite.com/docx/launch-plan',
    )
    expect(screen.getByRole('link', { name: 'Open Risk tracker in Lark' })).toHaveAttribute(
      'rel',
      'noreferrer',
    )
    expect(screen.getByRole('status')).toHaveTextContent('Research unavailable')
    expect(screen.getByRole('alert')).toHaveTextContent('Source unavailable')
    expect(screen.queryByText(/chain.of.thought|private reasoning/i)).not.toBeInTheDocument()
  })

  it.each(['loading', 'partial', 'completed', 'failed', 'unavailable'] as const)(
    'renders the %s artifact state',
    (status) => {
      render(<WorkflowPresentationList presentations={[
        presentation({
          id: `artifact-${status}`,
          kind: 'artifact',
          title: 'Launch readiness',
          artifactType: 'research',
          status,
          summary: 'Reviewable synthesis.',
          sections: [{ heading: 'Blockers', body: 'Two items need owners.' }],
        }),
      ]} />)

      expect(screen.getByRole('heading', { name: 'Launch readiness' })).toBeInTheDocument()
      expect(screen.getByText(status)).toBeInTheDocument()
      expect(screen.getByRole('heading', { name: 'Blockers' })).toBeInTheDocument()
    },
  )

  it('keeps confirmation review-only with no executable control', () => {
    render(<WorkflowPresentationList presentations={[
      presentation({
        id: 'confirmation',
        kind: 'confirmation',
        title: 'Review proposed tasks',
        description: 'Check the plan before a later workflow enables writes.',
        operationLabel: 'Create Lark tasks',
        confirmLabel: 'Confirm tasks',
        status: 'review_required',
      }),
    ]} />)

    expect(screen.getByRole('button', { name: 'Confirm tasks' })).toBeDisabled()
    expect(screen.getByText(/No Lark action can run/i)).toBeInTheDocument()
  })
})

function presentation(value: WorkflowPresentation): WorkflowPresentation {
  return value
}
