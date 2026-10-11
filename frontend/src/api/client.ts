/**
 * Minimal API client. Calls `/api/v1/...` with the session cookie and, for requests that
 * change data, sends the CSRF token read from the cookie.
 *
 * Not used by any screen yet. The cookie and header names follow the Spring Security
 * defaults; adjust them here if the backend contract says otherwise.
 */

export const API_BASE_PATH = '/api/v1'
export const CSRF_COOKIE_NAME = 'XSRF-TOKEN'
export const CSRF_HEADER_NAME = 'X-XSRF-TOKEN'

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS'])

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

  constructor(status: number, problem: ProblemDetails | null) {
    super(problem?.title ?? `HTTP ${status}`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

/** Reads a cookie by name. Returns `null` if it does not exist. */
export function readCookie(name: string, cookieSource: string = document.cookie): string | null {
  for (const part of cookieSource.split(';')) {
    const separatorIndex = part.indexOf('=')
    if (separatorIndex === -1) continue
    if (part.slice(0, separatorIndex).trim() === name) {
      return decodeURIComponent(part.slice(separatorIndex + 1).trim())
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
  { json, headers, method = 'GET', ...init }: ApiRequestOptions = {},
): Promise<T> {
  const finalHeaders = new Headers(headers)
  finalHeaders.set('Accept', 'application/json')

  if (json !== undefined) {
    finalHeaders.set('Content-Type', 'application/json')
  }

  const upperMethod = method.toUpperCase()
  if (!SAFE_METHODS.has(upperMethod)) {
    const csrfToken = readCookie(CSRF_COOKIE_NAME)
    if (csrfToken) finalHeaders.set(CSRF_HEADER_NAME, csrfToken)
  }

  const response = await fetch(`${API_BASE_PATH}${path}`, {
    ...init,
    method: upperMethod,
    headers: finalHeaders,
    credentials: 'include',
    body: json === undefined ? undefined : JSON.stringify(json),
  })

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
