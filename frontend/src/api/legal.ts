import { apiFetch } from './client'

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
 * `POST /legal/accept` (001-30). `apiFetch` reads the CSRF token again on every call and, on a
 * 403 `csrf-invalid`, asks for a new one and repeats the request once. Rejects with `ApiError`:
 * 409 outdated version, 422 not accepted, 401 no session.
 */
export async function acceptLegalDocuments(legalVersion: string): Promise<void> {
  await apiFetch('/legal/accept', { method: 'POST', json: { legalAccepted: true, legalVersion } })
}
