import { useEffect, useRef } from 'react'
import type { Profile } from '../../api/profile'
import { Button } from '../../components/Button'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { ProgressHeadline } from '../profile/ProgressHeadline'
import { sectionOfItem, sectionPath } from '../profile/sections'
import './StatusSteps.css'

/** After saving: the percentage the SERVER calculated, and where to go next. */
export function DoneStep({
  profile,
  returnTo,
}: {
  profile: Profile
  savedCount: number
  returnTo?: string | null
}) {
  const t = es.cv.done
  const titleRef = useRef<HTMLHeadingElement>(null)
  useEffect(() => titleRef.current?.focus({ preventScroll: false }), [])
  const { nextStep, percent } = profile.completeness
  const keepGoing = nextStep ? sectionPath(sectionOfItem(nextStep)) : sessionPaths.profile
  return (
    <div className="status-step">
      <h2 ref={titleRef} tabIndex={-1} className="status-step__title">
        {t.title}
      </h2>
      <p>{t.saved}</p>
      <ProgressHeadline percent={percent} />
      <div className="status-step__buttons">
        <Button to={returnTo ?? sessionPaths.profile}>{t.viewProfile}</Button>
        <Button to={keepGoing} variant="quiet">
          {t.keepGoing}
        </Button>
      </div>
    </div>
  )
}
