import { useEffect, useId, useRef, type ReactNode } from 'react'
import { Button } from '../../components/Button'
import { Card } from '../../components/Card'
import { usePrefersReducedMotion } from '../../hooks/usePrefersReducedMotion'
import { es } from '../../i18n/es'
import { sectionDomId, type SectionKey } from './sections'
import './SectionCard.css'

interface SectionCardProps {
  section: SectionKey
  title: string
  /** The server says the items of this card are done. */
  done: boolean
  /** The card shows its form (edit mode) instead of its summary (read mode). */
  open: boolean
  /** Highlights "Editar" because this is the next step the server suggests. */
  emphasized: boolean
  /** Shows "Guardado" right after this card saved. */
  justSaved: boolean
  /** Opened from the address (`?seccion=`) or a link: also bring it into view. */
  bringIntoView: boolean
  onEdit: () => void
  /** Read mode. */
  summary: ReactNode
  /** Edit mode (a form). Only drawn while `open`. */
  children: ReactNode
  /** Hides "Editar" (a card whose open mode has no read counterpart to leave). */
  hideEdit?: boolean
}

const CONTROL_SELECTOR = 'input:not([type=hidden]), textarea, select, button:not([disabled])'

/**
 * One card of /perfil (006-14): a mark (done / pending), the title, "Editar" and, below, either
 * the summary of what is saved or the form. It keeps focus where the person is: opening puts it
 * on the first control (or on the element marked `data-autofocus`); closing a form that had the
 * focus returns it to "Editar".
 */
export function SectionCard({
  section,
  title,
  done,
  open,
  emphasized,
  justSaved,
  bringIntoView,
  onEdit,
  summary,
  children,
  hideEdit = false,
}: SectionCardProps) {
  const headingId = useId()
  const cardRef = useRef<HTMLElement>(null)
  const bodyRef = useRef<HTMLDivElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  const wasOpen = useRef(false)
  const reducedMotion = usePrefersReducedMotion()

  useEffect(() => {
    if (open && !wasOpen.current) {
      // Just opened (or opened from the address): the form is there now. Focus its first control.
      const body = bodyRef.current
      const target =
        body?.querySelector<HTMLElement>('[data-autofocus]') ??
        body?.querySelector<HTMLElement>(CONTROL_SELECTOR)
      target?.focus({ preventScroll: true })
      if (bringIntoView) {
        headingRef.current?.scrollIntoView({
          block: 'start',
          behavior: reducedMotion ? 'auto' : 'smooth',
        })
      }
    } else if (!open && wasOpen.current) {
      // Just closed: if the focus was lost with the form, give it back to "Editar".
      const active = document.activeElement
      if (!active || active === document.body) {
        cardRef.current?.querySelector<HTMLElement>('.section-card__edit')?.focus()
      }
    }
    wasOpen.current = open
  }, [open, bringIntoView, reducedMotion])

  return (
    <Card
      as="section"
      ref={cardRef}
      id={sectionDomId(section)}
      className="section-card"
      aria-labelledby={headingId}
      data-open={open || undefined}
      data-done={done || undefined}
    >
      <div className="section-card__head">
        <span className="section-card__tick" aria-hidden="true" />
        <div className="section-card__heading">
          <h2 id={headingId} ref={headingRef} className="section-card__title" tabIndex={-1}>
            {title}
          </h2>
          <span className="section-card__status" data-done={done || undefined}>
            {done ? es.profile.sectionStatus.done : es.profile.sectionStatus.pending}
          </span>
          <span className="section-card__saved" role="status">
            {justSaved ? es.profile.actions.saved : ''}
          </span>
        </div>
        {!open && !hideEdit && (
          <Button
            variant={emphasized ? 'primary' : 'quiet'}
            size="sm"
            className="section-card__edit"
            aria-label={es.profile.actions.editSection(title)}
            onClick={onEdit}
          >
            {es.profile.actions.edit}
          </Button>
        )}
      </div>

      <div ref={bodyRef} className="section-card__body" data-mode={open ? 'edit' : 'read'}>
        {open ? children : summary}
      </div>
    </Card>
  )
}
