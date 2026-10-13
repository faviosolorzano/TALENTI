import { safeReturnTo } from '../../session/returnTo'
import { RETURN_TO_PARAM, sessionPaths } from '../../session/sessionPaths'

/**
 * Routes of the legal framework (spec 002). The route of the "new version of the documents"
 * screen is written once, in `session/sessionPaths.ts`, and re-exported here; the safe
 * `returnTo` rule lives once, in `session/returnTo.ts`.
 */
export const LEGAL_PATHS = {
  terms: '/terminos',
  privacy: '/privacidad',
  cookies: '/cookies',
  transparency: '/transparencia',
} as const

export const ACCEPT_DOCUMENTS_PATH = sessionPaths.acceptDocuments

/** Where to go after accepting when the address has no (valid) `returnTo`. */
export const DEFAULT_RETURN_PATH = sessionPaths.home

export { safeReturnTo }

/** Address of the accept screen that comes back to `returnTo` (an internal route) afterwards. */
export function acceptDocumentsPath(returnTo?: string): string {
  const safe = returnTo ? safeReturnTo(returnTo) : null
  return safe
    ? `${ACCEPT_DOCUMENTS_PATH}?${RETURN_TO_PARAM}=${encodeURIComponent(safe)}`
    : ACCEPT_DOCUMENTS_PATH
}
