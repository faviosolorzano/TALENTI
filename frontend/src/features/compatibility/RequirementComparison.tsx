import { Link, useLocation } from 'react-router'
import type { CompatibilityItem } from '../../api/compatibility'
import { es } from '../../i18n/es'
import { itemName, itemEvidence, itemAction } from './matchFormat'

export function RequirementComparison({ items }: { items: CompatibilityItem[] }) {
  const location = useLocation()
  const unique = new Map<string, CompatibilityItem>()
  for (const item of items)
    unique.set(
      item.type === 'REQUIREMENT'
        ? `${item.skill.slug}:${item.requirementKind}`
        : item.type === 'CONDITION'
          ? item.factor
          : 'profile',
      item,
    )
  return (
    <ul className="requirement-comparison">
      {[...unique.values()]
        .sort(
          (a, b) =>
            Number(b.type === 'REQUIREMENT' && b.requirementKind === 'REQUIRED') -
            Number(a.type === 'REQUIREMENT' && a.requirementKind === 'REQUIRED'),
        )
        .map((item) => {
          const name = itemName(item)
          const outcome = item.type === 'PROFILE' ? 'UNKNOWN' : item.outcome
          const key =
            item.type === 'REQUIREMENT'
              ? item.skill.slug
              : item.type === 'CONDITION'
                ? item.factor
                : 'skills'
          const anchor = `requisito-${key}`
          const action = itemAction(
            item,
            outcome === 'MATCH'
              ? 'matches'
              : outcome === 'MISSING' || outcome === 'UNKNOWN'
                ? 'missing'
                : 'gaps',
          )
          const returnTo = encodeURIComponent(location.pathname + location.search + '#' + anchor)
          return (
            <li
              key={key}
              id={anchor}
              tabIndex={-1}
              className="comparison-row"
              data-outcome={outcome}
            >
              <div className="comparison-row__name">
                <strong>{name}</strong>
                {item.type === 'REQUIREMENT' && (
                  <small>
                    {item.requirementKind === 'REQUIRED' ? es.badge.required : es.badge.desirable}
                  </small>
                )}
              </div>
              {item.type === 'REQUIREMENT' ? (
                <>
                  <div>
                    <small>{es.redesign.ownLevel}</small>
                    <p>
                      {item.profileLevel === null
                        ? es.redesign.noData
                        : es.vacancies.levels[item.profileLevel]}
                    </p>
                  </div>
                  <svg
                    className="comparison-row__connector"
                    viewBox="0 0 200 30"
                    aria-hidden="true"
                  >
                    <path d="M4 15C70 -5 130 35 196 15" />
                    <circle cx="4" cy="15" r="3" />
                    <circle cx="196" cy="15" r="3" />
                  </svg>
                  <div>
                    <small>{es.redesign.requiredLevel}</small>
                    <p>{es.vacancies.levels[item.requiredLevel]}</p>
                  </div>
                </>
              ) : (
                <p className="comparison-row__evidence">{itemEvidence(item)}</p>
              )}
              <div className="comparison-row__status">
                <span aria-hidden="true">
                  {outcome === 'MATCH'
                    ? '✓'
                    : outcome === 'PARTIAL' || outcome === 'MISMATCH'
                      ? '!'
                      : '—'}
                </span>
                <span>{itemEvidence(item)}</span>
                {action && (
                  <Link to={`${action.to}&returnTo=${returnTo}`} state={location.state}>
                    {item.type === 'REQUIREMENT' ? es.redesign.reviewLevel : action.label}
                  </Link>
                )}
              </div>
            </li>
          )
        })}
    </ul>
  )
}
