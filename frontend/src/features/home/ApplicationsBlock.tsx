import { Link } from 'react-router'
import { fetchApplicationSummary } from '../../api/applications'
import { Button } from '../../components/Button'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { statusLabel } from '../applications/status'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import { formatDate } from '../vacancies/format'
import '../vacancies/Skeletons.css'
import { HomeBlock } from './HomeBlock'

const t = es.home.applications

/**
 * "Tus postulaciones" (005-12): how many are active and the latest one, from
 * `GET /applications/summary`. Without applications it invites to look at the vacancies.
 */
export function ApplicationsBlock() {
  const summary = useApiResource('home-applications', (signal) => fetchApplicationSummary(signal))
  const state = summary.state

  return (
    <HomeBlock title={t.title} busy={state.status === 'loading'}>
      {state.status === 'loading' && (
        <div>
          <p className="visually-hidden" role="status">
            {t.loading}
          </p>
          <span className="skeleton skeleton--heading" aria-hidden="true" />
        </div>
      )}
      {state.status === 'error' && <ErrorNotice message={t.error} onRetry={summary.retry} />}
      {state.status === 'success' && state.data.latest === null && (
        <>
          <p className="home-block__text">{t.none}</p>
          <div>
            <Button to="/oportunidades" size="sm">
              {t.seeOpportunities}
            </Button>
          </div>
        </>
      )}
      {state.status === 'success' && state.data.latest !== null && (
        <>
          <p className="home-block__text">
            <strong>{t.active(state.data.active)}</strong>
          </p>
          <p className="home-block__text">
            {t.latest}:{' '}
            <Link to={`${sessionPaths.applications}/${state.data.latest.id}`}>
              {state.data.latest.vacancy.title}
            </Link>{' '}
            · {statusLabel(state.data.latest.status)} ·{' '}
            {formatDate(state.data.latest.withdrawnAt ?? state.data.latest.submittedAt)}
          </p>
          <div>
            <Button to={sessionPaths.applications} variant="quiet" size="sm">
              {t.seeAll}
            </Button>
          </div>
        </>
      )}
    </HomeBlock>
  )
}
