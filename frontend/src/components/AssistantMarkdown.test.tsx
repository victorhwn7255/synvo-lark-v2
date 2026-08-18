import { render, screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { AssistantMarkdown } from './AssistantMarkdown'

describe('AssistantMarkdown', () => {
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
})
