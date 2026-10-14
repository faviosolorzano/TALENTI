import { useId } from 'react'
import { Button } from '../../components/Button'
import { Card } from '../../components/Card'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import './CvCard.css'

/** Entry to the CV reader (spec 009): a way to fill the profile faster, never the only one. */
export function CvCard() {
  const titleId = useId()
  return (
    <Card as="section" className="cv-card" aria-labelledby={titleId}>
      <h2 id={titleId} className="cv-card__title">
        {es.profile.cv.title}
      </h2>
      <p className="cv-card__body">{es.profile.cv.body}</p>
      <div>
        <Button to={sessionPaths.cv} size="sm">
          {es.profile.cv.action}
        </Button>
      </div>
    </Card>
  )
}
