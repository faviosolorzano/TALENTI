import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react'
import { createPortal } from 'react-dom'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { es } from '../../i18n/es'
import { LEGAL_PATHS } from '../legal/paths'
import { normalizeChoices, rejectOptionalChoices, type Choices } from './consent'
import { useCookieConsent } from './useCookieConsent'
import './CookiePanel.css'

const FOCUSABLE =
  'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/** The app root: while the panel is open the page behind it can be neither reached nor read. */
const APP_ROOT_ID = 'root'

/**
 * Cookie preferences panel (002-14): a modal dialog with one row per category of the catalog.
 * Focus enters the dialog and stays inside, Esc closes it and gives focus back to the element
 * that opened it. Required categories have no switch.
 */
export function CookiePanel() {
  const { panelOpen } = useCookieConsent()
  if (!panelOpen) return null
  return createPortal(<PanelDialog />, document.body)
}

function PanelDialog() {
  const { catalog, consent, save, closePanel } = useCookieConsent()
  const dialogRef = useRef<HTMLDivElement>(null)
  const titleId = useId()
  const introId = useId()
  // Shows the current choice; before any choice, optional categories start off.
  const [choices, setChoices] = useState<Choices>(() =>
    consent ? normalizeChoices(consent.c, catalog) : rejectOptionalChoices(catalog),
  )

  // Focus enters the dialog; the page behind is inert and does not scroll while it is open.
  useEffect(() => {
    dialogRef.current?.focus()
    const root = document.getElementById(APP_ROOT_ID)
    root?.setAttribute('inert', '')
    const previousOverflow = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      root?.removeAttribute('inert')
      document.body.style.overflow = previousOverflow
    }
  }, [])

  // Esc closes it from anywhere.
  useEffect(() => {
    const onKeyDown = (event: globalThis.KeyboardEvent) => {
      if (event.key === 'Escape') closePanel()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [closePanel])

  // Tab and Shift+Tab go around inside the dialog.
  const keepFocusInside = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key !== 'Tab') return
    const dialog = dialogRef.current
    if (!dialog) return
    const focusable = Array.from(dialog.querySelectorAll<HTMLElement>(FOCUSABLE))
    const first = focusable[0]
    const last = focusable[focusable.length - 1]
    if (!first || !last) return
    const active = document.activeElement
    if (event.shiftKey && (active === first || active === dialog)) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && active === last) {
      event.preventDefault()
      first.focus()
    }
  }

  return (
    <div className="cookie-panel__scrim">
      <div
        ref={dialogRef}
        className="cookie-panel"
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={introId}
        tabIndex={-1}
        onKeyDown={keepFocusInside}
      >
        <h2 id={titleId} className="cookie-panel__title">
          {es.cookies.panel.title}
        </h2>
        <p id={introId} className="cookie-panel__intro">
          {es.cookies.panel.intro}
        </p>

        <ul className="cookie-panel__list">
          {catalog.categories.map((category) => {
            const descriptionId = `${titleId}-${category.id}`
            return (
              <li key={category.id} className="cookie-panel__item">
                <div className="cookie-panel__item-head">
                  {category.required ? (
                    <>
                      <span className="cookie-panel__name">{category.name}</span>
                      <span className="cookie-panel__fixed">{es.cookies.panel.required}</span>
                    </>
                  ) : (
                    <label className="cookie-panel__name cookie-panel__switch">
                      <input
                        type="checkbox"
                        checked={choices[category.id] === true}
                        aria-describedby={descriptionId}
                        onChange={(event) =>
                          setChoices({ ...choices, [category.id]: event.target.checked })
                        }
                      />
                      {category.name}
                    </label>
                  )}
                </div>
                <p id={descriptionId} className="cookie-panel__description">
                  {category.description}
                </p>
              </li>
            )
          })}
        </ul>

        <p className="cookie-panel__policy">
          <Link to={LEGAL_PATHS.cookies} onClick={() => closePanel({ returnFocus: false })}>
            {es.cookies.panel.policyLink}
          </Link>
        </p>

        <div className="cookie-panel__actions">
          <Button onClick={() => save(choices)}>{es.cookies.panel.save}</Button>
          <Button variant="quiet" onClick={() => closePanel()}>
            {es.cookies.panel.close}
          </Button>
        </div>
      </div>
    </div>
  )
}
