import { apiFetch, CSRF_COOKIE_NAME, readCookie } from './client'

/** One section of a legal document. `heading` is empty for the text before the first heading. */
export interface LegalSection {
  heading: string
  /** Markdown. It is rendered by the Markdown component, which never interprets raw HTML. */
  body: string
}

export interface LegalDocument {
  key: string
  title: string
  sections: LegalSection[]
}

/** Answer of `GET /legal/current` (spec 001, 001-17 and 001-28). */
export interface LegalCatalog {
  version: string
  /** ISO date of the publication, or missing/null when the backend has none. */
  publishedAt?: string | null
  documents: LegalDocument[]
}

export type LegalDocumentKey = 'terms' | 'privacy' | 'cookies'

export class LegalDocumentMissingError extends Error {
  constructor(key: string) {
    super(`The legal document "${key}" is not in the answer`)
    this.name = 'LegalDocumentMissingError'
  }
}

function isSection(value: unknown): value is LegalSection {
  if (typeof value !== 'object' || value === null) return false
  const section = value as Record<string, unknown>
  return typeof section.heading === 'string' && typeof section.body === 'string'
}

function isDocument(value: unknown): value is LegalDocument {
  if (typeof value !== 'object' || value === null) return false
  const document = value as Record<string, unknown>
  return (
    typeof document.key === 'string' &&
    typeof document.title === 'string' &&
    Array.isArray(document.sections) &&
    document.sections.every(isSection)
  )
}

/** Loads the current legal documents. Rejects when the answer does not have the expected shape. */
export async function fetchLegalCatalog(signal?: AbortSignal): Promise<LegalCatalog> {
  const data = await apiFetch<Partial<LegalCatalog> | null>('/legal/current', { signal })
  if (!data || typeof data.version !== 'string' || !Array.isArray(data.documents)) {
    throw new LegalDocumentMissingError('catalog')
  }
  return {
    version: data.version,
    publishedAt: typeof data.publishedAt === 'string' ? data.publishedAt : null,
    documents: data.documents.filter(isDocument),
  }
}

/** One document of the current version. A missing document is an error (002-06). */
export async function fetchLegalDocument(
  key: LegalDocumentKey,
  signal?: AbortSignal,
): Promise<{ version: string; publishedAt: string | null; document: LegalDocument }> {
  const catalog = await fetchLegalCatalog(signal)
  const document = catalog.documents.find((candidate) => candidate.key === key)
  if (!document) throw new LegalDocumentMissingError(key)
  return { version: catalog.version, publishedAt: catalog.publishedAt ?? null, document }
}

/**
 * Minimal read of the session for the "new version of the documents" screen: only whether the
 * student still has to accept. The full session lives elsewhere (this screen does not depend
 * on it). Rejects with `ApiError` 401 when there is no session.
 */
export async function fetchLegalAcceptancePending(signal?: AbortSignal): Promise<boolean> {
  const me = await apiFetch<{ legalAcceptancePending?: boolean } | null>('/auth/me', { signal })
  return me?.legalAcceptancePending === true
}

/** Asks for the CSRF cookie when the browser does not have it yet (`GET /auth/csrf`). */
async function ensureCsrfCookie(): Promise<void> {
  if (!readCookie(CSRF_COOKIE_NAME)) await apiFetch('/auth/csrf')
}

/**
 * `POST /legal/accept` (001-30). The CSRF token rotates when signing in or out, so
 * `apiFetch` reads the cookie again on every call. Rejects with `ApiError`: 409 outdated
 * version, 422 not accepted, 401 no session.
 */
export async function acceptLegalDocuments(legalVersion: string): Promise<void> {
  await ensureCsrfCookie()
  await apiFetch('/legal/accept', { method: 'POST', json: { legalAccepted: true, legalVersion } })
}

/** `POST /auth/logout` (T8: signing out is always available). */
export async function logoutRequest(): Promise<void> {
  await ensureCsrfCookie()
  await apiFetch('/auth/logout', { method: 'POST' })
}
