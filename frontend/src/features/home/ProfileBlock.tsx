import { ApiError } from '../../api/client'
import { fetchProfile } from '../../api/profile'
import { Button } from '../../components/Button'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { features } from '../../app/features'
import { sessionPaths } from '../../session/sessionPaths'
import { useSession } from '../../session/useSession'
import { ProgressHeadline } from '../profile/ProgressHeadline'
import { sectionOfItem, sectionPath } from '../profile/sections'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import '../vacancies/Skeletons.css'
import { HomeBlock, SoonTag } from './HomeBlock'

const t = es.home.profile

/**
 * "Tu perfil" (005-10): the percentage the server calculated and ONE next step. Everything
 * else that is missing lives in /perfil. Until the profile slice is integrated it only says
 * "Próximamente" and calls nothing.
 */
export function ProfileBlock() {
  if (!features.profile) {
    return (
      <HomeBlock
        title={t.title}
        className="home-block--wide"
        tag={<SoonTag>{es.home.soon}</SoonTag>}
      >
        <p className="home-block__text">{es.profile.lead}</p>
      </HomeBlock>
    )
  }
  return <LiveProfileBlock />
}

function LiveProfileBlock() {
  const { refresh } = useSession()
  const resource = useApiResource('home-profile', async (signal) => {
    try {
      return await fetchProfile(signal)
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void refresh()
      throw error
    }
  })
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
