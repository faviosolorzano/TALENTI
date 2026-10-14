import type { ReactNode } from 'react'
import { Link } from 'react-router'
import { es } from '../../i18n/es'
import { useSession } from '../../session/useSession'
import { SampleNote } from './SampleNote'
import './OpportunitiesIntro.css'

export const FOR_YOU_SEARCH = '?vista=para-ti'

interface OpportunitiesIntroProps {
  lead: string
  view: 'all' | 'forYou'
  /** The search form (only in "Todas"). */
  children?: ReactNode
}

/** Title, lead and, for a student, the "Todas | Para ti" switch of /oportunidades. */
export function OpportunitiesIntro({ lead, view, children }: OpportunitiesIntroProps) {
  const session = useSession()
  const t = es.opportunities.view
  return (
    <section className="opportunities__intro" aria-labelledby="opportunities-title">
      <div className="container">
        <div className="opportunities__title-row">
          <h1 id="opportunities-title" className="opportunities__title">
            {es.opportunities.title}
          </h1>
          <SampleNote />
        </div>
        <p className="opportunities__lead">{lead}</p>
        {session.isStudent && (
          <nav className="view-switch" aria-label={t.label}>
            <Link
              className="view-switch__item"
              to={{ pathname: '/oportunidades' }}
              aria-current={view === 'all' ? 'page' : undefined}
            >
              {t.all}
            </Link>
            <Link
              className="view-switch__item"
              to={{ pathname: '/oportunidades', search: FOR_YOU_SEARCH }}
              aria-current={view === 'forYou' ? 'page' : undefined}
            >
              {t.forYou}
            </Link>
          </nav>
        )}
        {children}
      </div>
    </section>
  )
}
