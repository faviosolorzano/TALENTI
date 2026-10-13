/**
 * Routes of the legal framework (spec 002). The route of the "new version of the documents"
 * screen lives only here: whoever redirects to it (the private routes, once the session exists)
 * imports `ACCEPT_DOCUMENTS_PATH` and `acceptDocumentsPath`.
 */
export const LEGAL_PATHS = {
  terms: '/terminos',
  privacy: '/privacidad',
  cookies: '/cookies',
  transparency: '/transparencia',
} as const

export const ACCEPT_DOCUMENTS_PATH = '/aceptar-documentos'

/** Where to go after accepting when the address has no (valid) `returnTo`. */
export const DEFAULT_RETURN_PATH = '/panel'

/** Address of the accept screen that comes back to `returnTo` (an internal route) afterwards. */
export function acceptDocumentsPath(returnTo?: string): string {
  const safe = returnTo ? safeReturnTo(returnTo) : null
  return safe
    ? `${ACCEPT_DOCUMENTS_PATH}?returnTo=${encodeURIComponent(safe)}`
    : ACCEPT_DOCUMENTS_PATH
}

/**
 * Validates a `returnTo` value (001, edge cases): only an internal route is accepted, that is,
 * one starting with a single "/", with no "//", no "\" and no control characters (a browser
 * would drop a tab or a line break and turn "/\t/evil.com" into "//evil.com"). Anything else,
 * and the accept screen itself, returns `null`.
 */
export function safeReturnTo(value: string | null | undefined): string | null {
  if (!value) return null
  if (!value.startsWith('/') || value.startsWith('//')) return null
  // eslint-disable-next-line no-control-regex
  if (value.includes('\\') || /[\x00-\x1f\x7f]/.test(value)) return null
  if (BLOCKED_RETURN_PATHS.has(normalizedPath(value))) return null
  return value
}

/** Routes that must never be a `returnTo`: coming back to them would loop (or is pointless). */
const BLOCKED_RETURN_PATHS: ReadonlySet<string> = new Set([
  ACCEPT_DOCUMENTS_PATH,
  '/ingresar',
  '/registro',
])

/**
 * The path a browser would end up on, in a comparable form: dot segments resolved, percent
 * escapes decoded (until stable, so "%2561" cannot hide a letter), lower case, no trailing "/".
 */
function normalizedPath(value: string): string {
  let path: string
  try {
    path = new URL(value, 'http://x').pathname
    for (let i = 0; i < 5; i++) {
      const decoded = decodeURIComponent(path)
      if (decoded === path) break
      path = new URL(decoded, 'http://x').pathname
    }
  } catch {
    // An address that cannot be read is not worth following: treat it as a blocked one.
    return ACCEPT_DOCUMENTS_PATH
  }
  return path.toLowerCase().replace(/\/+$/, '') || '/'
}
