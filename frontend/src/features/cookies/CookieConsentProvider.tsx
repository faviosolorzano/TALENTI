import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { es } from '../../i18n/es'
import { CATALOG, type CookieCatalog } from './catalog'
import {
  acceptAllChoices,
  isAllowedIn,
  readConsent,
  rejectOptionalChoices,
  writeConsent,
  type Choices,
  type StoredConsent,
} from './consent'
import { CookieConsentContext, type CookieConsentValue } from './cookieConsentContext'

/** How long the "Preferencias guardadas" announcement stays in the page. */
const ANNOUNCEMENT_MS = 6000

interface FocusRequest {
  /** Element to give focus back to, if it is still on the page; otherwise the main content. */
  opener: HTMLElement | null
}

/**
 * Holds the cookie choice for the whole app: whether the notice shows, whether the panel is open
 * and what the visitor allowed. It reads `talenti_cookies` once at the start and writes it when
 * the visitor chooses. It does not depend on the API (the notice works even if the API is down).
 * The catalog can be replaced (tests use one with an `analytics` category).
 */
export function CookieConsentProvider({
  catalog = CATALOG,
  children,
}: {
  catalog?: CookieCatalog
  children: ReactNode
}) {
  const [consent, setConsent] = useState<StoredConsent | null>(() => readConsent(catalog))
  const [panelOpen, setPanelOpen] = useState(false)
  const [announcement, setAnnouncement] = useState('')
  const [focusRequest, setFocusRequest] = useState<FocusRequest | null>(null)
  const openerRef = useRef<HTMLElement | null>(null)

  // Focus moves after the screen updated: the element that opened the panel may have been
  // removed by the choice itself (the notice closes), and then focus goes to the content.
  useEffect(() => {
    if (!focusRequest) return
    const { opener } = focusRequest
    const target = opener?.isConnected ? opener : document.querySelector<HTMLElement>('main')
    target?.focus({ preventScroll: true })
  }, [focusRequest])

  useEffect(() => {
    if (!announcement) return
    const timer = window.setTimeout(() => setAnnouncement(''), ANNOUNCEMENT_MS)
    return () => window.clearTimeout(timer)
  }, [announcement])

  const commit = useCallback(
    (choices: Choices, fromPanel: boolean) => {
      setConsent(writeConsent(choices, catalog))
      setAnnouncement(es.cookies.saved)
      setPanelOpen(false)
      setFocusRequest({ opener: fromPanel ? openerRef.current : null })
    },
    [catalog],
  )

  const value = useMemo<CookieConsentValue>(
    () => ({
      catalog,
      consent,
      panelOpen,
      openPanel: (opener) => {
        openerRef.current = opener ?? (document.activeElement as HTMLElement | null)
        setPanelOpen(true)
      },
      closePanel: ({ returnFocus = true } = {}) => {
        setPanelOpen(false)
        if (returnFocus) setFocusRequest({ opener: openerRef.current })
      },
      acceptAll: () => commit(acceptAllChoices(catalog), false),
      rejectOptional: () => commit(rejectOptionalChoices(catalog), false),
      save: (choices) => commit(choices, true),
      isAllowed: (id) => isAllowedIn(consent, id, catalog),
    }),
    [catalog, consent, panelOpen, commit],
  )

  return (
    <CookieConsentContext.Provider value={value}>
      {children}
      <div role="status" className="visually-hidden">
        {announcement}
      </div>
    </CookieConsentContext.Provider>
  )
}
