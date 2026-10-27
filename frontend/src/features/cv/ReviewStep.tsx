import { useEffect, useState } from 'react'
import { useBlocker } from 'react-router'
import { Dialog } from '../../components/Dialog'
import { ApiError } from '../../api/client'
import { fieldProblems } from '../../api/identity'
import {
  confirmAnalysis,
  readAgain,
  type CvAnalysis,
  type CvUsage,
  type SuggestionKind,
} from '../../api/cv'
import type { Profile } from '../../api/profile'
import { AiLabel } from '../../components/AiLabel'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import { es } from '../../i18n/es'
import { useSession } from '../../session/useSession'
import { uploadErrorMessage } from './cvErrors'
import {
  buildDecisions,
  countSaved,
  countUndecided,
  type DecisionMap,
  type RowDecision,
} from './decisions'
import { DiscardControl } from './DiscardControl'
import { SuggestionRow } from './SuggestionRow'
import './ReviewStep.css'

const t = es.cv.review
const GROUPS: readonly SuggestionKind[] = ['ACADEMIC', 'SKILL', 'PROJECT']

interface ReviewStepProps {
  analysis: CvAnalysis
  usage: CvUsage | undefined
  /** Ask the server for the analysis again (a new reading started). */
  onRefresh: () => void
  onConfirmed: (profile: Profile, savedCount: number) => void
  onDiscarded: () => void
}

/**
 * The suggestions to review, grouped. The AI suggests and the person confirms: nothing is saved
 * until the one confirmation at the bottom, and the profile percentage comes from the server.
 */
