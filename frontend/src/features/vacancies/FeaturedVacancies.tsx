import { Link } from 'react-router'
import { fetchFeaturedVacancies, fetchFilters } from '../../api/vacancies'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { ErrorNotice } from './ErrorNotice'
import { SampleNote } from './SampleNote'
import { VacancyListSkeleton } from './Skeletons'
import { VacancyCard } from './VacancyCard'
import './FeaturedVacancies.css'

/** Featured vacancies shown on the home page (spec 004-15). */
const FEATURED_LIMIT = 4

/**
 * Home page section: up to four featured vacancies, the real count of open vacancies and the
 * link to all of them. A failure here does not affect the rest of the page, and without
 * featured vacancies the section is not drawn.
 */
export function FeaturedVacancies() {
  const featured = useApiResource('featured-vacancies', (signal) =>
    fetchFeaturedVacancies(FEATURED_LIMIT, signal),
  )
  const filters = useApiResource('vacancy-filters', (signal) => fetchFilters(signal))

  if (featured.state.status === 'success' && featured.state.data.length === 0) return null

  const count = filters.state.status === 'success' ? filters.state.data.openCount : null

  return (
    <section className="featured" aria-labelledby="featured-title">
      <div className="container">
        <div className="featured__header">
          <div>
            <h2 id="featured-title" className="featured__title">
              <span className="featured__eyebrow">{es.landingVacancies.featuredTitle}</span>
              {count !== null && (
                <>
                  <span className="visually-hidden">: </span>
                  <span className="featured__count">{es.landingVacancies.openCount(count)}</span>
                </>
              )}
            </h2>
            <SampleNote />
          </div>
          <Link className="featured__all" to="/oportunidades">
            {es.landingVacancies.seeAll}
            <span aria-hidden="true">→</span>
          </Link>
        </div>

        {featured.state.status === 'loading' && (
          <VacancyListSkeleton count={FEATURED_LIMIT} className="featured__grid" />
        )}
        {featured.state.status === 'error' && (
          <ErrorNotice message={es.landingVacancies.featuredError} onRetry={featured.retry} />
        )}
        {featured.state.status === 'success' && (
          <div className="featured__grid">
            {featured.state.data.map((vacancy) => (
              <VacancyCard key={vacancy.id} vacancy={vacancy} />
            ))}
          </div>
        )}
      </div>
    </section>
  )
}
