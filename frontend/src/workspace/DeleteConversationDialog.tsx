import { useEffect, useRef, type KeyboardEvent } from 'react'
import type { ConversationSummary } from '../api/conversations'
import { TrashIcon } from './visuals'

export function DeleteConversationDialog({
  conversation,
  deleting,
  error,
  onCancel,
  onConfirm,
}: {
  conversation: ConversationSummary
  deleting: boolean
  error: string | null
  onCancel: () => void
  onConfirm: () => void
}) {
  const cancelRef = useRef<HTMLButtonElement>(null)
  const dialogRef = useRef<HTMLElement>(null)

  useEffect(() => {
    cancelRef.current?.focus()
  }, [])

  useEffect(() => {
    const closeOnEscape = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape' && !deleting) onCancel()
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [deleting, onCancel])

  const keepFocusInside = (event: KeyboardEvent<HTMLElement>) => {
    if (event.key !== 'Tab') return
    const buttons = Array.from(
      dialogRef.current?.querySelectorAll<HTMLButtonElement>('button:not(:disabled)') ?? [],
    )
    if (buttons.length === 0) return
    const first = buttons[0]
    const last = buttons.at(-1)
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last?.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first?.focus()
    }
  }

  return (
    <div className="workspace-dialog-backdrop">
      <section
        ref={dialogRef}
        className="workspace-dialog"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="delete-chat-title"
        aria-describedby="delete-chat-description"
        onKeyDown={keepFocusInside}
      >
        <div className="workspace-dialog__icon" aria-hidden="true"><TrashIcon /></div>
        <div className="workspace-dialog__content">
          <h2 id="delete-chat-title">Delete this chat?</h2>
          <p id="delete-chat-description">
            This will permanently delete “{conversation.title}” and all its messages. This action cannot be undone.
          </p>
          {error && <p className="workspace-dialog__error" role="alert">{error}</p>}
        </div>
        <div className="workspace-dialog__actions">
          <button ref={cancelRef} type="button" disabled={deleting} onClick={onCancel}>Cancel</button>
          <button type="button" disabled={deleting} onClick={onConfirm}>
            {deleting ? 'Deleting…' : 'Delete chat'}
          </button>
        </div>
      </section>
    </div>
  )
}
