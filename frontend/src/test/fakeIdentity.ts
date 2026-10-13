import { vi } from 'vitest'
import type { Role } from '../api/identity'
import type { LegalCatalog } from '../api/legal'

/**
 * In-memory stand-in for the identity API of spec 001, for component tests. It imitates the
 * SHAPE of the real answers (status, `code`, `errors[{field, rule, message}]`) and the CSRF
 * rules (the token rotates on sign-up, sign-in and sign-out); the real behavior is tested in the
 * backend and in the end-to-end tests.
 */

export const SAMPLE_LEGAL: LegalCatalog = {
  version: 'v1',
  publishedAt: '2026-10-11T00:00:00Z',
  documents: [
    {
      key: 'consent-account',
      title: 'Consentimiento para el uso de tus datos',
      sections: [
        {
          heading: 'Qué datos recogemos',
          body: '- Tu correo y tu contraseña.\n- Lo que escribas en tu perfil.\n',
        },
        { heading: 'Para qué los usamos', body: 'Crear y proteger tu cuenta.\n' },
        {
          heading: 'Qué enviamos a proveedores externos de IA y voz',
          body: 'Nunca enviamos tu contraseña ni tu correo.\n',
        },
        { heading: 'Cuánto tiempo conservamos tus datos', body: '- El audio no se guarda.\n' },
      ],
    },
  ],
}

interface Account {
  password: string
  role: Role
  legalAcceptancePending?: boolean
}

export interface FakeIdentityOptions {
  /** E-mail of the person who already has a session when the app opens. */
  signedInAs?: string
  /** Accounts that exist (e-mail in lower case -> data). */
  accounts?: Record<string, Account>
  legal?: LegalCatalog
  /** `GET /legal/current` answers 500. */
  legalFails?: boolean
  /** The session cookie is stale: `GET /auth/me` answers 401 `session-expired`. */
  expiredSession?: boolean
  /** After this many failed sign-ins of an e-mail, even the right password answers 401 (001-10). */
  lockAfterFailures?: number
  /** Every sign-in or sign-up answers 429, like the per-origin rate limit. */
  rateLimited?: boolean
  /** Nothing answers: a network failure. */
  offline?: boolean
}

export interface RecordedRequest {
  method: string
  path: string
  body: Record<string, unknown> | null
  /** The CSRF header the request carried, or `null`. */
  csrfHeader: string | null
  /** The value of the CSRF cookie at that moment. */
  csrfCookie: string | null
}

export interface FakeIdentity {
  requests: RecordedRequest[]
  /** Requests of a method and path, in order. */
  calls: (method: string, path: string) => RecordedRequest[]
  setOptions: (changes: Partial<FakeIdentityOptions>) => void
  /** E-mail of the current session, or `null`. */
  sessionEmail: () => string | null
  /** The server forgets the session (30 minutes idle): `me` now answers `session-expired`. */
  expireSession: () => void
}

function json(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json', ...headers },
  })
}

function problem(status: number, code: string, extra: Record<string, unknown> = {}): Response {
  return json(status, { type: `urn:talenti:problem:${code}`, status, code, ...extra })
}

const validation = (...errors: Array<{ field: string; rule: string; message: string }>) =>
  problem(422, 'validation-failed', { errors })

const requiredMessage = 'Este campo es obligatorio.'

function readCsrfCookie(): string | null {
  const match = /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(document.cookie)
  return match ? decodeURIComponent(match[1] ?? '') : null
}

/**
 * Replaces `fetch`. Paths that are not identity paths go to the `fetch` that was installed
 * before (for example `mockBackend()` of the vacancies), so both fakes work together.
 */
