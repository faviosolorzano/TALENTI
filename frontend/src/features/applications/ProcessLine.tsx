import type { Application } from '../../api/applications'
import { es } from '../../i18n/es'
import { formatDate } from '../vacancies/format'
import { statusLabel } from './status'
import './ProcessLine.css'

/** Only dated facts returned by the service; no future hiring stages. */
export function ProcessLine({ application }: { application: Application }) {
  const events = application.history.length
    ? [...application.history].sort((a, b) => a.at.localeCompare(b.at))
    : [{ status: 'SUBMITTED', at: application.submittedAt }]
  return (
    <>
      <ol className="process-line">
        {events.map((event, index) => (
          <li
            key={`${event.status}-${event.at}-${index}`}
            data-state={index === events.length - 1 ? 'now' : 'done'}
          >
            <span className="process-line__name">{statusLabel(event.status)}</span>
            <small>{formatDate(event.at)}</small>
          </li>
        ))}
      </ol>
      <p>{es.applications.noNewEvents}</p>
    </>
  )
}
