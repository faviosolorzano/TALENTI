import { Link } from 'react-router'
import {
  fetchRecommended,
  type CompatibilitySummary,
  type RecommendedItem,
} from '../../api/compatibility'
import { fetchFeaturedVacancies, type VacancyCardData } from '../../api/vacancies'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { useSession } from '../../session/useSession'
import { ApiError } from '../../api/client'
import { CompatibilityBadge } from '../compatibility/CompatibilityBadge'
import { sectionPath } from '../profile/sections'
import { FOR_YOU_SEARCH } from '../vacancies/OpportunitiesIntro'
import { CompanyAvatar } from '../vacancies/CompanyAvatar'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import '../vacancies/Skeletons.css'
import { HomeBlock } from './HomeBlock'

const t = es.home.vacancies

/** How many vacancies the Home shows (005-11, 007-22). */
const HOME_VACANCIES = 3

type HomeVacancies =
  { mode: 'forYou'; items: RecommendedItem[] } | { mode: 'featured'; items: VacancyCardData[] }

/**
 * "Vacantes para ti" (007-22): the first three recommended vacancies with the compatibility the
 * server calculated. With an insufficient profile it shows the three featured ones, with no
 * percentage, and one line that leads to the profile. The title is the only link of each tile.
 */
export function VacanciesBlock() {
  const { refresh } = useSession()
  const resource = useApiResource('home-vacancies', async (signal): Promise<HomeVacancies> => {
    try {
      const recommended = await fetchRecommended(1, HOME_VACANCIES, signal)
      if (recommended.profile.sufficient) {
        return { mode: 'forYou', items: recommended.items.slice(0, HOME_VACANCIES) }
      }
      return { mode: 'featured', items: await fetchFeaturedVacancies(HOME_VACANCIES, signal) }
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void refresh()
      throw error
    }
  })
  const { state } = resource
  const featured = state.status === 'success' && state.data.mode === 'featured'

  const tiles: Array<{ vacancy: VacancyCardData; compatibility: CompatibilitySummary | null }> =
    state.status === 'success'
      ? state.data.mode === 'forYou'
        ? state.data.items.map((item) => ({
            vacancy: item.vacancy,
            compatibility: item.compatibility,
          }))
        : state.data.items.map((vacancy) => ({ vacancy, compatibility: null }))
      : []

  return (
    <HomeBlock
      title={featured ? t.featuredTitle : t.forYouTitle}
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

      {state.status === 'success' && tiles.length === 0 && (
        <p className="home-block__text">{t.empty}</p>
      )}

      {state.status === 'success' && featured && (
        <p className="home-block__text">
          {t.completeProfile}{' '}
          <Link className="home-block__more" to={sectionPath('skills')}>
            {t.completeProfileAction}
          </Link>
        </p>
      )}

      {state.status === 'success' && tiles.length > 0 && (
        <>
          <ul className="home-vacancies" aria-label={featured ? t.listLabel : t.forYouListLabel}>
            {tiles.map(({ vacancy, compatibility }) => (
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
                <span className="home-vacancy__side">
                  {compatibility && <CompatibilityBadge summary={compatibility} variant="short" />}
                  <span className="home-vacancy__modality">
                    {es.vacancies.modality[vacancy.modality]}
                  </span>
                </span>
              </li>
            ))}
          </ul>
          <p>
            <Link
              className="home-block__more"
              to={
                featured
                  ? sessionPaths.opportunities
                  : { pathname: sessionPaths.opportunities, search: FOR_YOU_SEARCH }
              }
            >
              {featured ? t.seeAll : t.seeAllForYou}
              <span aria-hidden="true">→</span>
            </Link>
          </p>
        </>
      )}
    </HomeBlock>
  )
}
