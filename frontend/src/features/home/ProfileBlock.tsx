import type { ProfileResource } from './useProfileResource'
import { Button } from '../../components/Button'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { ProgressHeadline } from '../profile/ProgressHeadline'
import { sectionOfItem, sectionPath } from '../profile/sections'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import '../vacancies/Skeletons.css'
import { HomeBlock } from './HomeBlock'

const t = es.home.profile

/**
 * "Tu perfil" (005-10): the percentage the server calculated and ONE next step. Everything
 * else that is missing lives in /perfil.
 */
export function ProfileBlock({ resource }: { resource: ProfileResource }) {
  return <LiveProfileBlock resource={resource} />
}

function LiveProfileBlock({ resource }: { resource: ProfileResource }) {
  const { state } = resource

  if (state.status === 'loading') {
    return (
      <HomeBlock title={t.title} className="home-block--wide" busy>
        <p className="visually-hidden" role="status">
          {t.loading}
        </p>
        <div className="home-profile__skeleton" aria-hidden="true">
          <span className="skeleton skeleton--title" />
          <span className="skeleton skeleton--line" />
          <span className="skeleton skeleton--short skeleton--line" />
        </div>
      </HomeBlock>
    )
  }

  if (state.status === 'error') {
    return (
      <HomeBlock title={t.title} className="home-block--wide">
        <ErrorNotice message={t.error} onRetry={resource.retry} />
      </HomeBlock>
    )
  }

  const { completeness } = state.data
  const next = completeness.nextStep
  return (
    <HomeBlock title={t.title} className="home-block--wide">
      <div className="home-profile">
        <div className="home-profile__progress">
          <ProgressHeadline percent={completeness.percent} />
        </div>
        {next ? (
          <div className="home-profile__next">
            <p className="home-profile__caption">{t.nextStep}</p>
            <Button to={sectionPath(sectionOfItem(next))}>{es.profile.steps[next]}</Button>
          </div>
        ) : (
          <div className="home-profile__next">
            <p className="home-profile__done">{t.complete}</p>
            <Button variant="quiet" to={sessionPaths.profile}>
              {t.seeProfile}
            </Button>
          </div>
        )}
      </div>
    </HomeBlock>
  )
}
