import { useEffect, useId, useMemo, useRef } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { fetchFilters, fetchVacancies, type Modality, type VacancySort } from '../../api/vacancies'
import { Button } from '../../components/Button'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { ErrorNotice } from './ErrorNotice'
import { FilterPanel } from './FilterPanel'
import { Pagination } from './Pagination'
import { SampleFootnote, SampleNote } from './SampleNote'
import { SearchForm } from './SearchForm'
import { VacancyListSkeleton } from './Skeletons'
import { VacancyCard } from './VacancyCard'
import {
  clearFilters,
  hasActiveFilters,
  MAX_FILTER_VALUES,
  needsCorrection,
  parseSearchParams,
  serializeSearch,
  toApiQuery,
  type SearchState,
} from './searchParams'
import './OpportunitiesScreen.css'

/** Cards drawn as skeletons while the first page loads (the API's default page size). */
const SKELETON_COUNT = 9

/**
 * Public page /oportunidades. The whole search state lives in the address (`q`, `modalidad`,
 * `ubicacion`, `tecnologia`, `orden`, `pagina`), so a link can be shared and back/forward walk
 * through the searches. An invalid value in the address is ignored and the address corrected.
 */
export function OpportunitiesScreen() {
  const location = useLocation()
  const navigate = useNavigate()
  const sortId = useId()
  const headingRef = useRef<HTMLHeadingElement>(null)

  const state = useMemo(
    () => parseSearchParams(new URLSearchParams(location.search)),
    [location.search],
  )
  const canonical = serializeSearch(state)

  // The previous answer stays on screen (dimmed) while a new one arrives: the list is not emptied
  // and the title does not change on every click. Skeletons are only for the first load.
  const results = useApiResource(canonical, (signal) => fetchVacancies(toApiQuery(state), signal), {
    keepPrevious: true,
  })
  const filters = useApiResource('filters', (signal) => fetchFilters(signal))

  const go = (next: SearchState, replace = false) =>
    navigate({ search: serializeSearch(next) }, { replace })

  // 004-18: an invalid value in the address is ignored and the address is corrected.
  useEffect(() => {
    if (needsCorrection(location.search, canonical)) {
      navigate({ search: canonical }, { replace: true })
    }
  }, [location.search, canonical, navigate])

  // 004-18: a page beyond the last one leads to the last one.
  const data = results.state.status === 'success' ? results.state.data : null
  const lastPage = data ? Math.max(1, data.totalPages) : 1
  const outOfRange = data !== null && !results.refreshing && state.page > lastPage
  useEffect(() => {
    if (outOfRange)
      navigate({ search: serializeSearch({ ...state, page: lastPage }) }, { replace: true })
  }, [outOfRange, lastPage, state, navigate])

  const loading = results.state.status === 'loading' || outOfRange
  const filtersActive = hasActiveFilters(state)

  const toggleModality = (value: Modality) =>
    go({
      ...state,
      modality: state.modality.includes(value)
        ? state.modality.filter((item) => item !== value)
        : [...state.modality, value],
      page: 1,
    })
  const toggleSkill = (slug: string) =>
    go({
      ...state,
      skill: state.skill.includes(slug)
        ? state.skill.filter((item) => item !== slug)
        : [...state.skill, slug].slice(0, MAX_FILTER_VALUES),
      page: 1,
    })

  const focusResults = () => headingRef.current?.focus()
  const from = `${location.pathname}${canonical}`

  let heading: string = es.opportunities.results.searching
  if (data && !outOfRange) heading = es.opportunities.results.count(data.totalItems)
  if (results.state.status === 'error') heading = es.opportunities.results.title

  let range = ''
  if (data && !outOfRange && data.items.length > 0) {
    const first = (data.page - 1) * data.size + 1
    range = es.opportunities.results.range(first, first + data.items.length - 1, data.totalItems)
  }

  return (
    <div className="opportunities">
      <section className="opportunities__intro" aria-labelledby="opportunities-title">
        <div className="container">
          <div className="opportunities__title-row">
            <h1 id="opportunities-title" className="opportunities__title">
              {es.opportunities.title}
            </h1>
            <SampleNote />
          </div>
          <p className="opportunities__lead">{es.opportunities.lead}</p>
          <SearchForm
            q={state.q}
            location={state.location}
            locations={filters.state.status === 'success' ? filters.state.data.locations : []}
            onSearch={(q, place) => go({ ...state, q, location: place, page: 1 })}
          />
        </div>
      </section>

      <div className="container opportunities__layout">
        <div className="opportunities__filters">
          <FilterPanel
            filters={filters.state}
            onRetryFilters={filters.retry}
            modality={state.modality}
            skill={state.skill}
            onToggleModality={toggleModality}
            onToggleSkill={toggleSkill}
            onClear={() => go({ ...state, modality: [], skill: [], page: 1 })}
          />
        </div>

        <section
          className="opportunities__results"
          aria-labelledby="results-title"
          aria-busy={loading || results.refreshing}
          data-refreshing={results.refreshing}
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
            <div className="opportunities__sort">
              <label htmlFor={sortId}>{es.opportunities.sort.label}</label>
              <select
                id={sortId}
                value={state.sort}
                onChange={(event) =>
                  go({ ...state, sort: event.target.value as VacancySort, page: 1 })
                }
              >
                <option value="recent">{es.opportunities.sort.recent}</option>
                <option value="company">{es.opportunities.sort.company}</option>
              </select>
            </div>
          </div>

          {loading && (
            <VacancyListSkeleton count={SKELETON_COUNT} className="opportunities__list" />
          )}

          {results.state.status === 'error' && (
            <ErrorNotice message={es.opportunities.results.errorTitle} onRetry={results.retry} />
          )}

          {data && !outOfRange && data.items.length > 0 && (
            <ul className="opportunities__list" aria-label={es.opportunities.results.listLabel}>
              {data.items.map((vacancy) => (
                <li key={vacancy.id}>
                  <VacancyCard vacancy={vacancy} from={from} />
                </li>
              ))}
            </ul>
          )}

          {data && !outOfRange && data.items.length === 0 && (
            <div className="opportunities__empty">
              {filtersActive ? (
                <>
                  <h3>{es.opportunities.results.emptyTitle}</h3>
                  <p>{es.opportunities.results.emptyHint}</p>
                  <Button variant="quiet" onClick={() => go(clearFilters(state))}>
                    {es.opportunities.filters.clear}
                  </Button>
                </>
              ) : (
                <>
                  <h3>{es.opportunities.results.noneYetTitle}</h3>
                  <p>{es.opportunities.results.noneYetHint}</p>
                </>
              )}
            </div>
          )}

          {data && !outOfRange && (
            <Pagination
              page={data.page}
              totalPages={data.totalPages}
              searchFor={(page) => serializeSearch({ ...state, page })}
              onNavigate={focusResults}
            />
          )}

          <div className="opportunities__footnote">
            <SampleFootnote />
          </div>
        </section>
      </div>
    </div>
  )
}
