import type { CatalogSkill } from '../../api/profile'

/** Lower case and without accents, so that "Jávascript" and "javascript" are the same text. */
export function normalizeSearch(text: string): string {
  return text.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().trim()
}

export interface SkillMatch {
  skill: CatalogSkill
  /** The synonym that matched, when the name itself did not ("JS" for JavaScript). */
  alias: string | null
}

/**
 * Searches the catalog by name or synonym, ignoring case and accents (006-16). An exact name or synonym comes first, then names that start
 * with the text, then names that contain it, then synonyms; each group keeps the
 * order of the catalog (alphabetical). Skills in `exclude` are hidden. Empty text lists all.
 */
export function searchSkills(
  catalog: readonly CatalogSkill[],
  query: string,
  exclude: ReadonlySet<string>,
): SkillMatch[] {
  const text = normalizeSearch(query)
  const available = catalog.filter((skill) => !exclude.has(skill.slug))
  if (text === '') return available.map((skill) => ({ skill, alias: null }))

  const exact: SkillMatch[] = []
  const starts: SkillMatch[] = []
  const contains: SkillMatch[] = []
  const synonyms: SkillMatch[] = []
  for (const skill of available) {
    const name = normalizeSearch(skill.name)
    const exactAlias = skill.aliases.find((item) => normalizeSearch(item) === text)
    if (name === text) exact.push({ skill, alias: null })
    else if (exactAlias !== undefined) exact.push({ skill, alias: exactAlias })
    else if (name.startsWith(text)) starts.push({ skill, alias: null })
    else if (name.includes(text)) contains.push({ skill, alias: null })
    else {
      const alias = skill.aliases.find((item) => normalizeSearch(item).includes(text))
      if (alias !== undefined) synonyms.push({ skill, alias })
    }
  }
  return [...exact, ...starts, ...contains, ...synonyms]
}
