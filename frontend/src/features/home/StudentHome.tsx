import { useEffect, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { Notice } from '../../components/Notice'
import { es } from '../../i18n/es'
import { ProfileBlock } from './ProfileBlock'
import { ApplicationsBlock, PracticeBlock } from './SoonBlocks'
import { VacanciesBlock } from './VacanciesBlock'
import './StudentHome.css'

/**
 * Home of a student with a session (spec 005): a short greeting and four blocks. Each block
 * loads by itself, so one failing does not affect the others. No name and no e-mail are shown
 * (Talenti asks for neither as a greeting).
 */
export function StudentHome() {
  const location = useLocation()
  const navigate = useNavigate()
  // "Tu cuenta está lista" is told once: the mark travels in the navigation state of the sign-up.
  const [welcome] = useState(
    () => (location.state as { welcome?: unknown } | null)?.welcome === true,
  )

  useEffect(() => {
    // Take the mark out of the history entry, so reloading the page does not show it again.
    if (welcome) navigate(location.pathname + location.search, { replace: true, state: null })
    // Only once, when the page opens.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  return (
    <div className="container home">
      {welcome && (
        <Notice tone="success" announce="status">
          <p className="notice__title">{es.home.welcomeTitle}</p>
          <p>{es.home.welcomeBody}</p>
        </Notice>
      )}

      <div className="home__head">
        <h1 className="home__title">{es.home.title}</h1>
        <p className="home__lead">{es.home.lead}</p>
      </div>

      <div className="home__grid">
        <ProfileBlock />
        <VacanciesBlock />
        <div className="home__side">
          <ApplicationsBlock />
          <PracticeBlock />
        </div>
      </div>
    </div>
  )
}
