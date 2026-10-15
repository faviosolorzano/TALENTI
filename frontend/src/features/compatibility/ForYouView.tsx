import { useEffect, useRef } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { fetchRecommended } from '../../api/compatibility'
import { Button } from '../../components/Button'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import { FOR_YOU_SEARCH, OpportunitiesIntro } from '../vacancies/OpportunitiesIntro'
import { Pagination } from '../vacancies/Pagination'
import { VacancyListSkeleton } from '../vacancies/Skeletons'
import { VacancyCard } from '../vacancies/VacancyCard'
import '../vacancies/OpportunitiesScreen.css'
import { sectionPath } from '../profile/sections'
import { useSessionLost } from './useSessionLost'

const t = es.opportunities.forYou
const PAGE_SIZE = 9

function pageFrom(search: string): number {
  const text = new URLSearchParams(search).get('pagina')?.trim() ?? ''
  const number = /^\d{1,6}$/.test(text) ? Number(text) : 1
  return number >= 1 ? number : 1
}

const searchFor = (page: number) => (page > 1 ? `${FOR_YOU_SEARCH}&pagina=${page}` : FOR_YOU_SEARCH)

/**
 * "Para ti" (spec 007-24): the open vacancies ordered by the compatibility the SERVER calculated.
 * It does not combine with the search or the filters. Each piece has its own states.
 */
export function ForYouView() {
  const location = useLocation()
  const navigate = useNavigate()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const sessionLost = useSessionLost()
  const page = pageFrom(location.search)

  const resource = useApiResource(
    `recommended:${page}`,
    async (signal) => {
      try {
        return await fetchRecommended(page, PAGE_SIZE, signal)
      } catch (error) {
        sessionLost(error, true)
        throw error
      }
    },
    { keepPrevious: true },
  )

  const data = resource.state.status === 'success' ? resource.state.data : null
  const lastPage = data ? Math.max(1, data.totalPages) : 1
  const outOfRange = data !== null && !resource.refreshing && page > lastPage
  useEffect(() => {
    if (outOfRange) navigate({ search: searchFor(lastPage) }, { replace: true })
  }, [outOfRange, lastPage, navigate])

  const loading = resource.state.status === 'loading' || outOfRange
  const sufficient = data?.profile.sufficient ?? true
  const from = `${location.pathname}${searchFor(page)}`

  let heading: string = t.title
  let range = ''
  if (data && sufficient && !outOfRange && data.items.length > 0) {
    heading = `${t.title} · ${es.opportunities.results.count(data.totalItems)}`
    const first = (data.page - 1) * data.size + 1
    range = es.opportunities.results.range(first, first + data.items.length - 1, data.totalItems)
  }

  return (
    <div className="opportunities">
      <OpportunitiesIntro lead={t.lead} view="forYou" />

      <div className="container opportunities__layout opportunities__layout--single">
        <section
          className="opportunities__results"
          aria-labelledby="results-title"
          aria-busy={loading || resource.refreshing}
          data-refreshing={resource.refreshing}
        >
          <div className="opportunities__bar">
            <div aria-live="polite" aria-atomic="true">
              <h2
                id="results-title"
                ref={headingRef}
                tabIndex={-1}
                className="opportunities__count"
              >
                {heading}
              </h2>
              {range && <p className="opportunities__range">{range}</p>}
            </div>
          </div>
          <p className="opportunities__range">{t.notCombined}</p>

          {loading && <VacancyListSkeleton count={PAGE_SIZE} className="opportunities__list" />}

          {resource.state.status === 'error' && (
            <ErrorNotice message={t.error} onRetry={resource.retry} />
          )}

          {data && !sufficient && (
            <div className="opportunities__empty">
              <h3>{t.insufficientTitle}</h3>
              <p>{t.insufficientBody}</p>
              <Button to={sectionPath('skills')}>{t.action}</Button>
            </div>
          )}

          {data && sufficient && !outOfRange && data.items.length === 0 && (
            <div className="opportunities__empty">
              <h3>{t.emptyTitle}</h3>
              <p>{t.emptyHint}</p>
            </div>
          )}

          {data && sufficient && !outOfRange && data.items.length > 0 && (
            <ul className="opportunities__list" aria-label={t.listLabel}>
              {data.items.map((item) => (
                <li key={item.vacancy.id}>
                  <VacancyCard
                    vacancy={item.vacancy}
                    from={from}
                    compatibility={item.compatibility}
                  />
                </li>
              ))}
            </ul>
          )}

          {data && sufficient && !outOfRange && (
            <Pagination
              page={data.page}
              totalPages={data.totalPages}
              searchFor={searchFor}
              onNavigate={() => headingRef.current?.focus()}
            />
          )}
        </section>
      </div>
    </div>
  )
}
