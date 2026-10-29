import { useEffect, useRef, useState, useSyncExternalStore } from 'react'
import { useBlocker } from 'react-router'
import { es } from '../../../i18n/es'
import type { DoraSession } from '../../../api/workflows'
import { Button } from '../../../components/Button'
import { Dialog } from '../../../components/Dialog'
import { usePrefersReducedMotion } from '../../../hooks/usePrefersReducedMotion'
import type { DoraMode } from '../../dora/modes'
import { CallIcon, ConversationOrb } from '../ConversationOrb'
import { Source } from '../shared'
import { t } from '../workflowLabels'
import { DoraCall, type CallStatus, type TranscriptEntry } from './doraCall'
import type { TurnReason } from './channel'

const c = es.doraCall
const r = es.doraRealtime

const ORB_MODE: Record<CallStatus, DoraMode> = {
  idle: 'idle',
  preparing: 'thinking',
  connecting: 'thinking',
  listening: 'listening',
  speaking: 'speaking',
  reconnecting: 'thinking',
  ending: 'thinking',
  fallback: 'idle',
  ended: 'idle',
  error: 'idle',
}

function remaining(endsAt: number, now: number) {
  const seconds = Math.max(0, Math.round((endsAt - now) / 1000))
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`
}

/**
 * Real-time conversation with DORA (012-58 to 012-66). Voice goes straight between the browser
 * and the provider; this screen shows the real state, the live transcript (D-58) and the time
 * left, and offers mute, writing, hang up, retry and the turn-based fallback.
 */
export function RealtimeConversation({
  session,
  interview,
  onEnded,
  onFallback,
}: {
  session: DoraSession
  interview: boolean
  onEnded: () => void
  onFallback: (log: TranscriptEntry[], reason: TurnReason | null) => void
}) {
  // One call per mounted screen (the parent keys it by session). `attach` and `dispose` follow
  // the mount, so leaving, changing route or unmounting always releases everything (012-65).
  const [call] = useState(
    () => new DoraCall({ sessionId: session.id, closingNotice: r.closingNotice }),
  )
  useEffect(() => {
    call.setHandlers({
      onEnded,
      onFallback: (_result, problem, transcript) =>
        onFallback(transcript, problem === 'mic-denied' ? 'mic-denied' : null),
    })
  })
  useEffect(() => {
    call.attach()
    const leave = () => call.dispose()
    window.addEventListener('pagehide', leave)
    return () => {
      window.removeEventListener('pagehide', leave)
      call.dispose()
    }
  }, [call])

  const snap = useSyncExternalStore(call.subscribe, call.getSnapshot)
  const active = [
    'preparing',
    'connecting',
    'listening',
    'speaking',
    'reconnecting',
    'ending',
  ].includes(snap.status)
  const connected = snap.status === 'listening' || snap.status === 'speaking'

  // Leaving with an open call asks first (012-65).
  const blocker = useBlocker(active)
  useEffect(() => {
    if (!active) return
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [active])

  // Time left, from the server limits (012-62).
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!active || snap.endsAt === null) return
    const timer = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(timer)
  }, [active, snap.endsAt])

  // The orb follows the real volume (local or DORA), never emotions; still with reduced motion.
  const reducedMotion = usePrefersReducedMotion()
  const orbRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const element = orbRef.current
    if (!call || !element || reducedMotion || !connected) {
      element?.style.setProperty('--dora-level', '0')
      return
    }
    let frame = 0
    const tick = () => {
      element.style.setProperty('--dora-level', call.getLevel().toFixed(3))
      frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [call, connected, reducedMotion])

  const [writeOpen, setWriteOpen] = useState(false)
  const [draft, setDraft] = useState('')
  const [transcriptOpen, setTranscriptOpen] = useState(false)
  const [helpOpen, setHelpOpen] = useState(false)
  const [confirmHangUp, setConfirmHangUp] = useState(false)

  const statusText = snap.status === 'listening' && snap.muted ? r.muted : r.statuses[snap.status]

  return (
    <>
      {blocker.state === 'blocked' && (
        <Dialog title={r.leaveTitle} onClose={() => blocker.reset()}>
          <p>{r.leaveBody}</p>
          <div className="workflow__actions">
            <Button
              onClick={() => {
                call?.dispose()
                blocker.proceed()
              }}
            >
              {r.leave}
            </Button>
            <Button variant="quiet" onClick={() => blocker.reset()}>
              {r.stay}
            </Button>
          </div>
        </Dialog>
      )}
      <section
        className="dora-call dora-call--realtime"
        aria-label={interview ? t.interview : t.session}
        data-status={snap.status}
      >
        <div className="dora-call__heading">
          <div>
            <span>{interview ? c.interviewPrivacy : c.practicePrivacy}</span>
            {snap.endsAt !== null && active && (
              <small className="dora-call__timer">
                {r.timeLeft}: <time>{remaining(snap.endsAt, now)}</time>
              </small>
            )}
          </div>
          <div className="dora-call__toolbar">
            <Button
              variant="quiet"
              aria-label={c.transcript}
              onClick={() => setTranscriptOpen(true)}
            >
              <CallIcon kind="transcript" />
              <span>{c.transcriptButton}</span>
            </Button>
            <Button
              className="dora-call__help-button"
              variant="quiet"
              title={c.help}
              aria-label={c.help}
              onClick={() => setHelpOpen(true)}
            >
              <CallIcon kind="help" />
              <span>{c.helpButton}</span>
            </Button>
          </div>
        </div>
        <div className="dora-call__actors" ref={orbRef}>
          <ConversationOrb mode={ORB_MODE[snap.status]} levelDriven={connected} />
        </div>
        <span className="visually-hidden">
          {c.assistant} · {c.assistantNote}
        </span>
        <p className="dora-call__status" role="status">
          {statusText}
        </p>
        {snap.warned && active && (
          <p className="dora-call__warning" role="status">
            {r.warning}
          </p>
        )}
        {snap.status === 'idle' && (
          <>
            <Button onClick={() => void call?.start()}>{r.startCall}</Button>
            <small className="dora-call__hint">{r.startHint}</small>
          </>
        )}
        {snap.status === 'error' && snap.problem && (
          <div className="dora-call__problem" role="alert">
            <p>{r.problems[snap.problem]}</p>
            <p>{r.keepTranscript}</p>
            <div className="workflow__actions">
              {snap.canRetry && <Button onClick={() => void call?.retry()}>{r.retry}</Button>}
              {snap.problem !== 'session-closed' && snap.problem !== 'call-open' && (
                <Button
                  variant={snap.canRetry ? 'quiet' : 'primary'}
                  onClick={() => void call?.fallback()}
                >
                  {r.turns}
                </Button>
              )}
              {(snap.problem === 'session-closed' || snap.problem === 'call-open') && (
                <Button variant="quiet" onClick={onEnded}>
                  {t.refresh}
                </Button>
              )}
            </div>
          </div>
        )}
        {(connected || snap.status === 'reconnecting') && (
          <div className="dora-call__controls">
            <Button
              variant="quiet"
              className="dora-call__hangup"
              title={r.hangUp}
              aria-label={r.hangUp}
              onClick={() => setConfirmHangUp(true)}
            >
              <span className="dora-call__disc">
                <CallIcon kind="close" />
              </span>
              <span className="dora-call__control-label">{r.hangUpShort}</span>
            </Button>
            <Button
              className="dora-call__mic"
              aria-pressed={snap.muted}
              title={snap.muted ? r.unmute : r.mute}
              aria-label={snap.muted ? r.unmute : r.mute}
              onClick={() => call?.setMuted(!snap.muted)}
            >
              <span className="dora-call__disc">
                <CallIcon kind={snap.muted ? 'micOff' : 'mic'} />
              </span>
              <span className="dora-call__control-label">
                {snap.muted ? r.unmuteShort : r.muteShort}
              </span>
            </Button>
            <Button
              variant="quiet"
              className="dora-call__keyboard"
              title={writeOpen ? c.hideText : c.write}
              aria-label={writeOpen ? c.hideText : c.write}
              aria-expanded={writeOpen}
              aria-controls="dora-realtime-text"
              onClick={() => setWriteOpen(!writeOpen)}
            >
              <span className="dora-call__disc">
                <CallIcon kind="keyboard" />
              </span>
              <span className="dora-call__control-label">
                {writeOpen ? c.hideTextShort : c.writeShort}
              </span>
            </Button>
          </div>
        )}
        <form
          id="dora-realtime-text"
          hidden={!writeOpen || !connected}
          className="workflow__form dora-call__write"
          onSubmit={(event) => {
            event.preventDefault()
            if (call?.sendText(draft)) setDraft('')
          }}
        >
          <label>
            {r.textLabel}
            <textarea
              className="workflow__editor"
              maxLength={4000}
              value={draft}
              onChange={(event) => setDraft(event.target.value)}
            />
          </label>
          <Button type="submit" disabled={!draft.trim()}>
            {r.sendText}
          </Button>
        </form>
        {snap.audioBlocked && (
          <Button variant="quiet" onClick={() => call?.resumeAudio()}>
            {r.playAudio}
          </Button>
        )}
      </section>
      <Dialog
        open={transcriptOpen}
        title={c.transcriptButton}
        onClose={() => setTranscriptOpen(false)}
      >
        <Button variant="quiet" onClick={() => setTranscriptOpen(false)}>
          {c.closeTranscript}
        </Button>
        <LiveTranscript entries={snap.transcript} />
      </Dialog>
      {helpOpen && (
        <Dialog title={c.help} onClose={() => setHelpOpen(false)}>
          <Button variant="quiet" onClick={() => setHelpOpen(false)}>
            {c.closeHelp}
          </Button>
          <p>{interview ? c.interviewHelp : c.practiceHelp}</p>
          <p>{t.voicePrivacy}</p>
        </Dialog>
      )}
      {confirmHangUp && (
        <Dialog title={r.hangUp} onClose={() => setConfirmHangUp(false)}>
          <p>{r.hangUpBody}</p>
          <div className="workflow__actions">
            <Button
              onClick={() => {
                setConfirmHangUp(false)
                void call?.finishConversation()
              }}
            >
              {r.hangUpShort}
            </Button>
            <Button variant="quiet" onClick={() => setConfirmHangUp(false)}>
              {r.keepTalking}
            </Button>
          </div>
        </Dialog>
      )}
    </>
  )
}

/** What the person and DORA said; DORA's lines are labelled as AI (principle 2). */
export function LiveTranscript({ entries }: { entries: TranscriptEntry[] }) {
  if (!entries.length) return <p>{r.transcriptEmpty}</p>
  return (
    <ol className="dora-call__live">
      {entries.map((entry) => (
        <li key={entry.id} className="workflow__row" data-speaker={entry.speaker}>
          <strong>{entry.speaker === 'DORA' ? c.assistant : c.you}</strong>
          {entry.speaker === 'DORA' && <Source value="AI" />}
          <p>{entry.text}</p>
        </li>
      ))}
    </ol>
  )
}
