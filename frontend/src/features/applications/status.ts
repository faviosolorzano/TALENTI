import type { ApplicationStatus } from '../../api/applications'
import { es } from '../../i18n/es'

/** "Enviada", "Retirada" or, for any state of the next stage (E3), "En proceso". */
export function statusLabel(status: ApplicationStatus): string {
  const names = es.applications.stages
  if (status === 'SUBMITTED') return names.SUBMITTED
  if (status === 'WITHDRAWN') return names.WITHDRAWN
  return names.unknown
}
