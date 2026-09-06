import { useRef, useState } from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { useModalFocus } from './useModalFocus'

function Fixture({ enabled = true }: { enabled?: boolean }) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLElement>(null)
  useModalFocus(ref, enabled && open, () => setOpen(false))
  return <div>
    <button onClick={() => setOpen(true)}>Open sheet</button>
    {open && <section ref={ref} role="dialog" tabIndex={-1}>
      <button data-modal-initial onClick={() => setOpen(false)}>Close sheet</button>
      <details><summary>Edit goal</summary><input aria-label="Collapsed objective" /></details>
      <button>Last action</button>
    </section>}
  </div>
}

describe('useModalFocus', () => {
  afterEach(cleanup)
  it('contains focus, skips collapsed controls, and restores focus and background interaction', () => {
    render(<Fixture />)
    const trigger = screen.getByRole('button', { name: 'Open sheet' })
    trigger.focus()
    fireEvent.click(trigger)
    const close = screen.getByRole('button', { name: 'Close sheet' })
    expect(close).toHaveFocus()
    expect(trigger.inert).toBe(true)
    fireEvent.keyDown(close, { key: 'Tab', shiftKey: true })
    expect(screen.getByRole('button', { name: 'Last action' })).toHaveFocus()
    fireEvent.keyDown(document.activeElement!, { key: 'Tab' })
    expect(close).toHaveFocus()
    fireEvent.keyDown(close, { key: 'Escape' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
    expect(trigger.inert).toBeFalsy()
  })
  it('releases modal behavior when resizing to a nonmodal desktop panel', () => {
    const { rerender } = render(<Fixture />)
    const trigger = screen.getByRole('button', { name: 'Open sheet' })
    trigger.focus()
    fireEvent.click(trigger)
    expect(trigger.inert).toBe(true)
    rerender(<Fixture enabled={false} />)
    expect(trigger.inert).toBeFalsy()
    expect(trigger).toHaveFocus()
    expect(screen.getByRole('dialog')).toBeInTheDocument()
  })
})
