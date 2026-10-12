import { useId } from 'react'
import { fetchRecentVacancies } from '../../api/vacancies'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { VacancyListSkeleton } from './Skeletons'
import { VacancyCard } from './VacancyCard'
import './RecentVacancies.css'

/** Vacancies offered when a search finds nothing (spec 004-33). */
const RECENT_LIMIT = 3

/**
 * "Vacantes recientes que pueden interesarte": the newest open vacancies, with no text or
 * filters. It is mounted only while a search with text has no results, so it asks for nothing
 * otherwise. If the request fails (or there is nothing to show) the section is simply not drawn:
 * it is an extra, never a reason to break the screen.
 */
export function RecentVacancies({ from }: { from: string }) {
  const titleId = useId()
  const recent = useApiResource('recent-vacancies', (signal) =>
    fetchRecentVacancies(RECENT_LIMIT, signal),
  )

  if (recent.state.status === 'error') return null
  if (recent.state.status === 'success' && recent.state.data.items.length === 0) return null

  return (
    <section className="recent-vacancies" aria-labelledby={titleId}>
      <h3 id={titleId} className="recent-vacancies__title">
        {es.opportunities.results.recentTitle}
      </h3>
      {recent.state.status === 'loading' && (
        <VacancyListSkeleton count={RECENT_LIMIT} className="recent-vacancies__list" />
      )}
      {recent.state.status === 'success' && (
        <ul
          className="recent-vacancies__list"
          aria-label={es.opportunities.results.recentListLabel}
        >
          {recent.state.data.items.slice(0, RECENT_LIMIT).map((vacancy) => (
            <li key={vacancy.id}>
              <VacancyCard vacancy={vacancy} from={from} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
