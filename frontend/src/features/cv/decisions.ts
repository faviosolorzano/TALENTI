import type { AcademicValue, Decision, ProjectValue, SkillValue, Suggestion } from '../../api/cv'
import type { SkillLevel } from '../../api/profile'

/**
 * What the person decided about one suggestion. It only lives in the memory of the review
 * screen: nothing here is stored (the CV is sensitive data).
 */
export interface RowDecision {
  /** `undefined` = not decided yet (it is discarded when saving, and the person is told). */
  action?: 'ACCEPT' | 'EDIT' | 'DISCARD'
  /** Skills: the level is always chosen by the person (the CV does not say it). */
  level?: SkillLevel | null
  /** Skills: the catalog skill chosen for a suggestion that came "sin asociar" (or changed). */
  slug?: string | null
  skillName?: string
  /** Academic: the text typed when editing. */
  text?: string
  /** Project: the edited project. */
  project?: ProjectValue
}

export type DecisionMap = Record<string, RowDecision | undefined>

export const asSkill = (value: Suggestion['value']) => value as SkillValue
export const asAcademic = (value: Suggestion['value']) => value as AcademicValue
export const asProject = (value: Suggestion['value']) => value as ProjectValue

/** The skill slug in force: the one the person chose, else the one the suggestion came with. */
export function skillSlugOf(suggestion: Suggestion, decision: RowDecision | undefined) {
  return decision?.slug ?? asSkill(suggestion.value).slug
}

function academicPayload(suggestion: Suggestion, text: string): AcademicValue {
  if (suggestion.field === 'CYCLE' && /^\d+$/.test(text.trim())) {
    return { value: Number(text.trim()) }
  }
  return { value: text }
}

/** The one confirmation: only what the person accepted, edited or discarded goes in. */
export function buildDecisions(suggestions: Suggestion[], decisions: DecisionMap): Decision[] {
  return suggestions.flatMap((suggestion): Decision[] => {
    const decision = decisions[suggestion.id]
    if (!decision?.action) return []
    if (decision.action === 'DISCARD') {
      return [{ suggestionId: suggestion.id, action: 'DISCARD' }]
    }
    const base = { suggestionId: suggestion.id, action: decision.action }
    if (suggestion.kind === 'SKILL') {
      const skill = asSkill(suggestion.value)
      const slug = skillSlugOf(suggestion, decision)
      return [{ ...base, value: { slug, level: decision.level ?? skill.level } }]
    }
    if (suggestion.kind === 'ACADEMIC') {
      return decision.action === 'EDIT' && decision.text !== undefined
        ? [{ ...base, value: academicPayload(suggestion, decision.text) }]
        : [base]
    }
    return decision.action === 'EDIT' && decision.project
      ? [{ ...base, value: decision.project }]
      : [base]
  })
}

/** How many suggestions go into the profile. */
export const countSaved = (suggestions: Suggestion[], decisions: DecisionMap) =>
  suggestions.filter((suggestion) => {
    const action = decisions[suggestion.id]?.action
    return action === 'ACCEPT' || action === 'EDIT'
  }).length

/** How many have no decision yet. */
export const countUndecided = (suggestions: Suggestion[], decisions: DecisionMap) =>
  suggestions.filter((suggestion) => !decisions[suggestion.id]?.action).length

/** A short name of the suggestion, for the accessible name of its buttons. */
export function suggestionName(suggestion: Suggestion, decision?: RowDecision): string {
  if (suggestion.kind === 'SKILL') return decision?.skillName ?? asSkill(suggestion.value).name
  if (suggestion.kind === 'PROJECT')
    return decision?.project?.name ?? asProject(suggestion.value).name
  return String(decision?.text ?? asAcademic(suggestion.value).value)
}
