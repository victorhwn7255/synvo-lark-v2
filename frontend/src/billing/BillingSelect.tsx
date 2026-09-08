import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import { ArtifactIcon, CheckIcon } from '../workspace/visuals'

export type BillingSelectOption = { value: string; label: string; disabled?: boolean }

/** Shared selection mechanics for billing years and saved reports; never owns their data. */
export function BillingSelect({ id, label, value, options, onChange, disabled = false, compact = false, reportIcon = false }: {
  id?: string; label: string; value: string; options: BillingSelectOption[]; onChange: (value: string) => void
  disabled?: boolean; compact?: boolean; reportIcon?: boolean
}) {
  const generated = useId(), controlId = id ?? generated, listId = `${controlId}-options`
  const trigger = useRef<HTMLButtonElement>(null), menu = useRef<HTMLDivElement>(null)
  const [open, setOpen] = useState(false)
  const [active, setActive] = useState(value)
  const [position, setPosition] = useState({ left: 0, top: 0, width: 0, maxHeight: 280, transform: '' })
  const search = useRef({ text: '', at: 0 })
  const enabled = options.filter(option => !option.disabled)
  const shown = open && !disabled
  const activeValue = enabled.some(option => option.value === active) ? active : enabled[0]?.value
  const activeIndex = options.findIndex(option => option.value === activeValue)
  const selected = options.find(option => option.value === value)
  const show = (initial = value) => {
    if (disabled || !enabled.length || !trigger.current) return
    const rect = trigger.current.getBoundingClientRect()
    const width = Math.min(Math.max(rect.width, compact ? 112 : 240), window.innerWidth - 16)
    const below = window.innerHeight - rect.bottom - 12, above = rect.top - 12
    const upwards = below < 160 && above > below
    const maxHeight = Math.max(0, Math.min(280, upwards ? above : below))
    setPosition({ left: Math.max(8, Math.min(rect.left, window.innerWidth - width - 8)),
      top: upwards ? rect.top - 6 : rect.bottom + 6, transform: upwards ? 'translateY(-100%)' : '', width, maxHeight })
    setActive(initial); search.current = { text: '', at: 0 }; setOpen(true)
  }
  const commit = (next: string) => {
    if (disabled || !enabled.some(option => option.value === next)) return
    setOpen(false); trigger.current?.focus(); onChange(next)
  }
  useEffect(() => { if (disabled) setOpen(false) }, [disabled])
  useEffect(() => {
    if (!shown) return
    const outside = (event: Event) => {
      if (!menu.current?.contains(event.target as Node) && !trigger.current?.contains(event.target as Node)) setOpen(false)
    }
    const close = () => setOpen(false)
    document.addEventListener('pointerdown', outside)
    document.addEventListener('focusin', outside)
    document.addEventListener('scroll', outside, true)
    window.addEventListener('resize', close)
    return () => {
      document.removeEventListener('pointerdown', outside); document.removeEventListener('focusin', outside)
      document.removeEventListener('scroll', outside, true); window.removeEventListener('resize', close)
    }
  }, [shown])
  useEffect(() => {
    if (shown) menu.current?.querySelector<HTMLElement>(`[id="${listId}-${activeIndex}"]`)?.scrollIntoView?.({ block: 'nearest' })
  }, [shown, activeIndex, listId])
  const keyDown = (event: KeyboardEvent<HTMLButtonElement>) => {
    if (disabled || !enabled.length) return
    const key = event.key
    if (key === 'Tab') { setOpen(false); return }
    if (key === 'Escape') { if (shown) { event.preventDefault(); event.stopPropagation(); setOpen(false) } return }
    if (['ArrowDown', 'ArrowUp', 'Home', 'End', 'Enter', ' '].includes(key)) {
      event.preventDefault()
      if (key === 'Home' || key === 'End') {
        const next = enabled[key === 'Home' ? 0 : enabled.length - 1].value
        if (shown) setActive(next); else show(next)
      } else if (!shown) show()
      else if (key === 'Enter' || key === ' ') { if (activeValue) commit(activeValue) }
      else {
        const index = enabled.findIndex(option => option.value === activeValue)
        setActive(enabled[(index + (key === 'ArrowDown' ? 1 : -1) + enabled.length) % enabled.length].value)
      }
    } else if (key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      event.preventDefault()
      const now = Date.now(), text = (now - search.current.at < 700 ? search.current.text : '') + key.toLocaleLowerCase()
      const match = enabled.find(option => option.label.toLocaleLowerCase().startsWith(text))
      if (!shown) show(match?.value)
      else if (match) setActive(match.value)
      search.current = { text, at: now }
    }
  }
  return <>
    <button ref={trigger} id={controlId} type="button" role="combobox" aria-label={label} aria-haspopup="listbox"
      aria-expanded={shown} aria-controls={shown ? listId : undefined} aria-activedescendant={shown && activeIndex >= 0 ? `${listId}-${activeIndex}` : undefined}
      value={value} disabled={disabled} className={`billing-select-trigger${compact ? ' billing-select-trigger--compact' : ''}`}
      onKeyDown={keyDown} onClick={() => shown ? setOpen(false) : show()}>
      {reportIcon && <ArtifactIcon />}<span className="billing-select-label">{selected?.label ?? 'Choose an option'}</span>
      <span className="billing-select-arrow" aria-hidden="true" />
    </button>
    {shown && createPortal(<div ref={menu} id={listId} role="listbox" aria-label={label}
      className={`billing-select-menu workspace-themed-scrollbar${reportIcon ? ' billing-select-menu--reports' : ''}`} style={position}>
      {options.map((option, index) => <button key={option.value} id={`${listId}-${index}`} value={option.value} type="button" role="option" tabIndex={-1}
        aria-selected={option.value === value} disabled={option.disabled} data-active={option.value === activeValue}
        className="billing-select-option" onPointerDown={event => event.preventDefault()}
        onMouseMove={() => { if (!option.disabled) setActive(option.value) }} onClick={() => commit(option.value)}>
        {reportIcon && <ArtifactIcon />}<span className="billing-select-label">{option.label}</span>
        <span className="billing-select-check" aria-hidden="true">{option.value === value && <CheckIcon />}</span>
      </button>)}
    </div>, document.body)}
  </>
}
