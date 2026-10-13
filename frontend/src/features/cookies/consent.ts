import { readCookie } from '../../api/client'
import { CATALOG, COOKIE_MAX_AGE_SECONDS, COOKIE_NAME, type CookieCatalog } from './catalog'

/** category id -> allowed. */
export type Choices = Record<string, boolean>

/** What the cookie holds: no personal data, no identifiers (spec 002, section 5). */
export interface StoredConsent {
  /** Catalog version the visitor chose with. */
  v: number
  c: Choices
  /** When the choice was made (ISO, UTC). */
  t: string
}

/** The choice of "Aceptar": everything on. */
export function acceptAllChoices(catalog: CookieCatalog = CATALOG): Choices {
  return Object.fromEntries(catalog.categories.map((category) => [category.id, true]))
}

/** The choice of "Rechazar" and the starting point of the panel: only the required ones on. */
export function rejectOptionalChoices(catalog: CookieCatalog = CATALOG): Choices {
  return Object.fromEntries(catalog.categories.map((category) => [category.id, category.required]))
}

/**
 * Keeps one entry per category of the catalog. A required category is always on; an optional one
 * is on only if the value is exactly `true`. Unknown categories and odd values are ignored.
 */
export function normalizeChoices(raw: unknown, catalog: CookieCatalog = CATALOG): Choices {
  const source =
    typeof raw === 'object' && raw !== null && !Array.isArray(raw)
      ? (raw as Record<string, unknown>)
      : {}
  return Object.fromEntries(
    catalog.categories.map((category) => [
      category.id,
      category.required || source[category.id] === true,
    ]),
  )
}

/**
 * Reads the raw value of the cookie. Returns `null` (= "no choice yet") when it is missing,
 * unreadable, from an older catalog version or older than 12 months (002-16). It never throws.
 */
export function parseConsent(
  raw: string | null,
  catalog: CookieCatalog = CATALOG,
  now: Date = new Date(),
): StoredConsent | null {
  if (!raw) return null
  let value: unknown
  try {
    value = JSON.parse(raw)
  } catch {
    return null
  }
  if (typeof value !== 'object' || value === null || Array.isArray(value)) return null
  const { v, c, t } = value as Record<string, unknown>
  if (typeof v !== 'number' || !Number.isInteger(v) || v < catalog.version) return null
  if (typeof c !== 'object' || c === null || Array.isArray(c)) return null
  if (typeof t !== 'string') return null
  const chosenAt = new Date(t).getTime()
  if (Number.isNaN(chosenAt) || now.getTime() - chosenAt > COOKIE_MAX_AGE_SECONDS * 1000) {
    return null
  }
  return { v, c: normalizeChoices(c, catalog), t }
}

/** The stored choice of this browser, or `null` when the visitor has not chosen (or it is no longer valid). */
export function readConsent(catalog: CookieCatalog = CATALOG): StoredConsent | null {
  return parseConsent(readCookie(COOKIE_NAME), catalog)
}

/**
 * The text to assign to `document.cookie`: `Path=/`, `SameSite=Lax`, 12 months and, over HTTPS
 * (production), `Secure`. Not `HttpOnly`: the page has to read it and it holds nothing personal.
 */
export function serializeConsent(
  consent: StoredConsent,
  secure: boolean = window.location.protocol === 'https:',
): string {
  const parts = [
    `${COOKIE_NAME}=${encodeURIComponent(JSON.stringify(consent))}`,
    'Path=/',
    `Max-Age=${COOKIE_MAX_AGE_SECONDS}`,
    'SameSite=Lax',
  ]
  if (secure) parts.push('Secure')
  return parts.join('; ')
}

/**
 * Saves the choice (also renews the 12 months). If the browser blocks cookies nothing is
 * stored and the notice appears again on the next load; the page keeps working.
 */
export function writeConsent(
  choices: Choices,
  catalog: CookieCatalog = CATALOG,
  now: Date = new Date(),
): StoredConsent {
  const consent: StoredConsent = {
    v: catalog.version,
    c: normalizeChoices(choices, catalog),
    t: now.toISOString(),
  }
  try {
    document.cookie = serializeConsent(consent)
  } catch {
    // Blocked: the choice only lives in memory until the page is reloaded.
  }
  return consent
}

/**
 * Whether a category may be used. `necessary` is always allowed; an optional one only after the
 * visitor accepted it with the current catalog version. Nothing optional exists yet, so nothing
 * calls this for the moment.
 */
export function isAllowed(id: string, catalog: CookieCatalog = CATALOG): boolean {
  const category = catalog.categories.find((candidate) => candidate.id === id)
  if (!category) return false
  if (category.required) return true
  return readConsent(catalog)?.c[id] === true
}
