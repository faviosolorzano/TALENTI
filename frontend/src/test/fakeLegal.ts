import { vi } from 'vitest'
import type { LegalCatalog } from '../api/legal'

/**
 * In-memory stand-in for the legal endpoints (spec 001/002) for component tests. The shapes are
 * those of `GET /legal/current`, `GET /auth/me` and `POST /legal/accept`; the real behavior is
 * tested in the backend.
 */

export function json(status: number, body?: unknown): Response {
  return new Response(body === undefined ? null : JSON.stringify(body), {
    status,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
  })
}

/** A catalog like the real one: three documents, headings numbered, an opening text and a table. */
export function sampleCatalog(overrides: Partial<LegalCatalog> = {}): LegalCatalog {
  return {
    version: 'v1',
    publishedAt: '2026-10-11T00:00:00Z',
    documents: [
      {
        key: 'terms',
        title: 'Términos y Condiciones de Talenti',
        sections: [
          { heading: '', body: 'Versión v1 · Última actualización: [fecha de publicación]' },
          { heading: '1. Quiénes somos', body: 'El servicio lo presta [titular del servicio].' },
          { heading: '2. Qué aceptas', body: 'Aceptas estos Términos y la Política.' },
        ],
      },
      {
        key: 'privacy',
        title: 'Política de Privacidad de Talenti',
        sections: [
          { heading: '', body: 'Versión v1 · Última actualización: [fecha de publicación]' },
          {
            heading: '1. Quién es el responsable',
            body: 'Contacto: [correo de contacto]. Es **muy importante**.',
          },
          {
            heading: '2. Qué datos tratamos',
            body: [
              '- Tu correo',
              '- Tu perfil',
              '',
              '| Categoría | Ejemplos | De dónde vienen | Un texto bastante largo para ensanchar |',
              '|---|---|---|---|',
              '| Cuenta | Correo, contraseña | De ti | Mucho texto para que la tabla no entre en la pantalla |',
            ].join('\n'),
          },
          {
            heading: '3. Cookies',
            body: 'Lee la [Política de Cookies](/cookies) y la [guía](https://example.org/guia).',
          },
        ],
      },
      {
        key: 'cookies',
        title: 'Política de Cookies de Talenti',
        sections: [{ heading: 'Qué son', body: 'Pequeños archivos.' }],
      },
      {
        key: 'consent-account',
        title: 'Consentimiento',
        sections: [{ heading: 'Datos', body: 'Tu correo.' }],
      },
    ],
    ...overrides,
  }
}

export interface FakeLegalApi {
  fetch: ReturnType<typeof vi.fn>
  /** Method and path of every call, for example `GET /legal/current`. */
  calls: () => string[]
  /** Body of the calls to `POST /legal/accept`. */
  acceptBodies: () => unknown[]
}

export interface FakeLegalOptions {
  /** Catalog answered by `GET /legal/current`, or a function to answer each call differently. */
  catalog?: LegalCatalog | (() => LegalCatalog)
  /** Status forced for `GET /legal/current` (for example 500; 0 = no network). */
  catalogStatus?: number | (() => number)
  /** `legalAcceptancePending` of `GET /auth/me`; `null` = no session (401). */
  pending?: boolean | null
  /** Status of `POST /legal/accept` (default 204). Can be a function to change per call. */
  acceptStatus?: number | (() => number)
  /** Status of `POST /auth/logout` (default 204). */
  logoutStatus?: number
  /** The first N POSTs to `/legal/accept` and `/auth/logout` answer 403, like a stale CSRF token. */
  forbiddenFirst?: number
}

const resolve = <T>(value: T | (() => T)): T =>
  typeof value === 'function' ? (value as () => T)() : value

/** Replaces `fetch` with the fake legal API. */
export function mockLegalApi(options: FakeLegalOptions = {}): FakeLegalApi {
  const calls: string[] = []
  const acceptBodies: unknown[] = []
  let forbidden = options.forbiddenFirst ?? 0
  const handler = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(String(input), 'http://localhost')
    const method = (init?.method ?? 'GET').toUpperCase()
    calls.push(`${method} ${url.pathname}`)

    if (method === 'GET' && url.pathname === '/api/v1/legal/current') {
      const status = resolve(options.catalogStatus ?? 200)
      if (status === 0) throw new TypeError('network failure')
      return status === 200
        ? json(200, resolve(options.catalog ?? sampleCatalog()))
        : json(status, { title: 'error' })
    }
    if (method === 'GET' && url.pathname === '/api/v1/auth/me') {
      if (options.pending === null) return json(401, { title: 'Unauthorized' })
      return json(200, {
        id: '1',
        email: 'ana@example.org',
        role: 'STUDENT',
        legalAcceptancePending: options.pending ?? true,
      })
    }
    const isGuardedPost =
      method === 'POST' &&
      (url.pathname === '/api/v1/legal/accept' || url.pathname === '/api/v1/auth/logout')
    if (isGuardedPost && forbidden > 0) {
      forbidden -= 1
      return json(403, { title: 'Forbidden' })
    }
    if (method === 'POST' && url.pathname === '/api/v1/legal/accept') {
      acceptBodies.push(JSON.parse(String(init?.body ?? 'null')))
      const status = resolve(options.acceptStatus ?? 204)
      return status === 204 ? json(204) : json(status, { title: 'error' })
    }
    if (method === 'POST' && url.pathname === '/api/v1/auth/logout') {
      return json(options.logoutStatus ?? 204)
    }
    if (url.pathname === '/api/v1/auth/csrf') return json(204)
    // Anything else (for example the vacancies of the landing page) fails like a dead network.
    throw new TypeError('not mocked')
  })
  vi.stubGlobal('fetch', handler)
  return { fetch: handler, calls: () => calls, acceptBodies: () => acceptBodies }
}
