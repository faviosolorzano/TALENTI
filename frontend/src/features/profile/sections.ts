import type { Completeness, CompletenessKey } from '../../api/profile'
import { sessionPaths } from '../../session/sessionPaths'

/** The four cards of /perfil, in the order they are shown (spec 006-14). */
export type SectionKey = 'about' | 'academic' | 'skills' | 'availability' | 'projects'

export const SECTION_ORDER: readonly SectionKey[] = [
  'about',
  'academic',
  'skills',
  'availability',
  'projects',
]

/** Value of `?seccion=` for each card (006-20). */
const SECTION_PARAMS: Record<SectionKey, string> = {
  about: 'sobre-ti',
  academic: 'academico',
  skills: 'habilidades',
  availability: 'disponibilidad',
  projects: 'proyectos',
}

/** The card a `?seccion=` value opens; an unknown value is ignored (`null`). */
export function sectionFromParam(value: string | null): SectionKey | null {
  return SECTION_ORDER.find((key) => SECTION_PARAMS[key] === value) ?? null
}

/** The server measures five items; "Disponibilidad y preferencias" holds two of them. */
const ITEMS_OF_SECTION: Record<SectionKey, readonly CompletenessKey[]> = {
  about: ['ABOUT'],
  academic: ['ACADEMIC'],
  skills: ['SKILLS'],
  availability: ['AVAILABILITY', 'LOCATION'],
  projects: ['PROJECTS'],
}

export function sectionOfItem(item: CompletenessKey): SectionKey {
  return SECTION_ORDER.find((key) => ITEMS_OF_SECTION[key].includes(item)) ?? 'academic'
}

/** A card is "Completo" when every item of the server that belongs to it is done. */
export function isSectionDone(completeness: Completeness, section: SectionKey): boolean {
  return ITEMS_OF_SECTION[section].every(
    (key) => completeness.items.find((item) => item.key === key)?.done === true,
  )
}

/** Address that opens a card in edit mode. */
export function sectionPath(section: SectionKey): string {
  return `${sessionPaths.profile}?seccion=${SECTION_PARAMS[section]}`
}

/** Id of the card in the page. */
export function sectionDomId(section: SectionKey): string {
  return `seccion-${SECTION_PARAMS[section]}`
}

/**
 * Limits of the form fields. The SERVER decides (and answers 422); these only keep the page
 * from offering what is certain to fail. They mirror `talenti.profile.*` of the backend.
 */
export const PROFILE_LIMITS = {
  maxProjects: 10,
  displayNameMaxLength: 40,
  aboutMaxLength: 500,
  careerMaxLength: 80,
  locationMaxLength: 60,
  projectNameMaxLength: 80,
  projectDescriptionMaxLength: 500,
  technologyMaxLength: 30,
} as const
