import { Link } from 'react-router'
import type { CompatibilityItem } from '../../api/compatibility'
import { Badge } from '../../components/Badge'
import { es } from '../../i18n/es'
import { itemAction, itemEvidence, itemName, type ListKey } from './matchFormat'
import './MatchLists.css'

const TONES: Record<ListKey, string> = { matches: 'ok', gaps: 'no', missing: 'unknown' }
const TITLES: Record<ListKey, string> = {
  matches: es.compatibility.lists.matches,
  gaps: es.compatibility.lists.gaps,
  missing: es.compatibility.lists.missing,
}

function Row({ item, list }: { item: CompatibilityItem; list: ListKey }) {
  const name = itemName(item)
  const evidence = itemEvidence(item)
  const action = itemAction(item, list)
  const partial = list === 'gaps' && item.type === 'REQUIREMENT' && item.outcome === 'PARTIAL'
  return (
    <li className="match-row" data-tone={partial ? 'mid' : TONES[list]}>
      <span className="match-row__mark" aria-hidden="true" />
      <div className="match-row__text">
        <p className="match-row__name">
          {name}
          {item.type === 'REQUIREMENT' && (
            <Badge>
              {item.requirementKind === 'REQUIRED' ? es.badge.required : es.badge.desirable}
            </Badge>
          )}
        </p>
        {evidence && <p className="match-row__evidence">{evidence}</p>}
      </div>
      {action && (
        <Link className="match-row__action" to={action.to}>
          {action.label}
          <span className="visually-hidden"> ({name})</span>
        </Link>
      )}
    </li>
  )
}

function Subgroup({
  title,
  items,
  list,
}: {
  title: string
  items: CompatibilityItem[]
  list: ListKey
}) {
  if (items.length === 0) return null
  return (
    <div className="match-lists__sub">
      <h4 className="match-lists__sub-title">{title}</h4>
      <ul className="match-lists__rows">
        {items.map((item, index) => (
          <Row key={`${item.type}-${index}-${itemName(item)}`} item={item} list={list} />
        ))}
      </ul>
    </div>
  )
}

function Section({ list, items }: { list: ListKey; items: CompatibilityItem[] }) {
  if (items.length === 0) return null
  const t = es.compatibility.lists
  const required = items.filter((i) => i.type === 'REQUIREMENT' && i.requirementKind === 'REQUIRED')
  const desirable = items.filter(
    (i) => i.type === 'REQUIREMENT' && i.requirementKind === 'DESIRABLE',
  )
  const conditions = items.filter((i) => i.type === 'CONDITION')
  const profile = items.filter((i) => i.type === 'PROFILE')
  return (
    <section className="match-lists__section" aria-label={TITLES[list]}>
      <h3 className="match-lists__title">{TITLES[list]}</h3>
      <Subgroup title={t.required} items={required} list={list} />
      <Subgroup title={t.desirable} items={desirable} list={list} />
      <Subgroup title={t.conditions} items={conditions} list={list} />
      <Subgroup title={TITLES[list]} items={profile} list={list} />
    </section>
  )
}

interface MatchListsProps {
  matches: CompatibilityItem[]
  gaps: CompatibilityItem[]
  missingInfo: CompatibilityItem[]
}

/** The three lists of a result. An empty list is not drawn (007-19). */
export function MatchLists({ matches, gaps, missingInfo }: MatchListsProps) {
  return (
    <div className="match-lists">
      <Section list="matches" items={matches} />
      <Section list="gaps" items={gaps} />
      <Section list="missing" items={missingInfo} />
    </div>
  )
}
