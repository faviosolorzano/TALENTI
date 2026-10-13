import { createContext } from 'react'
import type { CookieCatalog } from './catalog'
import type { Choices, StoredConsent } from './consent'

export interface CookieConsentValue {
  catalog: CookieCatalog
  /** The choice made, or `null` while the visitor has not chosen (the notice shows). */
  consent: StoredConsent | null
  panelOpen: boolean
  /** Opens the panel. Pass the element that opened it so focus goes back there. */
  openPanel: (opener?: HTMLElement | null) => void
  /** Closes the panel without saving; focus goes back to the opener unless `returnFocus` is false. */
  closePanel: (options?: { returnFocus?: boolean }) => void
  acceptAll: () => void
  rejectOptional: () => void
  /** Saves the choices of the panel. */
  save: (choices: Choices) => void
  /** Whether a category may be used right now (reactive version of `isAllowed`). */
  isAllowed: (id: string) => boolean
}

export const CookieConsentContext = createContext<CookieConsentValue | null>(null)
