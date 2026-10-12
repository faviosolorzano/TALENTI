import { apiFetch } from './client'

/**
 * Public, read-only vacancy API (spec 004, section 6). Every call is a `GET`, so no CSRF token
 * is involved. The text a person searches for goes only in the query string of the request:
 * it is never stored by the frontend.
 */

export type Modality = 'REMOTE' | 'HYBRID' | 'ONSITE'
export const MODALITIES: readonly Modality[] = ['REMOTE', 'HYBRID', 'ONSITE']

/** `relevance` only makes sense with searched text; the server picks it by itself in that case. */
export type VacancySort = 'relevance' | 'recent' | 'company'

export interface CompanyRef {
  id: string
  name: string
  city: string
}

export interface SkillTag {
  slug: string
  name: string
  kind: 'REQUIRED' | 'DESIRABLE'
}

export interface VacancyCardData {
  id: string
  title: string
  summary: string
  modality: Modality
  /** City. `null` when the vacancy is remote. */
  location: string | null
  hoursPerWeek: number
  publishedAt: string
  featured: boolean
  company: CompanyRef
  /** Required skills first. */
  skills: SkillTag[]
}

export interface VacancyPage {
  items: VacancyCardData[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface Requirement {
  skill: { slug: string; name: string }
  /** 1 (basic) to 3 (advanced). */
  minLevel: number
}

export interface VacancyDetail extends Omit<VacancyCardData, 'company'> {
  description: string
  status: 'OPEN' | 'CLOSED'
  company: CompanyRef & { shortDescription: string }
  requirements: { required: Requirement[]; desirable: Requirement[] }
}

export interface FeaturedCompany {
  id: string
  name: string
  city: string
  shortDescription: string
  openVacancies: number
}

export interface FilterOptions {
  openCount: number
  modalities: Array<{ value: Modality; count: number }>
  locations: Array<{ value: string; count: number }>
  skills: Array<{ slug: string; name: string; count: number }>
}

/** What the person asked for, already in API terms. */
export interface VacancyQuery {
  q: string
  modality: Modality[]
  location: string[]
  skill: string[]
  /** Left out when the person has not chosen one: the server decides (relevance with text). */
  sort?: VacancySort
  page: number
  size?: number
}

/** Query string of `GET /vacancies`. Empty values and defaults are left out. */
export function buildVacancyQueryString(query: VacancyQuery): string {
  const params = new URLSearchParams()
  if (query.q) params.set('q', query.q)
  for (const value of query.modality) params.append('modality', value)
  for (const value of query.location) params.append('location', value)
  for (const value of query.skill) params.append('skill', value)
  if (query.sort) params.set('sort', query.sort)
  if (query.page > 1) params.set('page', String(query.page))
  if (query.size !== undefined) params.set('size', String(query.size))
  const text = params.toString()
  return text ? `?${text}` : ''
}

export function fetchVacancies(query: VacancyQuery, signal?: AbortSignal): Promise<VacancyPage> {
  return apiFetch<VacancyPage>(`/vacancies${buildVacancyQueryString(query)}`, { signal })
}

/** The newest open vacancies, without text or filters (suggestions when a search finds nothing). */
export function fetchRecentVacancies(limit: number, signal?: AbortSignal): Promise<VacancyPage> {
  return fetchVacancies(
    { q: '', modality: [], location: [], skill: [], sort: 'recent', page: 1, size: limit },
    signal,
  )
}

export function fetchVacancy(id: string, signal?: AbortSignal): Promise<VacancyDetail> {
  return apiFetch<VacancyDetail>(`/vacancies/${encodeURIComponent(id)}`, { signal })
}

export async function fetchFeaturedVacancies(
  limit?: number,
  signal?: AbortSignal,
): Promise<VacancyCardData[]> {
  const suffix = limit === undefined ? '' : `?limit=${limit}`
  const body = await apiFetch<{ items: VacancyCardData[] }>(`/vacancies/featured${suffix}`, {
    signal,
  })
  return body.items
}

export async function fetchFeaturedCompanies(
  limit?: number,
  signal?: AbortSignal,
): Promise<FeaturedCompany[]> {
  const suffix = limit === undefined ? '' : `?limit=${limit}`
  const body = await apiFetch<{ items: FeaturedCompany[] }>(`/companies/featured${suffix}`, {
    signal,
  })
  return body.items
}

export function fetchFilters(signal?: AbortSignal): Promise<FilterOptions> {
  return apiFetch<FilterOptions>('/vacancies/filters', { signal })
}
