import { ApiError } from '../../api/client'
import type { UnreadableReason } from '../../api/cv'
import { es } from '../../i18n/es'

const t = es.cv.errors

const UNREADABLE: readonly UnreadableReason[] = [
  'NOT_A_PDF',
  'CORRUPT',
  'ENCRYPTED',
  'TOO_MANY_PAGES',
  'NO_TEXT',
]

/** A date like "1 de noviembre", from the ISO date the server sends. */
export function longDate(iso: unknown): string {
  const date = typeof iso === 'string' ? new Date(iso) : null
  if (!date || Number.isNaN(date.getTime())) return 'el próximo mes'
  return new Intl.DateTimeFormat('es-PE', {
    day: 'numeric',
    month: 'long',
    timeZone: 'America/Lima',
  }).format(date)
}

/** The words for a failed upload or read request. Never the raw text of the server. */
export function uploadErrorMessage(error: unknown): string {
  if (!(error instanceof ApiError)) return t.generic
  if (error.status === 0) return es.errors.network
  const code = error.problem?.code
  if (code === 'cv-invalid-type') return t.notPdf
  if (code === 'cv-too-large') return t.tooLarge
  if (code === 'cv-unreadable') {
    const reason = error.problem?.reason as UnreadableReason
    return UNREADABLE.includes(reason) ? t[reason] : t.invalid
  }
  if (code === 'validation-failed') return t.invalid
  if (code === 'cv-limit-reached') return t.limit
  if (code === 'ai-quota-reached') {
    const limit = typeof error.problem?.limit === 'number' ? error.problem.limit : 3
    return t.quota(limit, longDate(error.problem?.resetsAt))
  }
  return t.generic
}

/** The upload failed because the PDF has no text (a scan): a good moment to offer "a mano". */
export const isScanned = (error: unknown) =>
  error instanceof ApiError && error.problem?.reason === 'NO_TEXT'
