import type { CompatibilityItem, ConditionItem, RequirementItem } from '../../api/compatibility'
import { es } from '../../i18n/es'
import { sectionPath, type SectionKey } from '../profile/sections'

const t = es.compatibility

const level = (value: number | null) =>
  value === null ? '' : (es.vacancies.levels[value] ?? String(value))

function modalityName(value: string): string {
  return (es.vacancies.modality as Record<string, string>)[value] ?? value
}

/** `REMOTE,HYBRID` -> "Remoto, Híbrido". */
function modalityList(value: string | null): string {
  return (value ?? '').split(',').filter(Boolean).map(modalityName).join(', ')
}

/** Name of a row. */
export function itemName(item: CompatibilityItem): string {
  if (item.type === 'REQUIREMENT') return item.skill.name
  if (item.type === 'CONDITION') return t.condition.factor[item.factor]
  return t.skillsMissing
}

/** Evidence of a row, in words (never only color). Empty when there is nothing to add. */
export function itemEvidence(item: CompatibilityItem): string {
  if (item.type === 'PROFILE') return ''
  if (item.type === 'REQUIREMENT') return requirementEvidence(item)
  return conditionEvidence(item)
}

function requirementEvidence(item: RequirementItem): string {
  const asked = level(item.requiredLevel)
  return item.profileLevel === null
    ? t.requirement.absent(asked)
    : t.requirement.compared(asked, level(item.profileLevel))
}

function conditionEvidence(item: ConditionItem): string {
  const vacancy = item.vacancyValue ?? ''
  const unknown = item.outcome === 'UNKNOWN' || item.profileValue === null
  switch (item.factor) {
    case 'MODALITY':
      return unknown
        ? t.condition.modalityUnknown(modalityName(vacancy))
        : t.condition.modality(modalityName(vacancy), modalityList(item.profileValue))
    case 'LOCATION':
      return unknown
        ? t.condition.locationUnknown(vacancy)
        : t.condition.location(vacancy, item.profileValue ?? '')
    case 'AVAILABILITY':
      return unknown
        ? t.condition.hoursUnknown(Number(vacancy))
        : t.condition.hours(Number(vacancy), item.profileValue ?? '')
  }
}

export type ListKey = 'matches' | 'gaps' | 'missing'

/** Where the person fixes it (`/perfil?seccion=...`) and what the link says; `null` for a match. */
export function itemAction(
  item: CompatibilityItem,
  list: ListKey,
): { to: string; label: string } | null {
  if (list === 'matches') return null
  if (item.type === 'PROFILE') return { to: sectionPath('skills'), label: t.action.SKILLS_MISSING }
  if (item.type === 'REQUIREMENT') {
    return {
      to: sectionPath('skills'),
      label: item.outcome === 'MISSING' ? t.action.MISSING : t.action.PARTIAL,
    }
  }
  return {
    to: sectionPath('availability'),
    label: list === 'missing' ? t.action.COMPLETE : t.action.CONDITION,
  }
}

/** First profile section with missing data, for "Completar mi perfil" when information is low. */
export function firstMissingSection(missing: CompatibilityItem[]): SectionKey | null {
  const first = missing[0]
  if (!first) return null
  return first.type === 'CONDITION' ? 'availability' : 'skills'
}
