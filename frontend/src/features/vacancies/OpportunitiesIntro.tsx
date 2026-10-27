import type { ReactNode } from 'react'
import { Link, useLocation } from 'react-router'
import { useEffect } from 'react'
import { DemoNotice } from '../../components/DemoNotice'
import { es } from '../../i18n/es'
import { useSession } from '../../session/useSession'
import './OpportunitiesIntro.css'

export const FOR_YOU_SEARCH = '?vista=para-ti'
// Public query URLs only, in tab memory, scoped to the current session identity.
const remembered = new Map<string, { all: string; forYou: string }>()

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
  const location = useLocation()
  const key = session.user?.id ?? 'visitor'
  const memory = remembered.get(key) ?? { all: '', forYou: FOR_YOU_SEARCH }
  useEffect(() => {
    remembered.set(key, {
      ...(remembered.get(key) ?? { all: '', forYou: FOR_YOU_SEARCH }),
      [view]: location.search,
    })
  }, [key, view, location.search])
  return (
    <section className="opportunities__intro" aria-labelledby="opportunities-title">
      <div className="container">
        <div className="opportunities__title-row">
          <h1 id="opportunities-title" className="opportunities__title">
            {es.opportunities.title}
          </h1>
        </div>
        <p className="opportunities__lead">{lead}</p>
        <DemoNotice />
        {session.isStudent && (
          <nav className="view-switch" aria-label={t.label}>
            <Link
              className="view-switch__item"
              to={{ pathname: '/oportunidades', search: memory.all }}
              aria-current={view === 'all' ? 'page' : undefined}
            >
              {t.all}
            </Link>
            <Link
              className="view-switch__item"
              to={{ pathname: '/oportunidades', search: memory.forYou }}
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
