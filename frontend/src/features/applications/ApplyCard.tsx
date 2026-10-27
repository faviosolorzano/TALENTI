import { Link, useLocation } from 'react-router'
import { fetchApplications, isActiveStatus } from '../../api/applications'
import { Button } from '../../components/Button'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import { formatDate } from '../vacancies/format'
import '../vacancies/Skeletons.css'
import { statusLabel } from './status'
import './ApplyCard.css'

const t = es.applications.card

interface ApplyCardProps {
  vacancyId: string
  vacancyTitle: string
  companyName: string
}

/**
 * The application part of the vacancy detail for a student with an OPEN vacancy (008-14):
 * "Postular", "Postulaste el…" with the link to it, or "Postular de nuevo" after a withdrawal.
 * The server decides every state; this card only asks for the latest application to this vacancy.
 */
export function ApplyCard({ vacancyId }: ApplyCardProps) {
  const location = useLocation()
  const latest = useApiResource(
    `apply-card-${vacancyId}`,
    async (signal) => (await fetchApplications({ vacancyId, size: 1 }, signal)).items[0] ?? null,
  )
  if (latest.state.status === 'loading') {
    return (
      <div aria-busy="true">
        <p className="visually-hidden" role="status">
          {t.loading}
        </p>
        <span className="skeleton skeleton--heading" aria-hidden="true" />
      </div>
    )
  }
  if (latest.state.status === 'error') {
    return <ErrorNotice message={t.loadError} onRetry={latest.retry} />
  }

  const application = latest.state.data
  const active = application !== null && isActiveStatus(application.status)

  return (
    <div className="apply-card">
      {active ? (
        <>
          <p className="apply-card__title">{t.applied(formatDate(application.submittedAt))}</p>
          <p className="apply-card__status">
            {es.applications.statusLabel}: <strong>{statusLabel(application.status)}</strong>
          </p>
          <Link className="apply-card__link" to={`${sessionPaths.applications}/${application.id}`}>
            {t.view}
          </Link>
        </>
      ) : (
        <>
          {application && (
            <p className="apply-card__title">
              {t.withdrawn(formatDate(application.withdrawnAt ?? application.submittedAt))}
            </p>
          )}
          <Button to={`/oportunidades/${vacancyId}/postular`} state={location.state}>
            {application ? t.applyAgain : es.vacancyDetail.apply}
          </Button>
        </>
      )}
    </div>
  )
}
