import { apiFetch } from './client'
import type { VacancyCardData } from './vacancies'

/**
 * Compatibility API (spec 007, section 6). Students only; every answer is private (`no-store`).
 * The percentage, the level, the lists and the explanation are calculated by the SERVER: this
 * file only types them. Nothing here is stored in the browser.
 */

export type CompatibilityStatus = 'CALCULATED' | 'INSUFFICIENT'
export type CompatibilityLevel = 'HIGH' | 'MEDIUM' | 'LOW'
export type InsufficientReason = 'NO_SKILLS' | 'LOW_INFORMATION' | 'VACANCY_WITHOUT_REQUIREMENTS'

/** Result for one vacancy as it appears on a card. `percent` and `level` are null when insufficient. */
export interface CompatibilitySummary {
  vacancyId: string
  status: CompatibilityStatus
  percent: number | null
  level: CompatibilityLevel | null
  capped: boolean
}

export interface CompatibilityComponent {
  key: 'REQUIRED' | 'DESIRABLE' | 'CONDITIONS' | 'SIMILARITY'
  weight: number
  score: number | null
  evaluable: boolean
}

export type RequirementOutcome = 'MATCH' | 'PARTIAL' | 'MISSING'
export type ConditionOutcome = 'MATCH' | 'PARTIAL' | 'MISMATCH' | 'UNKNOWN'
export type ConditionFactor = 'MODALITY' | 'LOCATION' | 'AVAILABILITY'

export interface RequirementItem {
  type: 'REQUIREMENT'
  skill: { slug: string; name: string }
  requirementKind: 'REQUIRED' | 'DESIRABLE'
  /** 1 (basic) to 3 (advanced). */
  requiredLevel: number
  /** `null` when the profile does not have the skill. */
  profileLevel: number | null
  outcome: RequirementOutcome
  evidence?: 'DECLARED'
}

export interface ConditionItem {
  type: 'CONDITION'
  factor: ConditionFactor
  outcome: ConditionOutcome
  /** Modality as `REMOTE`, a city, or hours per week as text. */
  vacancyValue: string | null
  /** Accepted modalities as `REMOTE,HYBRID`, a city, or hours per week as text. */
  profileValue: string | null
}

/** "The profile has no skills" (shown in the missing-information list). */
export interface ProfileItem {
  type: 'PROFILE'
  key: 'SKILLS'
}

export type CompatibilityItem = RequirementItem | ConditionItem | ProfileItem

export interface CompatibilityExplanation {
  /** `TEMPLATE` is written by rules, not by AI. `AI` is reserved for a later slice. */
  source: 'TEMPLATE' | 'AI'
  version: string
  text: string
}

export interface CompatibilityFormula {
  version: string
  weights: { required: number; desirable: number; conditions: number; similarity: number }
  cap: number
  minEvaluableWeight: number
  similarityEnabled: boolean
}

export interface Compatibility extends CompatibilitySummary {
  reasons: InsufficientReason[]
  /** 0 to 100: how much of the information could be used. */
  informationBasis: number | null
  components: CompatibilityComponent[]
  matches: CompatibilityItem[]
  gaps: CompatibilityItem[]
  missingInfo: CompatibilityItem[]
  explanation: CompatibilityExplanation
  formula: CompatibilityFormula
}

export interface RecommendedItem {
  vacancy: VacancyCardData
  compatibility: CompatibilitySummary
}

export interface RecommendedPage {
  profile: { sufficient: boolean }
  items: RecommendedItem[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface SummariesAnswer {
  sufficient: boolean
  items: CompatibilitySummary[]
}

/** Most ids the server accepts in one `summaries` call. */
export const MAX_SUMMARY_IDS = 24

export function fetchCompatibility(vacancyId: string, signal?: AbortSignal) {
  return apiFetch<Compatibility>(`/compatibility/vacancies/${encodeURIComponent(vacancyId)}`, {
    signal,
  })
}

export function fetchRecommended(page: number, size: number, signal?: AbortSignal) {
  const params = new URLSearchParams({ size: String(size) })
  if (page > 1) params.set('page', String(page))
  return apiFetch<RecommendedPage>(`/compatibility/recommended?${params.toString()}`, { signal })
}

/** One call for the whole page of cards (never one call per card). */
export function fetchSummaries(vacancyIds: readonly string[], signal?: AbortSignal) {
  const params = new URLSearchParams()
  for (const id of vacancyIds.slice(0, MAX_SUMMARY_IDS)) params.append('vacancyId', id)
  return apiFetch<SummariesAnswer>(`/compatibility/summaries?${params.toString()}`, { signal })
}
