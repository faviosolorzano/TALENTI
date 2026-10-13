import { Link } from 'react-router'
import { fetchFeaturedVacancies } from '../../api/vacancies'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { useSession } from '../../session/useSession'
import { ApiError } from '../../api/client'
import { CompanyAvatar } from '../vacancies/CompanyAvatar'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import '../vacancies/Skeletons.css'
import { HomeBlock } from './HomeBlock'

const t = es.home.vacancies

/** How many vacancies the Home shows (005-11). */
const HOME_VACANCIES = 3

/**
 * "Vacantes destacadas" (005-11): up to three compact vacancies and the link to all of them.
 * Without compatibility (spec 007) there is no percentage here. The title is the only link of
 * each vacancy.
 */
export function VacanciesBlock() {
  const { refresh } = useSession()
  const resource = useApiResource('home-featured', async (signal) => {
    try {
      return await fetchFeaturedVacancies(HOME_VACANCIES, signal)
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void refresh()
      throw error
    }
  })
  const { state } = resource

  return (
    <HomeBlock
      title={t.featuredTitle}
      className="home-block--main"
      busy={state.status === 'loading'}
    >
      {state.status === 'loading' && (
        <div className="home-vacancies__skeleton">
          <p className="visually-hidden" role="status">
            {t.loading}
          </p>
          {Array.from({ length: HOME_VACANCIES }, (_, index) => (
            <div key={index} className="home-vacancy home-vacancy--skeleton" aria-hidden="true">
              <span className="skeleton skeleton--avatar-sm" />
              <span className="home-vacancy__lines">
                <span className="skeleton skeleton--line" />
                <span className="skeleton skeleton--line skeleton--short" />
              </span>
            </div>
          ))}
        </div>
      )}

      {state.status === 'error' && <ErrorNotice message={t.error} onRetry={resource.retry} />}

      {state.status === 'success' && state.data.length === 0 && (
        <p className="home-block__text">{t.empty}</p>
      )}

      {state.status === 'success' && state.data.length > 0 && (
        <>
          <ul className="home-vacancies" aria-label={t.listLabel}>
            {state.data.slice(0, HOME_VACANCIES).map((vacancy) => (
              <li key={vacancy.id} className="home-vacancy">
                <CompanyAvatar name={vacancy.company.name} size="sm" />
                <div className="home-vacancy__body">
                  <h3 className="home-vacancy__title">
                    <Link
                      className="home-vacancy__link"
                      to={`/oportunidades/${encodeURIComponent(vacancy.id)}`}
                    >
                      {vacancy.title}
                    </Link>
                  </h3>
                  <p className="home-vacancy__meta">
                    {es.vacancies.companyAndCity(vacancy.company.name, vacancy.company.city)}
                  </p>
                </div>
                <span className="home-vacancy__modality">
                  {es.vacancies.modality[vacancy.modality]}
                </span>
              </li>
            ))}
          </ul>
          <p>
            <Link className="home-block__more" to={sessionPaths.opportunities}>
              {t.seeAll}
              <span aria-hidden="true">→</span>
            </Link>
          </p>
        </>
      )}
    </HomeBlock>
  )
}
