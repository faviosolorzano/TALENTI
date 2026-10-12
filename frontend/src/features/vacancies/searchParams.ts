import type { Modality, VacancyQuery, VacancySort } from '../../api/vacancies'

/**
 * The search state of /oportunidades lives in the URL (spec 004-18):
 * `q`, `modalidad`, `ubicacion`, `tecnologia`, `orden` and `pagina`.
 * Reading it is lenient (an invalid value is ignored); writing it is canonical, so the
 * address bar can be corrected without loops.
 */

export interface SearchState {
  q: string
  modality: Modality[]
  /** One city, or '' for any. */
  location: string
  skill: string[]
  sort: VacancySort
  page: number
}

export const MAX_QUERY_LENGTH = 80
export const MAX_LOCATION_LENGTH = 60
export const MAX_FILTER_VALUES = 10
const MAX_PAGE = 100_000
const SKILL_SLUG = /^[a-z0-9-]{1,40}$/

export const EMPTY_SEARCH: SearchState = {
  q: '',
  modality: [],
  location: '',
  skill: [],
  sort: 'recent',
  page: 1,
}

const MODALITY_TO_URL: Record<Modality, string> = {
  REMOTE: 'remoto',
  HYBRID: 'hibrido',
  ONSITE: 'presencial',
}
const MODALITY_ORDER: readonly Modality[] = ['REMOTE', 'HYBRID', 'ONSITE']
const SORT_TO_URL: Record<VacancySort, string> = { recent: 'recientes', company: 'empresa' }

/** Trims and collapses spaces, like the backend does. */
export function cleanText(text: string): string {
  return text.replace(/\s+/g, ' ').trim()
}

// eslint-disable-next-line no-control-regex
const CONTROL_CHARACTERS = /[\u0000-\u001f\u007f]/

function length(text: string): number {
  return Array.from(text).length
}

export function parseSearchParams(params: URLSearchParams): SearchState {
  const q = cleanText(params.get('q') ?? '')

  const modalityValues = new Set(params.getAll('modalidad').map((value) => value.trim()))
  const modality = MODALITY_ORDER.filter((value) => modalityValues.has(MODALITY_TO_URL[value]))

  const location = cleanText(params.get('ubicacion') ?? '')

  const skill = [...new Set(params.getAll('tecnologia').map((value) => value.trim()))]
    .filter((value) => SKILL_SLUG.test(value))
    .slice(0, MAX_FILTER_VALUES)

  const sortValue = params.get('orden')?.trim()
  const sort: VacancySort = sortValue === SORT_TO_URL.company ? 'company' : 'recent'

  const pageText = params.get('pagina')?.trim() ?? ''
  const pageNumber = /^\d{1,6}$/.test(pageText) ? Number(pageText) : 1
  const page = pageNumber >= 1 && pageNumber <= MAX_PAGE ? pageNumber : 1

  return {
    q: length(q) > MAX_QUERY_LENGTH || CONTROL_CHARACTERS.test(q) ? '' : q,
    modality,
    location:
      length(location) > MAX_LOCATION_LENGTH || CONTROL_CHARACTERS.test(location) ? '' : location,
    skill,
    sort,
    page,
  }
}

/** Canonical query string ("?a=b" or ""). Defaults and empty values are left out. */
export function serializeSearch(state: SearchState): string {
  const params = new URLSearchParams()
  if (state.q) params.set('q', state.q)
  for (const value of MODALITY_ORDER) {
    if (state.modality.includes(value)) params.append('modalidad', MODALITY_TO_URL[value])
  }
  if (state.location) params.set('ubicacion', state.location)
  for (const value of state.skill) params.append('tecnologia', value)
  if (state.sort !== 'recent') params.set('orden', SORT_TO_URL[state.sort])
  if (state.page > 1) params.set('pagina', String(state.page))
  const text = params.toString()
  return text ? `?${text}` : ''
}

/** True when the URL text differs from the canonical one (so the address must be corrected). */
export function needsCorrection(currentSearch: string, canonical: string): boolean {
  return new URLSearchParams(currentSearch).toString() !== canonical.replace(/^\?/, '')
}

/** Number of filters chosen in the side panel (modality and technology). */
export function panelFilterCount(state: SearchState): number {
  return state.modality.length + state.skill.length
}

/** True when anything narrows the list (text, place, modality or technology). */
export function hasActiveFilters(state: SearchState): boolean {
  return Boolean(state.q || state.location) || panelFilterCount(state) > 0
}

/** Same sort, no filters, first page. */
export function clearFilters(state: SearchState): SearchState {
  return { ...EMPTY_SEARCH, sort: state.sort }
}

export function toApiQuery(state: SearchState): VacancyQuery {
  return {
    q: state.q,
    modality: state.modality,
    location: state.location ? [state.location] : [],
    skill: state.skill,
    sort: state.sort,
    page: state.page,
  }
}