export function ReviewStep({
  analysis,
  usage,
  onRefresh,
  onConfirmed,
  onDiscarded,
}: ReviewStepProps) {
  const { refresh } = useSession()
  const isAi = analysis.source === 'AI'
  const [decisions, setDecisions] = useState<DecisionMap>({})
  const [confirmPending, setConfirmPending] = useState(false)
  const [activeSuggestion, setActiveSuggestion] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [failure, setFailure] = useState<'none' | 'fields' | 'generic'>('none')
  const [rowErrors, setRowErrors] = useState<Record<string, string>>({})

  const { suggestions } = analysis
  const toSave = countSaved(suggestions, decisions)
  const undecided = countUndecided(suggestions, decisions)

  function setDecision(id: string, next: RowDecision | undefined) {
    setDecisions((current) => ({ ...current, [id]: next }))
    setRowErrors((current) =>
      Object.fromEntries(Object.entries(current).filter(([key]) => key !== id)),
    )
  }

  const dirty = Object.values(decisions).some((value) => value !== undefined)
  const blocker = useBlocker(dirty && !saving)
  useEffect(() => {
    if (!dirty) return
    const warn = (e: BeforeUnloadEvent) => {
      e.preventDefault()
      e.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])
  async function save(allowPending = false) {
    if (undecided > 0 && !allowPending) {
      setConfirmPending(true)
      return
    }
    setConfirmPending(false)
    if (saving || toSave === 0) return
    setSaving(true)
    setFailure('none')
    setRowErrors({})
    const sent = buildDecisions(suggestions, decisions)
    try {
      const profile = await confirmAnalysis(analysis.id, sent)
      if (blocker.state === 'blocked') blocker.proceed()
      onConfirmed(profile, toSave)
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void refresh()
      // Closed somewhere else, or not ready: look at the analysis again.
      if (error instanceof ApiError && (error.status === 404 || error.status === 409)) {
        onRefresh()
        return
      }
      const found: Record<string, string> = {}
      for (const problem of fieldProblems(error)) {
        const index = /^decisions\[(\d+)\]/.exec(problem.field)?.[1]
        const id = index === undefined ? undefined : sent[Number(index)]?.suggestionId
        if (id && !found[id]) found[id] = problem.message.trim() || t.reviewFields
      }
      setRowErrors(found)
      setFailure(Object.keys(found).length > 0 ? 'fields' : 'generic')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="review">
      <header className="review__head">
        <h2 className="review__title">{t.title}</h2>
        {isAi ? (
          <p className="review__source">
            <AiLabel /> <span>{t.aiNote}</span>
          </p>
        ) : (
          <p className="review__source">
            <strong>{t.rulesLabel}</strong> <span>{t.rulesNote}</span>
          </p>
        )}
        <p className="review__decide">{t.youDecide}</p>
      </header>

      <Notices analysis={analysis} />
      <EnhancedAction analysis={analysis} usage={usage} onStarted={onRefresh} />

      {GROUPS.map((kind) => {
        const items = suggestions.filter((suggestion) => suggestion.kind === kind)
        if (items.length === 0) return null
        return (
          <section key={kind} className="review__group" aria-labelledby={`cv-group-${kind}`}>
            <h3 id={`cv-group-${kind}`} className="review__group-title">
              {t.groups[kind]} {isAi && <AiLabel />}
            </h3>
            <ul className="review__list">
              {items.map((suggestion) => (
                <SuggestionRow
                  key={suggestion.id}
                  active={activeSuggestion === suggestion.id}
                  onActivate={() =>
                    setActiveSuggestion(activeSuggestion === suggestion.id ? null : suggestion.id)
                  }
                  suggestion={suggestion}
                  decision={decisions[suggestion.id]}
                  serverError={rowErrors[suggestion.id]}
                  onChange={(next) => setDecision(suggestion.id, next)}
                />
              ))}
            </ul>
          </section>
        )
      })}

      <div className="review__save">
        {failure !== 'none' && (
          <Notice tone="error" announce="alert">
            {failure === 'fields' ? t.reviewFields : t.saveFailed}
          </Notice>
        )}
        {undecided > 0 && toSave > 0 && <p className="review__hint">{t.undecided(undecided)}</p>}
        {toSave === 0 && (
          <p className="review__hint" id="cv-save-none">
            {t.saveNone}
          </p>
        )}
        <div>
          <Button
            disabled={saving || toSave === 0}
            aria-describedby={toSave === 0 ? 'cv-save-none' : undefined}
            onClick={() => void save()}
          >
            {saving ? t.saving : failure === 'generic' ? es.common.retry : t.save(toSave)}
          </Button>
        </div>
      </div>

      {confirmPending && (
        <Dialog title={es.redesign.pendingCv} onClose={() => setConfirmPending(false)}>
          <p>{es.redesign.pendingCvBody}</p>
          <div className="profile__tools">
            <Button variant="quiet" onClick={() => setConfirmPending(false)}>
              {es.redesign.reviewPending}
            </Button>
            <Button onClick={() => void save(true)}>{es.redesign.discardPending}</Button>
          </div>
        </Dialog>
      )}
      {blocker.state === 'blocked' && !confirmPending && (
        <Dialog title={es.redesign.leaveTitle} onClose={() => blocker.reset()}>
          <p>{es.redesign.leaveBody}</p>
          <div className="profile__tools">
            <Button onClick={() => void save()} disabled={toSave === 0}>
              {es.redesign.saveLeave}
            </Button>
            <Button variant="quiet" onClick={() => blocker.proceed()}>
              {es.redesign.discard}
            </Button>
            <Button variant="quiet" onClick={() => blocker.reset()}>
              {es.redesign.stay}
            </Button>
          </div>
        </Dialog>
      )}
      <DiscardControl analysisId={analysis.id} onDiscarded={onDiscarded} />
    </div>
  )
}

/** Explanations that do not alarm: a degraded reading, a repeated file, a long CV. */
function Notices({ analysis }: { analysis: CvAnalysis }) {
  const lines: string[] = []
  if (analysis.degraded && analysis.degradedReason) {
    lines.push(t.degraded[analysis.degradedReason])
  }
  if (analysis.reused) lines.push(t.reused)
  if (analysis.inputTruncated && analysis.source === 'AI') lines.push(t.truncated)
  if (analysis.alreadyInProfile > 0) lines.push(t.alreadyInProfile(analysis.alreadyInProfile))
  if (lines.length === 0) return null
  return (
    <Notice tone="info" announce="none" className="review__notices">
      {lines.map((line) => (
        <p key={line}>{line}</p>
      ))}
    </Notice>
  )
}

/**
 * "Leer mejor con IA": only when the server says it is available, with the quota left, and
 * after saying out loud that the text goes to an external AI service (009-27).
 */
function EnhancedAction({
  analysis,
  usage,
  onStarted,
}: {
  analysis: CvAnalysis
  usage: CvUsage | undefined
  onStarted: () => void
}) {
  const e = es.cv.enhanced
  const { refresh } = useSession()
  const [asking, setAsking] = useState(false)
  const [busy, setBusy] = useState(false)
  const [problem, setProblem] = useState<string | null>(null)

  if (!usage || !usage.enhanced.available) return null
  const { used, limit } = usage.enhanced
  const left = Math.max(0, limit - used)
  const label = analysis.source === 'AI' ? e.again : e.button

  async function start() {
    setBusy(true)
    setProblem(null)
    try {
      await readAgain(analysis.id, 'ENHANCED')
      setAsking(false)
      onStarted()
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void refresh()
      if (error instanceof ApiError && error.problem?.code === 'analysis-in-progress') {
        onStarted()
        return
      }
      setProblem(uploadErrorMessage(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="review__enhanced">
      {asking ? (
        <Notice tone="info" announce="none" className="review__ask">
          <p className="review__ask-title">
            {e.confirmTitle} <AiLabel />
          </p>
          <p>{es.cv.upload.enhancedSend}</p>
          <p>{es.cv.upload.enhancedLeft(left, limit)}</p>
          <div className="review__ask-buttons">
            <Button size="sm" disabled={busy} onClick={() => void start()}>
              {e.confirmYes}
            </Button>
            <Button size="sm" variant="quiet" disabled={busy} onClick={() => setAsking(false)}>
              {e.cancel}
            </Button>
          </div>
        </Notice>
      ) : (
        <div className="review__enhanced-row">
          <Button size="sm" variant="quiet" onClick={() => setAsking(true)}>
            {label}
          </Button>
          <AiLabel />
          <span className="review__quota">{es.cv.upload.enhancedLeft(left, limit)}</span>
        </div>
      )}
      {problem && (
        <p role="alert" className="review__problem">
          {problem}
        </p>
      )}
    </div>
  )
}
