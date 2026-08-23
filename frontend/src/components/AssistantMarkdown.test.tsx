import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { AssistantMarkdown } from './AssistantMarkdown'

describe('AssistantMarkdown', () => {
  afterEach(cleanup)

  it('renders assistant structure without displaying raw Markdown syntax', () => {
    render(
      <AssistantMarkdown>{`Natural language provides:

1. **Improved accessibility**: Less training is required.
2. **Faster completion**: Work takes fewer steps.

Additional qualities:

- Clear output

Read the [source](https://example.com/source).`}</AssistantMarkdown>,
    )

    expect(screen.getAllByRole('list').map((list) => list.tagName)).toEqual(['OL', 'UL'])
    expect(screen.getAllByRole('listitem')).toHaveLength(3)
    expect(screen.getByText('Improved accessibility').tagName).toBe('STRONG')
    expect(screen.getByRole('link', { name: 'source' })).toHaveAttribute('target', '_blank')
    expect(screen.queryByText(/\*\*Improved/)).not.toBeInTheDocument()
  })

  it('drops raw HTML, images, and non-HTTP links from model output', () => {
    render(
      <AssistantMarkdown>{`<script>unsafe()</script>

![tracker](https://example.com/tracker.png)

[Unsafe](javascript:alert(1))`}</AssistantMarkdown>,
    )

    expect(screen.queryByText('unsafe()')).not.toBeInTheDocument()
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: 'Unsafe' })).not.toBeInTheDocument()
    expect(screen.getByText('Unsafe')).toBeInTheDocument()
  })

  it('preserves intentional soft line breaks in plain-text results', () => {
    const { container } = render(
      <AssistantMarkdown>{`Workspace entries:
AGENTS.md
backend
frontend`}</AssistantMarkdown>,
    )

    expect(container.querySelector('p')).toHaveTextContent('Workspace entries:AGENTS.mdbackendfrontend')
    expect(container.querySelectorAll('p > br')).toHaveLength(3)
  })

  it('presents a bare line-delimited inventory as a compact result list', () => {
    render(
      <AssistantMarkdown>{`.env
.env.example
AGENTS.md
backend
frontend`}</AssistantMarkdown>,
    )

    const list = screen.getByRole('list', { name: 'Result entries' })
    expect(list).toHaveClass('assistant-compact-list')
    expect(screen.getAllByRole('listitem').map((item) => item.textContent)).toEqual([
      '.env',
      '.env.example',
      'AGENTS.md',
      'backend',
      'frontend',
    ])
  })

  it('does not reinterpret prose or authored Markdown as an inventory', () => {
    const { container, rerender } = render(
      <AssistantMarkdown>{`First sentence.
Second sentence.
Third sentence.`}</AssistantMarkdown>,
    )

    expect(screen.queryByRole('list', { name: 'Result entries' })).not.toBeInTheDocument()
    expect(container.querySelectorAll('p > br')).toHaveLength(2)

    rerender(<AssistantMarkdown>{`- First item
- Second item
- Third item`}</AssistantMarkdown>)

    expect(screen.queryByRole('list', { name: 'Result entries' })).not.toBeInTheDocument()
    expect(screen.getByRole('list')).not.toHaveClass('assistant-compact-list')
  })

  it('presents a workspace-relative source as a non-clickable file reference', () => {
    render(
      <AssistantMarkdown>
        {'Source: [README.md](./README.md:271)'}
      </AssistantMarkdown>,
    )

    expect(screen.queryByRole('link')).not.toBeInTheDocument()
    expect(screen.getByText('README.md:271')).toHaveClass('assistant-workspace-reference')
  })
})
