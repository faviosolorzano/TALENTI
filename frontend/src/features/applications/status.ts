import type { ApplicationStatus } from '../../api/applications'
import { es } from '../../i18n/es'

/** Show known service states, with a neutral fallback for an unknown code. */
export function statusLabel(status: ApplicationStatus): string {
  const names = es.applications.stages
  if (status === 'SUBMITTED') return names.SUBMITTED
  if (status === 'WITHDRAWN') return names.WITHDRAWN
  if (status === 'EXAM_PENDING') return names.EXAM_PENDING
  if (status === 'EXAM_DONE') return names.EXAM_DONE
  return names.unknown
}
