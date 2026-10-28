import { useEffect, useId, useRef, type KeyboardEvent, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import './Dialog.css'

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/** The app root: while a dialog is open the page behind it can be neither reached nor read. */
const APP_ROOT_ID = 'root'
const dialogStack: HTMLElement[] = []
let pageOverflow = ''

interface DialogProps {
  /** Keep content mounted while closed, to preserve an editor draft. */
  open?: boolean
  title: string
  /** Esc and the scrim ask to close. Ignored while `busy`. */
  onClose: () => void
  busy?: boolean
  children: ReactNode
}

/**
 * Accessible modal dialog: `role="dialog"` with `aria-modal`, focus enters the title and stays
 * inside, Esc cancels and focus goes back to the element that opened it.
 */
export function Dialog({ title, onClose, busy = false, children, open = true }: DialogProps) {
  const dialogRef = useRef<HTMLDivElement>(null)
  const titleRef = useRef<HTMLHeadingElement>(null)
  const titleId = useId()
  const onCloseRef = useRef(onClose)
  const busyRef = useRef(busy)
  useEffect(() => {
    onCloseRef.current = onClose
    busyRef.current = busy
  })

  useEffect(() => {
    if (!open) return
    const opener = document.activeElement as HTMLElement | null
    titleRef.current?.focus()
    const root = document.getElementById(APP_ROOT_ID)
    const dialog = dialogRef.current!
    if (!dialogStack.length) pageOverflow = document.body.style.overflow
    dialogStack.push(dialog)
    root?.setAttribute('inert', '')
    document.body.style.overflow = 'hidden'
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (dialogStack.at(-1) !== dialog) return
      if (event.key === 'Escape' && !busyRef.current) onCloseRef.current()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      const wasTop = dialogStack.at(-1) === dialog
      const index = dialogStack.indexOf(dialog)
      if (index !== -1) dialogStack.splice(index, 1)
      if (!dialogStack.length) {
        root?.removeAttribute('inert')
        document.body.style.overflow = pageOverflow
      }
      if (wasTop && opener && document.contains(opener)) opener.focus()
    }
  }, [open])

  const keepFocusInside = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'Tab') return
    const dialog = dialogRef.current
    if (!dialog) return
    const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE))
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (!first || !last) return
    const active = document.activeElement
    if (event.shiftKey && (active === first || active === titleRef.current)) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && active === last) {
      event.preventDefault()
      first.focus()
    }
  }

  return createPortal(
    <div className="dialog__scrim" hidden={!open}>
      <div
        ref={dialogRef}
        className="dialog"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        onKeyDown={keepFocusInside}
      >
        <h2 id={titleId} ref={titleRef} tabIndex={-1} className="dialog__title">
          {title}
        </h2>
        {children}
      </div>
    </div>,
    document.body,
  )
}
