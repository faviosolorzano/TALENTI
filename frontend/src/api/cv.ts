import { apiFetch } from './client'
import type { Profile, SkillLevel } from './profile'

/**
 * CV reader API (spec 009, section 6). Everything here is sensitive: the suggestions and their
 * evidence live only in the memory of the screen, never in storage, the URL or the console.
 * The server decides every number (percentage, quota); the interface only shows them.
 */

export type CvMode = 'BASIC' | 'ENHANCED'

export type EnhancedReason = 'DISABLED' | 'PERSONAL_LIMIT' | 'GLOBAL_LIMIT'

export interface CvUsage {
  basic: { available: boolean }
  enhanced: {
    available: boolean
    reason: EnhancedReason | null
    used: number
    limit: number
    /** ISO date when the monthly quota renews. */
    resetsAt: string | null
  }
}

export type AnalysisStatus = 'PENDING' | 'ANALYZING' | 'READY' | 'FAILED'
export type DegradedReason =
  'DISABLED' | 'GLOBAL_LIMIT' | 'ALREADY_READ' | 'AI_FAILED' | 'ALREADY_ATTEMPTED'
export type FailureReason = 'TIMEOUT' | 'EXTRACTION_ERROR' | 'RESTART' | 'BUSY'
export type UnreadableReason = 'NOT_A_PDF' | 'CORRUPT' | 'ENCRYPTED' | 'TOO_MANY_PAGES' | 'NO_TEXT'

export type SuggestionKind = 'SKILL' | 'ACADEMIC' | 'PROJECT'
export type AcademicField = 'CAREER' | 'INSTITUTE' | 'CYCLE'

export interface SkillValue {
  /** `null` = a skill that is not in the catalog yet ("sin asociar"). */
  slug: string | null
  name: string
  level: SkillLevel | null
}
export interface AcademicValue {
  value: string | number
}
export interface ProjectValue {
  name: string
  description: string
  technologies: string[]
}

export interface Suggestion {
  id: string
  kind: SuggestionKind
  field: AcademicField | null
  value: SkillValue | AcademicValue | ProjectValue
  /** The phrase of the CV that backs it. */
  evidence: string
  /** What the profile has now, if anything (text). */
  currentValue: string | null
}

export interface CvAnalysis {
  id: string
  status: AnalysisStatus
  source: 'RULES' | 'AI'
  level: CvMode
  reused: boolean
  degraded: boolean
  degradedReason: DegradedReason | null
  createdAt: string
  expiresAt: string
  failureReason: FailureReason | null
  inputTruncated: boolean
  alreadyInProfile: number
  suggestions: Suggestion[]
}

export interface StartedAnalysis {
  id: string
  status: AnalysisStatus
  reused: boolean
  degraded: boolean
  degradedReason: DegradedReason | null
}

export type DecisionAction = 'ACCEPT' | 'EDIT' | 'DISCARD'

export interface Decision {
  suggestionId: string
  action: DecisionAction
  value?: unknown
}

export const fetchCvUsage = (signal?: AbortSignal) => apiFetch<CvUsage>('/cv/usage', { signal })

/** The open analysis of the person, or `null` when there is none (204). */
export async function fetchCurrentAnalysis(signal?: AbortSignal): Promise<CvAnalysis | null> {
  const analysis = await apiFetch<CvAnalysis | undefined>('/cv/analyses/current', { signal })
  return analysis ?? null
}

export function uploadCv(file: File, mode: CvMode) {
  const form = new FormData()
  form.append('file', file)
  form.append('mode', mode)
  return apiFetch<StartedAnalysis>('/cv/analyses', { method: 'POST', form })
}

export const readAgain = (id: string, mode: CvMode) =>
  apiFetch<StartedAnalysis>(`/cv/analyses/${encodeURIComponent(id)}/reads`, {
    method: 'POST',
    json: { mode },
  })

export const confirmAnalysis = (id: string, decisions: Decision[]) =>
  apiFetch<Profile>(`/cv/analyses/${encodeURIComponent(id)}/confirm`, {
    method: 'POST',
    json: { decisions },
  })

export const discardAnalysis = (id: string) =>
  apiFetch<void>(`/cv/analyses/${encodeURIComponent(id)}`, { method: 'DELETE' })
