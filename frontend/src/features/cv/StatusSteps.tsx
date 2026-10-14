import { useState } from 'react'
import { ApiError } from '../../api/client'
import { readAgain, type CvAnalysis } from '../../api/cv'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { useSession } from '../../session/useSession'
import '../vacancies/Skeletons.css'
import { uploadErrorMessage } from './cvErrors'
import { DiscardControl } from './DiscardControl'
import './StatusSteps.css'

/** "Analizando tu CV…": soft, announced, and the person is free to leave (009-20). */
export function AnalyzingStep({
  analysis,
  onDiscarded,
}: {
  analysis: CvAnalysis
  onDiscarded: () => void
}) {
  const t = es.cv.analyzing
  return (
    <div className="status-step" aria-busy="true">
      <p role="status" className="status-step__title">
        {analysis.status === 'PENDING' ? t.pending : t.analyzing}
      </p>
      <div className="status-step__skeleton" aria-hidden="true">
        <span className="skeleton skeleton--heading" />
        <span className="skeleton skeleton--line" />
        <span className="skeleton skeleton--line skeleton--short" />
      </div>
      <p className="status-step__note">{t.note}</p>
      <DiscardControl analysisId={analysis.id} onDiscarded={onDiscarded} />
    </div>
  )
}

/** The reading did not finish: say why in plain words and offer retry or the manual way. */
export function FailedStep({
  analysis,
  onRetried,
  onDiscarded,
}: {
  analysis: CvAnalysis
  onRetried: () => void
  onDiscarded: () => void
}) {
  const t = es.cv.failed
  const { refresh } = useSession()
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  async function retry() {
    setBusy(true)
    setProblem(null)
    try {
      await readAgain(analysis.id, analysis.level)
      onRetried()
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void refresh()
      if (error instanceof ApiError && error.problem?.code === 'analysis-in-progress') {
        onRetried()
        return
      }
      setProblem(uploadErrorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="status-step">
      <Notice tone="error" announce="alert">
        <p className="status-step__title">{t.title}</p>
        <p>{analysis.failureReason ? t[analysis.failureReason] : t.fallback}</p>
        <p>{t.hint}</p>
      </Notice>
      {problem && (
        <p role="alert" className="status-step__problem">
          {problem}
        </p>
      )}
      <div className="status-step__buttons">
        <Button disabled={busy} onClick={() => void retry()}>
          {es.common.retry}
        </Button>
      </div>
      <DiscardControl analysisId={analysis.id} onDiscarded={onDiscarded} />
    </div>
  )
}

/** A reading with nothing to suggest is not an error (RF-21). */
export function EmptyStep({
  analysis,
  onDiscarded,
}: {
  analysis: CvAnalysis
  onDiscarded: () => void
}) {
  const t = es.cv.empty
  return (
    <div className="status-step">
      <Notice tone="info" announce="status">
        <p className="status-step__title">{t.title}</p>
        <p>{t.body}</p>
      </Notice>
      <div className="status-step__buttons">
        <Button to={sessionPaths.profile}>{t.toProfile}</Button>
      </div>
      <DiscardControl analysisId={analysis.id} onDiscarded={onDiscarded} />
    </div>
  )
}
