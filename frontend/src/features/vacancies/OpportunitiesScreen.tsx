import { useEffect, useId, useMemo, useRef } from 'react'
import { Link, useLocation, useNavigate } from 'react-router'
import {
  fetchFilters,
  fetchVacancies,
  type Modality,
  type VacancyPage,
  type VacancySort,
} from '../../api/vacancies'
import { Button } from '../../components/Button'
import { ForYouView } from '../compatibility/ForYouView'
import { useCardCompatibility } from '../compatibility/useCardCompatibility'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { useSession } from '../../session/useSession'
import { sectionPath } from '../profile/sections'
import { ErrorNotice } from './ErrorNotice'
import { OpportunitiesIntro } from './OpportunitiesIntro'
import { FilterPanel } from './FilterPanel'
import { Pagination } from './Pagination'
import { RecentVacancies } from './RecentVacancies'
import { SearchForm } from './SearchForm'
import { VacancyListSkeleton } from './Skeletons'
import { VacancyCard } from './VacancyCard'
import {
  clearFilters,
  clearNarrowing,
  effectiveSort,
  hasActiveFilters,
  hasNarrowingFilters,
  MAX_FILTER_VALUES,
  needsCorrection,
  parseSearchParams,
  serializeSearch,
  toApiQuery,
  type SearchState,
} from './searchParams'
import './OpportunitiesScreen.css'

/** Cards drawn as skeletons while the first page loads (the API's default page size). */
const SKELETON_COUNT = 4

/** The text of the title "No encontramos vacantes para «…»" is cut when it is long. */
const MAX_SHOWN_QUERY = 40

function shortened(text: string): string {
  const letters = Array.from(text)
  return letters.length > MAX_SHOWN_QUERY ? `${letters.slice(0, MAX_SHOWN_QUERY).join('')}…` : text
}

/** What the list answers, with the text it was asked for (so the empty state never lies). */
interface Answer {
  page: VacancyPage
  q: string
}

/**
 * Public page /oportunidades. The whole search state lives in the address (`q`, `modalidad`,
 * `ubicacion`, `tecnologia`, `orden`, `pagina`), so a link can be shared and back/forward walk
 * through the searches. An invalid value in the address is ignored and the address corrected.
 */
function AllVacancies() {
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
  const results = useApiResource(
    canonical,
    (signal) =>
      fetchVacancies(toApiQuery(state), signal).then((page): Answer => ({ page, q: state.q })),
    { keepPrevious: true },
  )
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
  const answer = results.state.status === 'success' ? results.state.data : null
  const data = answer?.page ?? null
  const lastPage = data ? Math.max(1, data.totalPages) : 1
  const outOfRange = data !== null && !results.refreshing && state.page > lastPage
  useEffect(() => {
    if (outOfRange)
      navigate({ search: serializeSearch({ ...state, page: lastPage }) }, { replace: true })
  }, [outOfRange, lastPage, state, navigate])

  // One `summaries` call for the whole page of cards (student with a session only).
  const compatibility = useCardCompatibility(
    data && !outOfRange ? data.items.map((item) => item.id) : [],
  )

  const loading = results.state.status === 'loading' || outOfRange
  const filtersActive = hasActiveFilters(state)
  const empty = data !== null && !outOfRange && data.items.length === 0
  // 004-33: a search with text that finds nothing. The recent vacancies are only requested once
  // the answer is settled (not while a refresh keeps the previous list on screen).
  const noMatchesFor = empty && answer ? answer.q : ''
  const narrowing = hasNarrowingFilters(state)

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
      <OpportunitiesIntro lead={es.opportunities.lead} view="all">
        <SearchForm
          q={state.q}
          location={state.location}
          locations={filters.state.status === 'success' ? filters.state.data.locations : []}
          onSearch={(q, place) => go({ ...state, q, location: place, page: 1 })}
        />
      </OpportunitiesIntro>

      <div className="container opportunities__layout">
        <div className="opportunities__filters">
          <FilterPanel
            filters={filters.state}
            onRetryFilters={filters.retry}
            modality={state.modality}
            skill={state.skill}
            onApply={(modality, skill) => go({ ...state, modality, skill, page: 1 })}
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
                value={effectiveSort(state)}
                onChange={(event) =>
                  go({ ...state, sort: event.target.value as VacancySort, page: 1 })
                }
              >
                {state.q && <option value="relevance">{es.opportunities.sort.relevance}</option>}
                <option value="recent">{es.opportunities.sort.recent}</option>
                <option value="company">{es.opportunities.sort.company}</option>
              </select>
            </div>
          </div>

          {results.refreshing && <p role="status">{es.redesign.updating}</p>}
          {loading && (
            <VacancyListSkeleton count={SKELETON_COUNT} className="opportunities__list" />
          )}

          {results.state.status === 'error' && (
            <ErrorNotice message={es.opportunities.results.errorTitle} onRetry={results.retry} />
          )}

          {compatibility.sufficient === false && (
            <p className="opportunities__complete">
              {es.opportunities.completeLine}{' '}
              <Link to={sectionPath('skills')}>{es.opportunities.completeAction}</Link>
            </p>
          )}

          {data && !outOfRange && data.items.length > 0 && (
            <ul className="opportunities__list" aria-label={es.opportunities.results.listLabel}>
              {data.items.map((vacancy) => (
                <li key={vacancy.id}>
                  <VacancyCard
                    vacancy={vacancy}
                    from={from}
                    compatibility={compatibility.byId.get(vacancy.id)}
                  />
                </li>
              ))}
            </ul>
          )}

          {empty && noMatchesFor && (
            <div className="opportunities__empty">
              <h3>{es.opportunities.results.noMatchesTitle(shortened(noMatchesFor))}</h3>
              <ul
                className="opportunities__suggestions"
                aria-label={es.opportunities.results.suggestionsLabel}
              >
                <li>{es.opportunities.results.suggestionSpelling}</li>
                {narrowing && <li>{es.opportunities.results.suggestionFilters}</li>}
                <li>{es.opportunities.results.suggestionTechnology}</li>
              </ul>
              {narrowing && (
                <Button variant="quiet" onClick={() => go(clearNarrowing(state))}>
                  {es.opportunities.results.removeFilters}
                </Button>
              )}
            </div>
          )}

          {empty && noMatchesFor && !results.refreshing && <RecentVacancies from={from} />}

          {empty && !noMatchesFor && (
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
        </section>
      </div>
    </div>
  )
}

/**
 * Public page /oportunidades. `?vista=para-ti` shows the recommended vacancies, but only to a
 * student with a session; for anyone else the parameter is ignored (spec 007-24).
 */
export function OpportunitiesScreen() {
  const session = useSession()
  const location = useLocation()
  const wantsForYou = new URLSearchParams(location.search).get('vista') === 'para-ti'

  if (wantsForYou && session.status === 'loading') {
    return (
      <div className="opportunities">
        <div className="container opportunities__layout">
          <VacancyListSkeleton count={SKELETON_COUNT} className="opportunities__list" />
        </div>
      </div>
    )
  }
  if (wantsForYou && session.isStudent) return <ForYouView />
  return <AllVacancies />
}
