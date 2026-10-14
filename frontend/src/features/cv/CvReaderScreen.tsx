import { useState } from 'react'
import { fetchCvUsage } from '../../api/cv'
import type { Profile } from '../../api/profile'
import { Card } from '../../components/Card'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import '../vacancies/Skeletons.css'
import { DoneStep } from './DoneStep'
import { ReviewStep } from './ReviewStep'
import { AnalyzingStep, EmptyStep, FailedStep } from './StatusSteps'
import { UploadStep } from './UploadStep'
import { useCurrentAnalysis } from './useCurrentAnalysis'
import './CvReaderScreen.css'

/**
 * Page /perfil/cv (spec 009 with amendment 1: no separate permission, only a line of
 * information next to "Subir mi CV"). One page, one step at a time: upload, analyzing,
 * review, done. The CV and its suggestions only live in the memory of this page.
 */
export function CvReaderScreen() {
  const current = useCurrentAnalysis()
  const { analysis } = current
  const [done, setDone] = useState<{ profile: Profile; count: number } | null>(null)

  // The options of the AI reading change when an analysis does; a failure only hides that option.
  const usageKey = `cv-usage-${analysis?.id ?? 'none'}-${analysis?.status ?? ''}-${analysis?.source ?? ''}`
  const usageResource = useApiResource(usageKey, (signal) => fetchCvUsage(signal), {
    keepPrevious: true,
  })
  const usage = usageResource.state.status === 'success' ? usageResource.state.data : undefined

  let body
  if (done) {
    body = <DoneStep profile={done.profile} savedCount={done.count} />
  } else if (current.state.status === 'loading') {
    body = (
      <div className="cv-reader__loading" aria-busy="true">
        <p className="visually-hidden" role="status">
          {es.cv.loading}
        </p>
        <span className="skeleton skeleton--heading" aria-hidden="true" />
        <span className="skeleton skeleton--line" aria-hidden="true" />
        <span className="skeleton skeleton--line skeleton--short" aria-hidden="true" />
      </div>
    )
  } else if (current.state.status === 'error') {
    body = <ErrorNotice message={es.cv.loadError} onRetry={current.retry} />
  } else if (analysis === null || analysis === undefined) {
    body = <UploadStep usage={usage} onStarted={current.refresh} />
  } else if (analysis.status === 'PENDING' || analysis.status === 'ANALYZING') {
    body = <AnalyzingStep analysis={analysis} onDiscarded={current.refresh} />
  } else if (analysis.status === 'FAILED') {
    body = (
      <FailedStep analysis={analysis} onRetried={current.refresh} onDiscarded={current.refresh} />
    )
  } else if (analysis.suggestions.length === 0) {
    body = <EmptyStep analysis={analysis} onDiscarded={current.refresh} />
  } else {
    body = (
      <ReviewStep
        // A new reading brings new suggestions: the decisions start over with them.
        key={`${analysis.id}-${analysis.createdAt}-${analysis.source}-${analysis.suggestions[0]?.id}`}
        analysis={analysis}
        usage={usage}
        onRefresh={current.refresh}
        onConfirmed={(profile, count) => setDone({ profile, count })}
        onDiscarded={current.refresh}
      />
    )
  }

  return (
    <div className="container cv-reader">
      <div className="cv-reader__head">
        <Button to={sessionPaths.profile} variant="quiet" size="sm">
          {es.cv.backToProfile}
        </Button>
        <h1 className="cv-reader__title">{es.cv.title}</h1>
        <p className="cv-reader__lead">{es.cv.lead}</p>
      </div>
      <Card className="cv-reader__card">{body}</Card>
      {!done && (
        <p className="cv-reader__manual">
          <Link className="cv-reader__link" to={sessionPaths.profile}>
            {es.cv.manual}
          </Link>
        </p>
      )}
    </div>
  )
}
