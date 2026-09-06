import { useEffect, useRef, type RefObject } from 'react'

// Shared by navigation and task/decision sheets. The caller owns when it is modal.
export function useModalFocus(ref: RefObject<HTMLElement | null>, enabled: boolean, onClose?: () => void) {
  const closeRef = useRef(onClose)
  closeRef.current = onClose
  useEffect(() => {
    const root = ref.current
    if (!enabled || !root) return
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const siblings: { element: HTMLElement; inert: boolean }[] = []
    let branch: HTMLElement = root
    while (branch.parentElement && branch !== document.body) {
      for (const sibling of branch.parentElement.children) {
        if (sibling !== branch && sibling instanceof HTMLElement && !sibling.hasAttribute('data-modal-backdrop')) {
          siblings.push({ element: sibling, inert: sibling.inert })
          sibling.inert = true
        }
      }
      branch = branch.parentElement
    }
    const selector = 'a[href], button:not(:disabled), input:not(:disabled), select:not(:disabled), textarea:not(:disabled), summary, [tabindex="0"]'
    const focusable = () => Array.from(root.querySelectorAll<HTMLElement>('*')).filter((element) => {
      if (!element.matches(selector)) return false
      if (element.closest('[hidden], [inert]')) return false
      for (let ancestor: HTMLElement | null = element; ancestor && ancestor !== root; ancestor = ancestor.parentElement) {
        if (getComputedStyle(ancestor).display === 'none' || getComputedStyle(ancestor).visibility === 'hidden') return false
        if (ancestor.parentElement instanceof HTMLDetailsElement && !ancestor.parentElement.open && ancestor.tagName !== 'SUMMARY') return false
      }
      return true
    })
    const focusFirst = () => {
      const initial = root.querySelector<HTMLElement>('[data-modal-initial]:not(:disabled)')
      ;(initial ?? focusable()[0] ?? root).focus()
    }
    const keydown = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && closeRef.current) {
        event.preventDefault()
        closeRef.current()
      }
      if (event.key !== 'Tab') return
      const items = focusable()
      const first = items[0]
      const last = items.at(-1)
      if (!first) { event.preventDefault(); root.focus(); return }
      if (event.shiftKey && (document.activeElement === first || document.activeElement === root)) {
        event.preventDefault(); last?.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault(); first.focus()
      }
    }
    const contain = (event: FocusEvent) => {
      if (event.target instanceof Node && !root.contains(event.target)) focusFirst()
    }
    focusFirst()
    root.addEventListener('keydown', keydown)
    document.addEventListener('focusin', contain)
    return () => {
      root.removeEventListener('keydown', keydown)
      document.removeEventListener('focusin', contain)
      for (const { element, inert } of siblings) element.inert = inert
      if (previous?.isConnected && !previous.closest('[inert]')) previous.focus()
    }
  }, [enabled, ref])
}
