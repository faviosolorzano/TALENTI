import { useRef } from 'react'
import { Link, useSearchParams } from 'react-router'
import { fetchApplications } from '../../api/applications'
import { ApiError } from '../../api/client'
import { Button } from '../../components/Button'
import { Card } from '../../components/Card'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { useSession } from '../../session/useSession'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import { formatDate } from '../vacancies/format'
import { Pagination } from '../vacancies/Pagination'
import '../vacancies/Skeletons.css'
import { statusLabel } from './status'
import './Applications.css'

const t = es.applications

/** The page number from the address: a whole number from 1 up; anything else is the first page. */
function pageFrom(value: string | null): number {
  const page = Number(value)
  return Number.isInteger(page) && page >= 1 ? page : 1
}

/** Page /postulaciones (008-18): every application of the student, newest first. */
export function ApplicationsScreen() {
  const { refresh } = useSession()
  const [params] = useSearchParams()
  const page = pageFrom(params.get('pagina'))
  const headingRef = useRef<HTMLHeadingElement>(null)
  const resource = useApiResource(
    `applications-${page}`,
    async (signal) => {
      try {
        return await fetchApplications({ page }, signal)
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) void refresh()
        throw error
      }
    },
    { keepPrevious: true },
  )

  const state = resource.state
  return (
    <div className="container applications" aria-busy={state.status === 'loading' || undefined}>
      <div className="applications__head">
        <h1 ref={headingRef} tabIndex={-1} className="applications__title">
          {t.title}
        </h1>
        <p className="applications__lead">{t.lead}</p>
      </div>

      {state.status === 'loading' && (
        <div className="applications__list">
          <p className="visually-hidden" role="status">
            {t.loading}
          </p>
          {[0, 1, 2].map((index) => (
            <Card key={index} className="application-item" aria-hidden="true">
              <span className="skeleton skeleton--title" />
              <span className="skeleton skeleton--line" />
            </Card>
          ))}
        </div>
      )}

      {state.status === 'error' && <ErrorNotice message={t.loadError} onRetry={resource.retry} />}

      {state.status === 'success' && state.data.items.length === 0 && (
        <Card className="applications__empty">
          <p>{t.empty}</p>
          <div>
            <Button to="/oportunidades">{t.seeOpportunities}</Button>
          </div>
        </Card>
      )}

      {state.status === 'success' && state.data.items.length > 0 && (
        <>
          <ul className="applications__list" aria-label={t.listLabel}>
            {state.data.items.map((item) => (
              <li key={item.id}>
                <Card className="application-item">
                  <h2 className="application-item__title">
                    <Link to={`${sessionPaths.applications}/${item.id}`}>{item.vacancy.title}</Link>
                  </h2>
                  <p className="application-item__company">
                    {item.vacancy.company.name} · {item.vacancy.company.city}
                  </p>
                  <p className="application-item__meta">
                    <span className="application-item__status" data-status={item.status}>
                      {statusLabel(item.status)}
                    </span>
                    <span>{formatDate(item.withdrawnAt ?? item.submittedAt)}</span>
                  </p>
                </Card>
              </li>
            ))}
          </ul>
          <Pagination
            page={state.data.page}
            totalPages={state.data.totalPages}
            searchFor={(next) => (next > 1 ? `?pagina=${next}` : '')}
            onNavigate={() => headingRef.current?.focus()}
          />
        </>
      )}
    </div>
  )
}
