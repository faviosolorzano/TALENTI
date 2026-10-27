import { AudioQueue } from './audioQueue'
import { useWorkflowAction } from './useWorkflowAction'
import { t } from './workflowLabels'
import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import {
  workflowApi as api,
  streamDora,
  type PracticeOptions,
  type DoraSession,
  type DoraSessionPage,
  type DoraAnswer,
} from '../../api/workflows'
import { useApiResource } from '../../hooks/useApiResource'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { DoraOrb } from '../dora/DoraOrb'
import { WorkflowFrame, StudentWorkflow, Loading, ActionError, Source } from './shared'

export function PracticePage() {
  return (
    <StudentWorkflow>
      <PracticeSetup />
    </StudentWorkflow>
  )
}
function PracticeSetup() {
  const options = useApiResource('practice-options', (signal) =>
    api<PracticeOptions>('/practice/options', { signal }),
  )
  const history = useApiResource('practice-history', (signal) =>
    api<DoraSessionPage>('/practice/sessions?size=20', { signal }),
  )
  const [params] = useSearchParams()
  const [basis, setBasis] = useState(params.get('vacante') ? 'VACANCY' : 'PROFILE')
  const [vacancy, setVacancy] = useState(params.get('vacante') ?? '')
  const [topic, setTopic] = useState('')
  const [count, setCount] = useState(3)
  const [voice, setVoice] = useState(false)
  const navigate = useNavigate()
  const action = useWorkflowAction()
  const [deleting, setDeleting] = useState<string | null>(null)
  const data = options.state.status === 'success' ? options.state.data : null
  const selected = data?.bases.find((item) => item.basis === basis)
  const topics =
    basis === 'VACANCY'
      ? (selected?.vacancies.find((item) => item.id === vacancy)?.topics ?? [])
      : (data?.topics ?? [])
  return (
    <WorkflowFrame title={t.practice}>
      <p className="workflow__notice">{t.practiceIntro}</p>
      <ActionError message={action.error} />
      {options.state.status === 'loading' ? (
        <Loading />
      ) : options.state.status === 'error' ? (
        <>
          <ActionError message={t.error} />
          <Button onClick={options.retry}>{t.retry}</Button>
        </>
      ) : (
        data && (
          <form
            className="workflow__form"
            onSubmit={(event) => {
              event.preventDefault()
              void action.perform(async () => {
                const session = await api<DoraSession>('/practice/sessions', {
                  method: 'POST',
                  json: {
                    basis,
                    vacancyId: basis === 'VACANCY' ? vacancy : undefined,
                    topic: topic || undefined,
                    questionCount: count,
                    voice,
                  },
                })
                navigate('/practicar/' + session.id)
              })
            }}
          >
            <h2>{t.configure}</h2>
            <p>
              {t.quota}: {data.quota.used} / {data.quota.limit}
            </p>
            <label>
              {t.basis}
              <select
                value={basis}
                onChange={(event) => {
                  setBasis(event.target.value)
                  setTopic('')
                }}
              >
                {data.bases.map((item) => (
                  <option key={item.basis} value={item.basis} disabled={!item.available}>
                    {item.basis === 'PROFILE' ? t.profile : t.vacancy}
                  </option>
                ))}
              </select>
            </label>
            {basis === 'VACANCY' && (
              <label>
                {t.chooseVacancy}
                <select
                  required
                  value={vacancy}
                  onChange={(event) => {
                    setVacancy(event.target.value)
                    setTopic('')
                  }}
                >
                  <option value="">—</option>
                  {selected?.vacancies.map((item) => (
                    <option key={item.id} value={item.id}>
                      {item.title} · {item.company}
                    </option>
                  ))}
                </select>
              </label>
            )}
            <label>
              {t.topic}
              <select value={topic} onChange={(event) => setTopic(event.target.value)}>
                <option value="">{t.allTopics}</option>
                {topics.map((item) => (
                  <option key={item.slug} value={item.slug}>
                    {item.name}
                  </option>
                ))}
              </select>
            </label>
            <label>
              {t.questions}
              <select value={count} onChange={(event) => setCount(Number(event.target.value))}>
                {Array.from({ length: data.maxQuestions }, (_, i) => (
                  <option key={i} value={i + 1}>
                    {i + 1}
                  </option>
                ))}
              </select>
            </label>
            <label className="workflow__choice">
              <input
                type="checkbox"
                checked={voice}
                disabled={!data.voiceAvailable}
                onChange={(event) => setVoice(event.target.checked)}
              />
              {t.voice}
            </label>
            {!selected?.available && <p>{t.noBasis}</p>}
            <Button
              type="submit"
              disabled={action.busy || !selected?.available || data.quota.used >= data.quota.limit}
            >
              {action.busy ? t.working : t.start}
            </Button>
          </form>
        )
      )}
      <section>
        <h2>{t.history}</h2>
        {history.state.status === 'loading' ? (
          <Loading />
        ) : history.state.status === 'error' ? (
          <Button onClick={history.retry}>{t.retry}</Button>
        ) : (
          <ul className="workflow__list">
            {history.state.data.items.length === 0 && <li>{t.emptySessions}</li>}
            {history.state.data.items.map((item) => (
              <li className="workflow__row" key={item.id}>
                <Link to={'/practicar/' + item.id}>
                  {new Date(item.createdAt).toLocaleString('es-PE')} · {item.answered}/
                  {item.questionCount}
                </Link>
                <p>{t.statuses[item.status as keyof typeof t.statuses] ?? item.status}</p>
                <div>
                  <Button size="sm" variant="quiet" onClick={() => setDeleting(item.id)}>
                    {t.deleteSession}
                  </Button>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>
      {deleting && (
        <Dialog title={t.deleteSession} onClose={() => setDeleting(null)}>
          <p>{t.deleteSessionBody}</p>
          <div className="workflow__actions">
            <Button
              variant="danger"
              disabled={action.busy}
              onClick={() =>
                void action.perform(async () => {
                  await api('/practice/sessions/' + deleting, { method: 'DELETE' })
                  setDeleting(null)
                  history.retry()
                })
              }
            >
              {t.delete}
            </Button>
            <Button variant="quiet" onClick={() => setDeleting(null)}>
              {t.cancel}
            </Button>
          </div>
          <ActionError message={action.error} />
        </Dialog>
      )}
    </WorkflowFrame>
  )
}

export function PracticeSessionPage() {
  const { id = '' } = useParams()
  return (
    <StudentWorkflow>
      <PracticeSession id={id} />
    </StudentWorkflow>
  )
}
function PracticeSession({ id }: { id: string }) {
  const resource = useApiResource(
    'practice-session:' + id,
    (signal) => api<DoraSession>('/practice/sessions/' + id, { signal }),
    { keepPrevious: true },
  )
  return (
    <WorkflowFrame title={t.session} back="/practicar">
      {resource.state.status === 'loading' ? (
        <Loading />
      ) : resource.state.status === 'error' ? (
        <>
          <ActionError message={t.error} />
          <Button onClick={resource.retry}>{t.retry}</Button>
        </>
      ) : (
        <DoraConversation session={resource.state.data} refresh={resource.retry} />
      )}
    </WorkflowFrame>
  )
}

export function DoraConversation({
  session,
  refresh,
  interview = false,
  onTranscript,
}: {
  session: DoraSession
  refresh: () => void
  interview?: boolean
  onTranscript?: (position: number, turnId: string) => void
}) {
  const action = useWorkflowAction()
  const [text, setText] = useState('')
  const [reply, setReply] = useState('')
  const [replySource, setReplySource] = useState('')
  const [recording, setRecording] = useState(false)
  const [playing, setPlaying] = useState(false)
  const [audioBlocked, setAudioBlocked] = useState(false)
  const playbackRef = useRef<AudioQueue | null>(null)
  useEffect(() => {
    const queue = new AudioQueue((playing, blocked) => {
      setPlaying(playing)
      setAudioBlocked(blocked)
    })
    playbackRef.current = queue
    return () => queue.stop()
  }, [])
  const [confirmFinish, setConfirmFinish] = useState(false)
  const [degraded, setDegraded] = useState(false)
  const controller = useRef<AbortController | null>(null)
  const recorder = useRef<MediaRecorder | null>(null)
  const media = useRef<MediaStream | null>(null)
  const chunks = useRef<Blob[]>([])
  const recordTimer = useRef<ReturnType<typeof setTimeout> | null>(null)
  const pendingTurn = useRef<{ id: string; position: number; value: string } | null>(null)
  const position = session.nextPosition ?? 1
  const question = session.questions.find((item) => item.position === position)
  const ended = ['COMPLETED', 'ABANDONED', 'FAILED'].includes(session.status)
  useEffect(
    () => () => {
      controller.current?.abort()
      if (recordTimer.current) clearTimeout(recordTimer.current)
      if (recorder.current?.state === 'recording') {
        recorder.current.onstop = null
        recorder.current.stop()
      }
      media.current?.getTracks().forEach((track) => track.stop())
      playbackRef.current?.stop()
    },
    [],
  )
  const feedbackPending =
    !interview && session.questions.some((item) => item.answer?.feedback?.status === 'PENDING')
  useEffect(() => {
    if (!feedbackPending) return
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') refresh()
    }, 2000)
    return () => clearInterval(timer)
  }, [feedbackPending, refresh])
  const enqueue = (mime: string, data: string) => {
    if (!mime.startsWith('audio/')) return
    const blob = new Blob([Uint8Array.from(atob(data), (letter) => letter.charCodeAt(0))], {
      type: mime,
    })
    playbackRef.current?.enqueue(blob)
  }

  async function stream(path: string, body: { json?: unknown; form?: FormData }) {
    controller.current?.abort()
    controller.current = new AbortController()
    setReply('')
    setDegraded(false)
    await streamDora(path, { ...body, signal: controller.current.signal }, (name, data) => {
      if (name === 'transcript' && typeof data.turnId === 'string')
        onTranscript?.(position, data.turnId)
      if (name === 'reply.text') {
        setReply((value) => value + String(data.text ?? ''))
        setReplySource(String(data.source ?? data.generatedBy ?? ''))
      }
      if (name === 'reply.audio' && session.voice)
        enqueue(String(data.mime ?? ''), String(data.data ?? ''))
      if (name === 'degraded') setDegraded(true)
      if (name === 'error') throw new ApiError(409, { code: data.code })
    })
    refresh()
  }
  async function sendText() {
    if (!text.trim()) return
    const value = text.trim()
    if (
      !pendingTurn.current ||
      pendingTurn.current.value !== value ||
      pendingTurn.current.position !== position
    )
      pendingTurn.current = { id: crypto.randomUUID(), position, value }
    await stream('/dora/sessions/' + session.id + '/turns', {
      json: { text: value, questionPosition: position, clientTurnId: pendingTurn.current.id },
    })
    pendingTurn.current = null
    setText('')
  }
  async function startRecording() {
    try {
      const input = await navigator.mediaDevices.getUserMedia({ audio: true })
      media.current = input
      const recording = new MediaRecorder(input)
      recorder.current = recording
      chunks.current = []
      recording.ondataavailable = (event) => {
        if (event.data.size) chunks.current.push(event.data)
      }
      recording.onstop = () => {
        if (recordTimer.current) clearTimeout(recordTimer.current)
        input.getTracks().forEach((track) => track.stop())
        setRecording(false)
        const blob = new Blob(chunks.current, { type: recording.mimeType })
        chunks.current = []
        if (!blob.size || blob.size > 3 * 1024 * 1024) {
          action.setError(t.invalid)
          return
        }
        const form = new FormData()
        form.append('audio', blob, 'respuesta.webm')
        form.append('questionPosition', String(position))
        form.append('clientTurnId', crypto.randomUUID())
        void action.perform(() => stream('/dora/sessions/' + session.id + '/turns', { form }))
      }
      recording.start()
      setRecording(true)
      recordTimer.current = setTimeout(
        () => recording.state === 'recording' && recording.stop(),
        120000,
      )
    } catch {
      media.current?.getTracks().forEach((track) => track.stop())
      action.setError(t.noMicrophone)
    }
  }
  return (
    <>
      <p className="workflow__notice">{interview ? t.interviewIntro : t.practiceIntro}</p>
      {session.versions?.llmAdapter.includes('simulated') && <p role="status">{t.simulated}</p>}
      <ActionError message={action.error} />
      {audioBlocked && (
        <Button variant="quiet" onClick={() => playbackRef.current?.resume()}>
          {t.playAudio}
        </Button>
      )}
      {(degraded || session.degraded?.length > 0) && <p role="status">{t.degraded}</p>}
      <div className="workflow__orb">
        <DoraOrb
          mode={recording ? 'listening' : playing ? 'speaking' : action.busy ? 'thinking' : 'idle'}
        />
      </div>
      {session.status === 'CREATED' && (
        <Button
          disabled={action.busy}
          onClick={() =>
            void action.perform(() => stream('/dora/sessions/' + session.id + '/start', {}))
          }
        >
          {action.busy ? t.working : t.start}
        </Button>
      )}
      {question && session.nextPosition !== null && !ended && session.status !== 'CREATED' && (
        <section className="workflow__row">
          <h2>
            {t.question} {position}/{session.questionCount}
          </h2>
          <Source value={question.generatedBy} />
          <p className="workflow__question">{question.text}</p>
          {session.voice && (
            <Button
              variant="quiet"
              disabled={action.busy}
              onClick={() =>
                void action.perform(async () => {
                  const response = await api<Response>(
                    `/dora/sessions/${session.id}/questions/${position}/audio`,
                    { rawResponse: true },
                  )
                  playbackRef.current?.stop()
                  playbackRef.current?.enqueue(await response.blob())
                })
              }
            >
              {t.listen}
            </Button>
          )}
          <form
            className="workflow__form"
            onSubmit={(event) => {
              event.preventDefault()
              void action.perform(sendText)
            }}
          >
            <label>
              {t.answer}
              <textarea
                className="workflow__editor"
                maxLength={6000}
                value={text}
                disabled={action.busy || recording}
                onChange={(event) => setText(event.target.value)}
              />
            </label>
            <div className="workflow__actions">
              <Button type="submit" disabled={action.busy || recording || !text.trim()}>
                {action.busy ? t.working : t.send}
              </Button>
              {session.voice && (
                <Button
                  variant="quiet"
                  disabled={action.busy}
                  onClick={() => (recording ? recorder.current?.stop() : void startRecording())}
                >
                  {recording ? t.stopRecording : t.record}
                </Button>
              )}
            </div>
          </form>
          <small>{t.voicePrivacy}</small>
        </section>
      )}
      {reply && (
        <section className="workflow__row" aria-live="polite">
          <Source value={replySource} />
          <p>{reply}</p>
        </section>
      )}
      <section>
        <h2>{t.history}</h2>
        {session.questions
          .filter((item) => item.answer)
          .map((item) => (
            <section className="workflow__row" key={item.position}>
              <h3>
                {t.question} {item.position}
              </h3>
              <p>{item.text}</p>
              <Transcript answer={item.answer!} sessionId={session.id} refresh={refresh} />
            </section>
          ))}
      </section>
      {!ended && session.nextPosition === null && session.status !== 'CREATED' && (
        <p role="status">{t.allAnswered}</p>
      )}
      {ended ? (
        <p role="status">{t.closedSession}</p>
      ) : (
        <Button
          variant="quiet"
          disabled={action.busy || recording}
          onClick={() => setConfirmFinish(true)}
        >
          {t.finish}
        </Button>
      )}
      {confirmFinish && (
        <Dialog title={t.finish} onClose={() => setConfirmFinish(false)}>
          <p>{t.finishBody}</p>
          <div className="workflow__actions">
            <Button
              disabled={action.busy}
              onClick={() =>
                void action.perform(async () => {
                  await api('/dora/sessions/' + session.id + '/finish', { method: 'POST' })
                  setConfirmFinish(false)
                  refresh()
                })
              }
            >
              {t.finish}
            </Button>
            <Button variant="quiet" onClick={() => setConfirmFinish(false)}>
              {t.cancel}
            </Button>
          </div>
          <ActionError message={action.error} />
        </Dialog>
      )}
    </>
  )
}
function Transcript({
  answer,
  sessionId,
  refresh,
}: {
  answer: DoraAnswer
  sessionId: string
  refresh: () => void
}) {
  const [editing, setEditing] = useState(false)
  const [text, setText] = useState(answer.transcriptFinal ?? answer.transcriptRaw ?? '')
  const action = useWorkflowAction()
  return (
    <>
      <p className="workflow__transcript">{answer.transcriptFinal ?? answer.transcriptRaw}</p>
      {answer.turnId && (
        <Button variant="quiet" size="sm" onClick={() => setEditing(!editing)}>
          {t.correct}
        </Button>
      )}
      {editing && (
        <form
          className="workflow__form"
          onSubmit={(event) => {
            event.preventDefault()
            void action.perform(async () => {
              await api(`/dora/sessions/${sessionId}/turns/${answer.turnId}`, {
                method: 'PATCH',
                json: { transcript: text },
              })
              setEditing(false)
              refresh()
            })
          }}
        >
          <label>
            {t.transcript}
            <textarea
              className="workflow__editor"
              maxLength={6000}
              value={text}
              onChange={(event) => setText(event.target.value)}
            />
          </label>
          <Button type="submit" disabled={action.busy}>
            {t.save}
          </Button>
          <ActionError message={action.error} />
        </form>
      )}
      {answer.feedback && (
        <section>
          <h3>{t.feedback}</h3>
          <Source value={answer.feedback.generatedBy} />
          {answer.feedback.status === 'PENDING' ? (
            <p role="status">{t.feedbackPending}</p>
          ) : (
            answer.feedback.criteria?.map((item) => (
              <div key={item.criterion}>
                <strong>
                  {item.name} · {item.level}
                </strong>
                <p>{item.comment}</p>
                {item.quotes.map((quote, index) => (
                  <blockquote key={index}>{quote}</blockquote>
                ))}
              </div>
            ))
          )}
          {answer.feedback.retryable && (
            <Button
              variant="quiet"
              disabled={action.busy}
              onClick={() =>
                void action.perform(async () => {
                  await api(`/dora/sessions/${sessionId}/turns/${answer.turnId}/feedback`, {
                    method: 'POST',
                  })
                  refresh()
                })
              }
            >
              {t.retry}
            </Button>
          )}
        </section>
      )}
    </>
  )
}
