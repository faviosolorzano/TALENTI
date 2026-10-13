import type { MouseEvent } from 'react'
import { useId } from 'react'
import type { Completeness } from '../../api/profile'
import { Card } from '../../components/Card'
import { es } from '../../i18n/es'
import { ProgressHeadline } from './ProgressHeadline'
import { sectionOfItem, sectionPath, type SectionKey } from './sections'
import './ProfileSummary.css'

interface ProfileSummaryProps {
  completeness: Completeness
  /** A "Qué falta" link was chosen: open that card. */
  onOpenSection: (section: SectionKey) => void
}

/**
 * Progress of the profile (006-14): the percentage and the next step come from the server.
 * "Qué falta" has one link per pending item; the next step comes first and is marked.
 */
export function ProfileSummary({ completeness, onOpenSection }: ProfileSummaryProps) {
  const t = es.profile.summary
  const titleId = useId()
  const pending = completeness.items.filter((item) => !item.done)
  // The next step first; the others keep the order of the server.
  const ordered = [
    ...pending.filter((item) => item.key === completeness.nextStep),
    ...pending.filter((item) => item.key !== completeness.nextStep),
  ]

  function follow(event: MouseEvent<HTMLAnchorElement>, section: SectionKey) {
    // Plain clicks open the card in the page; a middle click or Ctrl+click still opens the link.
    if (event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey) return
    event.preventDefault()
    onOpenSection(section)
  }

  return (
    <Card as="section" className="profile-summary" aria-labelledby={titleId}>
      <h2 id={titleId} className="profile-summary__title">
        {t.title}
      </h2>
      <ProgressHeadline percent={completeness.percent} />

      {ordered.length === 0 ? (
        <div className="profile-summary__complete">
          <span className="profile-summary__celebrate" aria-hidden="true" />
          <div>
            <p className="profile-summary__complete-title">{t.complete}</p>
            <p className="profile-summary__complete-hint">{t.completeHint}</p>
          </div>
        </div>
      ) : (
        <div className="profile-summary__missing">
          <h3 className="profile-summary__subtitle">{t.whatIsMissing}</h3>
          <ul className="profile-summary__list">
            {ordered.map((item) => {
              const section = sectionOfItem(item.key)
              const isNext = item.key === completeness.nextStep
              return (
                <li key={item.key}>
                  <a
                    className="missing-link"
                    data-next={isNext || undefined}
                    href={sectionPath(section)}
                    onClick={(event) => follow(event, section)}
                  >
                    <span className="missing-link__text">
                      {es.profile.steps[item.key]}
                      {isNext && <span className="missing-link__next">{t.next}</span>}
                    </span>
                    <span className="missing-link__points">
                      <span aria-hidden="true">{t.points(item.weight)}</span>
                      <span className="visually-hidden">, {t.pointsLabel(item.weight)}</span>
                    </span>
                  </a>
                </li>
              )
            })}
          </ul>
        </div>
      )}
    </Card>
  )
}
