import type { Application } from '../../api/applications'
import { es } from '../../i18n/es'
import { formatDate } from '../vacancies/format'
import './ProcessLine.css'

const names = es.applications.stages

/**
 * The line of stages of one application (HU-14). The state is told with words and a mark, never
 * with color alone. Today only "Enviada" (and "Retirada") are real; the rest is informative.
 */
export function ProcessLine({ application }: { application: Application }) {
  const sentAt =
    application.history.find((event) => event.status === 'SUBMITTED')?.at ?? application.submittedAt
  const withdrawn = application.status === 'WITHDRAWN'
  const known = application.status === 'SUBMITTED' || withdrawn

  return (
    <ol className="process-line">
      <li data-state={known && !withdrawn ? 'now' : 'done'}>
        <span className="process-line__name">{names.SUBMITTED}</span>
        <small>{formatDate(sentAt)}</small>
      </li>
      {withdrawn && (
        <li data-state="now">
          <span className="process-line__name">{names.WITHDRAWN}</span>
          <small>
            {formatDate(application.withdrawnAt ?? application.history.at(-1)?.at ?? '')}
          </small>
        </li>
      )}
      {!known && (
        <li data-state="now">
          <span className="process-line__name">{names.unknown}</span>
        </li>
      )}
      {!withdrawn &&
        names.next.map((stage) => (
          <li key={stage} data-state="soon">
            <span className="process-line__name">{stage}</span>
          </li>
        ))}
    </ol>
  )
}
