import { ApiError, apiFetch } from './client'

/**
 * Identity API (spec 001, section 6). The CSRF token is handled by `apiFetch`. Nothing here
 * stores a password, a token or an e-mail address: the session lives in the HttpOnly cookie.
 */

export type Role = 'STUDENT' | 'COMPANY' | 'ADMIN'

/** Answer of sign-up and sign-in. */
export interface SessionUser {
  id: string
  email: string
  role: Role
}

/** Answer of `GET /auth/me`. */
export interface Me extends SessionUser {
  /** True when the last accepted TERMS or ACCOUNT_DATA version is not the current one. */
  legalAcceptancePending: boolean
}

export interface RegisterInput {
  email: string
  password: string
  legalAccepted: boolean
  legalVersion: string
}

export interface LoginInput {
  email: string
  password: string
}

export const fetchMe = (signal?: AbortSignal) => apiFetch<Me>('/auth/me', { signal })

export const registerStudent = (input: RegisterInput) =>
  apiFetch<SessionUser>('/auth/register', { method: 'POST', json: input })

export const loginUser = (input: LoginInput) =>
  apiFetch<SessionUser>('/auth/login', { method: 'POST', json: input })

export const logoutUser = () => apiFetch<void>('/auth/logout', { method: 'POST' })

/** A rule broken in one field (422). `message` is the Spanish text written by the backend. */
export interface FieldProblem {
  field: string
  rule: string
  message: string
}

/** Stable codes of the backend that the screens react to. */
export const PROBLEM_CODES = {
  validationFailed: 'validation-failed',
  emailAlreadyRegistered: 'email-already-registered',
  legalVersionOutdated: 'legal-version-outdated',
  tooManyRequests: 'too-many-requests',
  invalidCredentials: 'invalid-credentials',
  sessionExpired: 'session-expired',
} as const

/** The stable `code` of an error answer, or `null`. */
export function problemCode(error: unknown): string | null {
  if (!(error instanceof ApiError)) return null
  const code = error.problem?.code
  return typeof code === 'string' ? code : null
}

/** The per-field problems of a 422 answer. Anything that does not look right is dropped. */
export function fieldProblems(error: unknown): FieldProblem[] {
  if (!(error instanceof ApiError) || error.status !== 422) return []
  const errors = error.problem?.errors
  if (!Array.isArray(errors)) return []
  return errors.flatMap((item: unknown) => {
    if (typeof item !== 'object' || item === null) return []
    const { field, rule, message } = item as Record<string, unknown>
    return typeof field === 'string' && typeof rule === 'string' && typeof message === 'string'
      ? [{ field, rule, message }]
      : []
  })
}
