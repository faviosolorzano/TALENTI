import { apiFetch } from './client'
import type { Modality } from './vacancies'

/**
 * Student profile API (spec 006, section 6). Every write answers with the WHOLE profile,
 * including the completeness the server calculated: the interface never calculates it. The
 * profile is only kept in the memory of the screen that shows it, never in storage or the URL.
 */

/** The five items the server uses to measure progress (RN-11). */
export type CompletenessKey = 'ACADEMIC' | 'SKILLS' | 'AVAILABILITY' | 'PROJECTS' | 'LOCATION'

export interface CompletenessItem {
  key: CompletenessKey
  weight: number
  done: boolean
}

export interface Completeness {
  /** 0 to 100, from the server. */
  percent: number
  items: CompletenessItem[]
  /** The pending item that weighs the most, or `null` at 100 %. */
  nextStep: CompletenessKey | null
}

/** 1 basic, 2 intermediate, 3 advanced (RN-03). */
export type SkillLevel = 1 | 2 | 3

export interface ProfileSkill {
  slug: string
  name: string
  level: SkillLevel
  /** Everything the person writes is `DECLARED` until someone verifies it. */
  evidence: 'DECLARED' | 'OBSERVED' | 'EVALUATED'
}

export interface ProfileProject {
  id: string
  name: string
  description: string
  technologies: string[]
}

export interface Profile {
  academic: { career: string | null; institute: string | null; cycle: number | null }
  skills: ProfileSkill[]
  availability: {
    hoursPerWeek: number | null
    acceptedModalities: Modality[]
    location: string | null
  }
  projects: ProfileProject[]
  completeness: Completeness
  updatedAt: string | null
}

/** A skill of the catalog (`GET /skills`, public). `aliases` are the synonyms ("JS"). */
export interface CatalogSkill {
  slug: string
  name: string
  aliases: string[]
}

/**
 * A number typed by the person. A whole number goes as a number, nothing as `null`; anything
 * else goes as the typed text so that the SERVER says it is not valid (422 `invalid`).
 */
export type TypedNumber = number | string | null

export interface AcademicInput {
  career: string
  institute: string
  cycle: TypedNumber
}

export interface SkillInput {
  slug: string
  level: SkillLevel
}

export interface AvailabilityInput {
  hoursPerWeek: TypedNumber
  acceptedModalities: Modality[]
  location: string
}

export interface ProjectInput {
  name: string
  description: string
  technologies: string[]
}

export const fetchProfile = (signal?: AbortSignal) => apiFetch<Profile>('/profile', { signal })

export const fetchSkillCatalog = (signal?: AbortSignal) =>
  apiFetch<CatalogSkill[]>('/skills', { signal })

export const saveAcademic = (input: AcademicInput) =>
  apiFetch<Profile>('/profile/academic', { method: 'PUT', json: input })

export const saveSkills = (skills: SkillInput[]) =>
  apiFetch<Profile>('/profile/skills', { method: 'PUT', json: { skills } })

export const saveAvailability = (input: AvailabilityInput) =>
  apiFetch<Profile>('/profile/availability', { method: 'PUT', json: input })

export const addProject = (input: ProjectInput) =>
  apiFetch<Profile>('/profile/projects', { method: 'POST', json: input })

export const updateProject = (id: string, input: ProjectInput) =>
  apiFetch<Profile>(`/profile/projects/${encodeURIComponent(id)}`, { method: 'PUT', json: input })

export const deleteProject = (id: string) =>
  apiFetch<Profile>(`/profile/projects/${encodeURIComponent(id)}`, { method: 'DELETE' })
