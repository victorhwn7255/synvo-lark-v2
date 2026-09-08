import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { BillingSelect } from './BillingSelect'

const options = [{ value: '2024', label: '2024' }, { value: '2025', label: '2025', disabled: true }, { value: '2026', label: '2026' }]
afterEach(() => { cleanup(); vi.restoreAllMocks() })
describe('BillingSelect', () => {
  it('uses a themed portal with report icons and commits only a chosen enabled option', () => {
    const onChange = vi.fn()
    const { container } = render(<BillingSelect label="Saved analyses" value="2026" options={options} onChange={onChange} reportIcon />)
    const trigger = screen.getByRole('combobox')
    fireEvent.click(trigger)
    expect(container.querySelector('[role="listbox"]')).toBeNull()
    expect(screen.getByRole('listbox')).toHaveClass('billing-select-menu--reports')
    expect(screen.getByRole('option', { name: '2026' })).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByRole('option', { name: '2024' }).querySelector('svg')).not.toBeNull()
    fireEvent.click(screen.getByRole('option', { name: '2025' }))
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('option', { name: '2024' }))
    expect(onChange).toHaveBeenCalledWith('2024')
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(trigger).toHaveFocus()
  })
  it('supports arrows, Home/End, typeahead and Enter/Space without selecting during navigation', () => {
    const onChange = vi.fn()
    render(<BillingSelect label="Spending year" value="2026" options={options} onChange={onChange} compact />)
    const trigger = screen.getByRole('combobox')
    fireEvent.keyDown(trigger, { key: 'ArrowDown' })
    expect(screen.getByRole('listbox')).not.toHaveClass('billing-select-menu--reports')
    fireEvent.keyDown(trigger, { key: 'ArrowUp' })
    expect(trigger).toHaveAttribute('aria-activedescendant', screen.getByRole('option', { name: '2024' }).id)
    expect(onChange).not.toHaveBeenCalled()
    fireEvent.keyDown(trigger, { key: 'End' })
    fireEvent.keyDown(trigger, { key: ' ' })
    expect(onChange).toHaveBeenLastCalledWith('2026')
    fireEvent.keyDown(trigger, { key: 'Home' })
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(onChange).toHaveBeenLastCalledWith('2024')
    for (const key of '2026') fireEvent.keyDown(trigger, { key })
    fireEvent.keyDown(trigger, { key: 'Enter' })
    expect(onChange).toHaveBeenLastCalledWith('2026')
  })
  it('dismisses without committing on Escape, Tab, outside interactions, scroll and resize', () => {
    const onChange = vi.fn()
    render(<BillingSelect label="Year" value="2026" options={options} onChange={onChange} />)
    const trigger = screen.getByRole('combobox')
    const dismissals = [() => fireEvent.keyDown(trigger, { key: 'Escape' }), () => fireEvent.keyDown(trigger, { key: 'Tab' }),
      () => fireEvent.pointerDown(document.body), () => fireEvent.focusIn(document.body), () => fireEvent.scroll(document), () => fireEvent.resize(window)]
    for (const dismiss of dismissals) {
      fireEvent.click(trigger)
      expect(screen.getByRole('listbox')).toBeInTheDocument()
      fireEvent.scroll(screen.getByRole('listbox'))
      expect(screen.getByRole('listbox')).toBeInTheDocument()
      dismiss()
      expect(screen.queryByRole('listbox')).toBeNull()
    }
    expect(onChange).not.toHaveBeenCalled()
  })
  it('closes when disabled or unmounted and uses new controlled values', () => {
    const onChange = vi.fn()
    const view = render(<BillingSelect label="Year" value="2026" options={options} onChange={onChange} />)
    fireEvent.click(screen.getByRole('combobox'))
    view.rerender(<BillingSelect label="Year" value="2024" options={options} onChange={onChange} disabled />)
    expect(screen.queryByRole('listbox')).toBeNull()
    expect(screen.getByRole('combobox')).toHaveTextContent('2024')
    view.rerender(<BillingSelect label="Year" value="2024" options={options} onChange={onChange} />)
    fireEvent.click(screen.getByRole('combobox'))
    view.unmount()
    expect(screen.queryByRole('listbox')).toBeNull()
  })
  it('opens upwards at the bottom edge and constrains width to the viewport', () => {
    render(<BillingSelect label="Year" value="2026" options={options} onChange={vi.fn()} />)
    const trigger = screen.getByRole('combobox')
    vi.spyOn(trigger, 'getBoundingClientRect').mockReturnValue({ left: window.innerWidth - 60, top: window.innerHeight - 60,
      bottom: window.innerHeight - 20, width: 100, height: 40, right: window.innerWidth + 40, x: 0, y: 0, toJSON: () => ({}) })
    fireEvent.click(trigger)
    const menu = screen.getByRole('listbox')
    expect(menu.style.transform).toBe('translateY(-100%)')
    expect(Number.parseFloat(menu.style.left) + Number.parseFloat(menu.style.width)).toBeLessThanOrEqual(window.innerWidth - 8)
    expect(Number.parseFloat(menu.style.maxHeight)).toBeLessThanOrEqual(280)
  })
})
