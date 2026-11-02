import { useEffect, useRef, useSyncExternalStore, type ReactNode } from 'react'
import { breakpoints } from '../../design/tokens'
import { Button } from '../../components/Button'
import { es } from '../../i18n/es'
import './DoraTranscriptPanel.css'

const query = `(min-width: ${breakpoints.columns}px)`
const snapshot = () => typeof window.matchMedia === 'function' && window.matchMedia(query).matches
function subscribe(update: () => void) {
  const media = window.matchMedia?.(query)
  media?.addEventListener('change', update)
  return () => media?.removeEventListener('change', update)
}

/** One persistent dialog: non-modal in the desktop rail, native modal on smaller screens. */
export function DoraTranscriptPanel({
  id,
  open,
  onClose,
  children,
}: {
  id: string
  open: boolean
  onClose: () => void
  children: ReactNode
}) {
  const wide = useSyncExternalStore(subscribe, snapshot, () => false)
  const dialog = useRef<HTMLDialogElement>(null)
  const title = useRef<HTMLHeadingElement>(null)
  const opener = useRef<HTMLElement | null>(null)
  useEffect(() => {
    if (!open) return
    opener.current = document.activeElement as HTMLElement | null
    return () => {
      if (opener.current?.isConnected) opener.current.focus({ preventScroll: true })
    }
  }, [open])
  useEffect(() => {
    if (!open) return
    const node = dialog.current!
    const overflow = document.body.style.overflow
    if (wide) node.show()
    else {
      node.showModal()
      document.body.style.overflow = 'hidden'
    }
    title.current?.focus({ preventScroll: true })
    return () => {
      node.close()
      if (!wide) document.body.style.overflow = overflow
    }
  }, [open, wide])
  return (
    <dialog
      ref={dialog}
      id={id}
      className="dora-transcript"
      aria-labelledby={id + '-title'}
      aria-modal={wide ? undefined : true}
      onCancel={(event) => {
        event.preventDefault()
        onClose()
      }}
      onKeyDown={(event) => {
        if (event.key === 'Escape') {
          event.preventDefault()
          event.stopPropagation()
          onClose()
        }
      }}
    >
      <header className="dora-transcript__header">
        <div>
          <h2 ref={title} tabIndex={-1} id={id + '-title'}>
            {es.doraCall.transcriptButton}
          </h2>
          <p>{es.doraCall.transcriptHint}</p>
        </div>
        <Button
          variant="quiet"
          className="dora-transcript__close"
          onClick={onClose}
          aria-label={es.doraCall.closeTranscript}
        >
          <span aria-hidden="true">×</span>
        </Button>
      </header>
      <div
        className="dora-transcript__content"
        role="region"
        aria-labelledby={id + '-title'}
        tabIndex={0}
      >
        {children}
      </div>
    </dialog>
  )
}
