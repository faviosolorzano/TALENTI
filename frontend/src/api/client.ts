import { es } from '../i18n/es'

/**
 * Minimal API client. Calls `/api/v1/...` with the session cookie and, for requests that
 * change data, sends the CSRF token read from the cookie.
 *
 * CSRF contract (spec 001, confirmed): the backend uses `CookieCsrfTokenRepository`. The token
 * lives in the `XSRF-TOKEN` cookie and goes back in the `X-XSRF-TOKEN` header on every request
 * that changes data. It ROTATES on sign-up, sign-in and sign-out, so it is read from the cookie
 * again before EACH request, never kept in memory. Without the cookie (first visit) it is asked
 * for with `GET /auth/csrf`; a 403 `csrf-invalid` is retried once with a fresh token.
 */

export const API_BASE_PATH = '/api/v1'
export const CSRF_COOKIE_NAME = 'XSRF-TOKEN'
export const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])
const CSRF_ENDPOINT = '/auth/csrf'
const CSRF_INVALID_CODE = 'csrf-invalid'

/** Error body format of the backend (Problem Details, RFC 9457). */
export interface ProblemDetails {
  type?: string
  title?: string
  status?: number
  detail?: string
  [key: string]: unknown
}

export class ApiError extends Error {
  readonly status: number
  readonly problem: ProblemDetails | null

  /**
   * `status` is the HTTP status, or 0 when there was no response (network failure).
   * `message` is a user-facing Spanish text, never the raw backend title.
   */
  constructor(status: number, problem: ProblemDetails | null, message: string = es.errors.generic) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

/** Reads a cookie by name. Returns `null` if it does not exist or is badly encoded. */
export function readCookie(name: string, cookieSource: string = document.cookie): string | null {
  for (const part of cookieSource.split(';')) {
    const separatorIndex = part.indexOf('=')
    if (separatorIndex === -1) continue
    if (part.slice(0, separatorIndex).trim() === name) {
      try {
        return decodeURIComponent(part.slice(separatorIndex + 1).trim())
      } catch {
        return null
      }
    }
  }
  return null
}

export interface ApiRequestOptions extends Omit<RequestInit, 'body' | 'credentials'> {
  /** Object to send as JSON. */
  json?: unknown
}

/**
 * Calls the API. `path` starts with `/` and goes after `/api/v1` (e.g. `/auth/me`).
 * Resolves with the parsed JSON body, or `undefined` when the response has no content.
 * Rejects with `ApiError` when the status is not successful.
 */
export async function apiFetch<T = unknown>(
  path: string,
  options: ApiRequestOptions = {},
): Promise<T> {
  const method = (options.method ?? 'GET').toUpperCase()
  const changesData = !SAFE_METHODS.has(method)

  if (changesData && !readCookie(CSRF_COOKIE_NAME)) {
    await requestCsrfToken(options.signal ?? undefined)
  }

  try {
    return await send<T>(path, options)
  } catch (error) {
    // The token may be stale (another tab signed in or out): ask for a new one and retry ONCE.
    if (changesData && error instanceof ApiError && error.problem?.code === CSRF_INVALID_CODE) {
      await requestCsrfToken(options.signal ?? undefined)
      return send<T>(path, options)
    }
    throw error
  }
}

/** Leaves the `XSRF-TOKEN` cookie in the browser (public, read-only endpoint). */
async function requestCsrfToken(signal?: AbortSignal): Promise<void> {
  await send<void>(CSRF_ENDPOINT, { signal })
}

async function send<T>(
  path: string,
  { json, headers, method = 'GET', ...init }: ApiRequestOptions,
): Promise<T> {
  const finalHeaders = new Headers(headers)
  finalHeaders.set('Accept', 'application/json')

  if (json !== undefined) {
    finalHeaders.set('Content-Type', 'application/json')
  }

  const upperMethod = method.toUpperCase()
  if (!SAFE_METHODS.has(upperMethod)) {
    // Read again on every call: the token rotates.
    const csrfToken = readCookie(CSRF_COOKIE_NAME)
    if (csrfToken) finalHeaders.set(CSRF_HEADER_NAME, csrfToken)
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE_PATH}${path}`, {
      ...init,
      method: upperMethod,
      headers: finalHeaders,
      // The app and the API share the origin (nginx proxies /api/), so same-origin is enough.
      credentials: 'same-origin',
      body: json === undefined ? undefined : JSON.stringify(json),
    })
  } catch (error) {
    // A request cancelled on purpose (AbortController) is not a network failure.
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, null, es.errors.network)
  }

  if (!response.ok) {
    throw new ApiError(response.status, await parseJsonSafely<ProblemDetails>(response))
  }

  if (response.status === 204) return undefined as T
  return (await parseJsonSafely<T>(response)) as T
}

async function parseJsonSafely<T>(response: Response): Promise<T | null> {
  try {
    return (await response.json()) as T
  } catch {
    return null
  }
}
