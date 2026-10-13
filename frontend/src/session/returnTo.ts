import { RETURN_TO_PARAM, sessionPaths } from './sessionPaths'

/** Pages where "go back after signing in" would make a loop. */
const LOOP_PATHS: readonly string[] = [
  sessionPaths.login,
  sessionPaths.register,
  sessionPaths.acceptDocuments,
]

const FAKE_ORIGIN = 'http://talenti.invalid'
const MAX_LENGTH = 512
const BACKSLASH = String.fromCharCode(92)

/** True when the text has a control character (code below 32, or 127). */
function hasControlCharacter(text: string): boolean {
  for (const character of text) {
    const code = character.charCodeAt(0)
    if (code < 32 || code === 127) return true
  }
  return false
}

/**
 * The path in its plainest form, to compare it: percent-decoded (also when encoded twice), lower
 * case, without trailing slashes. Dot segments ("/./", "/../") are already resolved by `URL`.
 */
function plainPath(pathname: string): string {
  let current = pathname
  for (let round = 0; round < 3; round += 1) {
    let decoded: string
    try {
      decoded = decodeURIComponent(current)
    } catch {
      break
    }
    if (decoded === current) break
    current = decoded
  }
  const lower = current.toLowerCase().replace(/\/+$/, '')
  return lower === '' ? '/' : lower
}

function isLoop(pathname: string): boolean {
  const plain = plainPath(pathname)
  return LOOP_PATHS.some((loop) => plain === loop || plain.startsWith(`${loop}/`))
}

/** The tricks that turn "/something" into another site, checked on one form of the text. */
function looksExternal(text: string): boolean {
  return text.startsWith('//') || text.includes(BACKSLASH) || hasControlCharacter(text)
}

/**
 * Accepts ONLY a route inside this app (spec 001, edge cases). Returns the value when it is
 * safe, or `null`: the caller then uses the default destination.
 *
 * Returns the NORMALIZED route (path, query and hash of the resolved address), never the raw
 * text. Rejected: not a string, empty, too long, not starting with "/", "//" at the start, any
 * backslash (browsers read it as "/"), control characters, another origin once parsed, the
 * same tricks written encoded ("/%5Cevil.com", "/%2F/evil.com"), and the sign-in/sign-up
 * pages (a loop).
 */
export function safeReturnTo(raw: string | null | undefined): string | null {
  if (typeof raw !== 'string' || raw.length === 0 || raw.length > MAX_LENGTH) return null
  if (!raw.startsWith('/') || looksExternal(raw)) return null

  let decoded: string
  try {
    decoded = decodeURIComponent(raw)
  } catch {
    return null
  }
  if (looksExternal(decoded)) return null

  let parsed: URL
  try {
    parsed = new URL(raw, FAKE_ORIGIN)
  } catch {
    return null
  }
  if (parsed.origin !== FAKE_ORIGIN) return null
  if (isLoop(parsed.pathname)) return null

  // What a browser would really navigate to: dot segments resolved. "/..//evil.com" becomes
  // "//evil.com", which would leave the site, so the SAME checks run on the normalized form.
  const normalized = `${parsed.pathname}${parsed.search}${parsed.hash}`
  if (!normalized.startsWith('/') || looksExternal(normalized)) return null
  try {
    if (looksExternal(decodeURIComponent(normalized))) return null
  } catch {
    return null
  }
  return normalized
}

/** The sign-in address that comes back to `target` afterwards (if it is a safe route). */
export function loginPathWithReturn(target: string): string {
  const safe = safeReturnTo(target)
  // Going to the panel is what happens anyway: no parameter needed.
  if (!safe || safe === sessionPaths.dashboard) return sessionPaths.login
  return `${sessionPaths.login}?${RETURN_TO_PARAM}=${encodeURIComponent(safe)}`
}