export function mockIdentityBackend(initial: FakeIdentityOptions = {}): FakeIdentity {
  let options: FakeIdentityOptions = { ...initial }
  const requests: RecordedRequest[] = []
  let session: string | null = initial.signedInAs ? initial.signedInAs.toLowerCase() : null
  let tokenCounter = 0
  const failures = new Map<string, number>()
  const previousFetch = globalThis.fetch

  const accounts = (): Record<string, Account> => options.accounts ?? {}
  const rotateToken = () => {
    tokenCounter += 1
    document.cookie = `XSRF-TOKEN=token-${tokenCounter}; path=/`
  }

  const handler = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(String(input), 'http://localhost')
    if (!url.pathname.startsWith('/api/v1/auth') && !url.pathname.startsWith('/api/v1/legal')) {
      return previousFetch(input, init)
    }
    if (options.offline) throw new TypeError('Failed to fetch')

    const path = url.pathname.replace('/api/v1', '')
    const method = (init?.method ?? 'GET').toUpperCase()
    const headers = new Headers(init?.headers)
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : null
    requests.push({
      method,
      path,
      body,
      csrfHeader: headers.get('X-XSRF-TOKEN'),
      csrfCookie: readCsrfCookie(),
    })

    if (method === 'GET' && path === '/auth/csrf') {
      rotateToken()
      return new Response(null, { status: 204 })
    }
    if (method === 'GET' && path === '/legal/current') {
      return options.legalFails
        ? problem(500, 'internal-error')
        : json(200, options.legal ?? SAMPLE_LEGAL)
    }
    if (method === 'GET' && path === '/auth/me') {
      if (session) {
        const account = accounts()[session]
        return json(200, {
          id: '11111111-1111-4111-8111-111111111111',
          email: session,
          role: account?.role ?? 'STUDENT',
          legalAcceptancePending: account?.legalAcceptancePending ?? false,
        })
      }
      return problem(401, options.expiredSession ? 'session-expired' : 'unauthenticated')
    }

    // From here on: POST. The CSRF token must be the one in the cookie.
    const cookie = readCsrfCookie()
    if (method === 'POST' && (!cookie || headers.get('X-XSRF-TOKEN') !== cookie)) {
      return problem(403, 'csrf-invalid')
    }

    if (method === 'POST' && path === '/auth/register') {
      if (options.rateLimited) return problem(429, 'too-many-requests')
      const email = String(body?.email ?? '')
        .trim()
        .toLowerCase()
      const password = String(body?.password ?? '')
      const errors: Array<{ field: string; rule: string; message: string }> = []
      if (!email) errors.push({ field: 'email', rule: 'required', message: requiredMessage })
      else if (!email.includes('@')) {
        errors.push({
          field: 'email',
          rule: 'invalid-format',
          message: 'Escribe un correo válido, por ejemplo nombre@ejemplo.com.',
        })
      }
      if (password.length < 8) {
        errors.push({
          field: 'password',
          rule: 'min-length',
          message: 'La contraseña debe tener al menos 8 caracteres.',
        })
      }
      if (body?.legalAccepted !== true) {
        errors.push({
          field: 'legalAccepted',
          rule: 'must-be-accepted',
          message:
            'Debes aceptar los Términos y Condiciones y la Política de Privacidad para continuar.',
        })
      }
      if (errors.length > 0) return validation(...errors)
      if (body?.legalVersion !== (options.legal ?? SAMPLE_LEGAL).version) {
        return problem(409, 'legal-version-outdated')
      }
      if (accounts()[email]) return problem(409, 'email-already-registered')
      options = { ...options, accounts: { ...accounts(), [email]: { password, role: 'STUDENT' } } }
      session = email
      rotateToken()
      return json(201, { id: '11111111-1111-4111-8111-111111111111', email, role: 'STUDENT' })
    }

    if (method === 'POST' && path === '/auth/login') {
      if (options.rateLimited) return problem(429, 'too-many-requests')
      const email = String(body?.email ?? '')
        .trim()
        .toLowerCase()
      const password = String(body?.password ?? '')
      if (!email || !password) {
        return validation(
          ...(!email ? [{ field: 'email', rule: 'required', message: requiredMessage }] : []),
          ...(!password ? [{ field: 'password', rule: 'required', message: requiredMessage }] : []),
        )
      }
      const account = accounts()[email]
      const locked =
        options.lockAfterFailures !== undefined &&
        (failures.get(email) ?? 0) >= options.lockAfterFailures
      if (!account || account.password !== password || locked) {
        failures.set(email, (failures.get(email) ?? 0) + 1)
        return problem(401, 'invalid-credentials')
      }
      failures.delete(email)
      session = email
      rotateToken()
      return json(200, { id: '11111111-1111-4111-8111-111111111111', email, role: account.role })
    }

    if (method === 'POST' && path === '/auth/logout') {
      if (!session) return problem(401, 'unauthenticated')
      session = null
      rotateToken()
      return new Response(null, { status: 204 })
    }

    return problem(404, 'not-found')
  })
  vi.stubGlobal('fetch', handler)

  return {
    requests,
    calls: (method, path) =>
      requests.filter((request) => request.method === method && request.path === path),
    setOptions: (changes) => {
      options = { ...options, ...changes }
    },
    sessionEmail: () => session,
    expireSession: () => {
      session = null
      options = { ...options, expiredSession: true }
    },
  }
}

/** Removes the CSRF cookie the fake leaves in the document (call it in `afterEach`). */
export function clearCsrfCookie() {
  document.cookie = 'XSRF-TOKEN=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT'
}

/**
 * Holds back the answer of the next request whose address ends with `suffix` (to look at the
 * "sending" state). Call `release()` with the answer; it is sent to the page afterwards.
 */
export function holdNextRequest(suffix: string) {
  const real = globalThis.fetch
  let release: (response: Response) => void = () => undefined
  vi.stubGlobal(
    'fetch',
    vi.fn((input: RequestInfo | URL, init?: RequestInit) =>
      String(input).endsWith(suffix)
        ? new Promise<Response>((resolve) => (release = resolve))
        : real(input, init),
    ),
  )
  return {
    release: (response: Response) => release(response),
  }
}
