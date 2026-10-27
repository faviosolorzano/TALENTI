import { useWorkflowAction } from './useWorkflowAction'
import { t } from './workflowLabels'
import { useEffect, useRef, useState } from 'react'
import { useBlocker, useParams } from 'react-router'
import {
  workflowApi as api,
  type ExamAttempt,
  type ExamPreparation,
  type ExamQuestion,
  type ExamAnswer,
  type ExamRun,
  type SqlTable,
} from '../../api/workflows'
import { useApiResource } from '../../hooks/useApiResource'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import notice from '../../content/exam-notice-v1.md?raw'
import { WorkflowFrame, StudentWorkflow, Loading, ActionError, Source } from './shared'

export function ExamPage() {
  const { id = '' } = useParams()
  return (
    <StudentWorkflow>
      <ExamLoader id={id} />
    </StudentWorkflow>
  )
}
function ExamLoader({ id }: { id: string }) {
  const resource = useApiResource('exam:' + id, (signal) =>
    api<ExamAttempt | undefined>('/applications/' + id + '/exam', { signal }),
  )
  const action = useWorkflowAction()
  const [accepted, setAccepted] = useState(false)
  const [preparing, setPreparing] = useState(false)
  return (
    <WorkflowFrame title={t.exam} back={'/postulaciones/' + id}>
      <ActionError message={action.error} />
      {resource.state.status === 'loading' ? (
        <Loading />
      ) : resource.state.status === 'error' ? (
        <>
          <ActionError message={t.error} />
          <Button onClick={resource.retry}>{t.retry}</Button>
        </>
      ) : resource.state.data ? (
        <ExamWorkspace key={resource.state.data.id} initial={resource.state.data} />
      ) : (
        <>
          <p>{t.examIntro}</p>
          <div className="workflow__notice workflow__pre">{notice}</div>
          <label className="workflow__choice">
            <input
              type="checkbox"
              checked={accepted}
              onChange={(e) => setAccepted(e.target.checked)}
            />
            {t.acceptNotice}
          </label>
          {preparing && <p role="status">{t.preparing}</p>}
          <Button
            disabled={!accepted || action.busy}
            onClick={() =>
              void action.perform(async () => {
                const result = await api<ExamAttempt | ExamPreparation>(
                  '/applications/' + id + '/exam',
                  { method: 'POST', json: { noticeAccepted: true, noticeVersion: 'v1' } },
                )
                if ('id' in result) {
                  setPreparing(false)
                  resource.retry()
                } else setPreparing(true)
              })
            }
          >
            {action.busy ? t.working : preparing ? t.refresh : t.prepareExam}
          </Button>
        </>
      )}
    </WorkflowFrame>
  )
}
function ExamWorkspace({ initial }: { initial: ExamAttempt }) {
  const [attempt, setAttempt] = useState(initial)
  const action = useWorkflowAction()
  const [active, setActive] = useState(0)
  const [dirty, setDirty] = useState(false)
  const [confirm, setConfirm] = useState(false)
  const [eventMessage, setEventMessage] = useState('')
  const [seconds, setSeconds] = useState(initial.remainingSeconds)
  const baseline = useRef({ seconds: initial.remainingSeconds, at: 0 })
  const saveCurrentRef = useRef<() => Promise<void>>(async () => {})
  const mounted = useRef(true)
  const running = attempt.status === 'IN_PROGRESS'
  const closed = attempt.status !== 'NOT_STARTED' && !running
  const blocker = useBlocker(dirty)
  const q = attempt.questions[active]
  function update(next: ExamAttempt) {
    baseline.current = { seconds: next.remainingSeconds, at: Date.now() }
    setSeconds(next.remainingSeconds)
    setAttempt(next)
  }
  useEffect(() => {
    baseline.current.at = Date.now()
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  useEffect(() => {
    if (!running) return
    const timer = setInterval(() => {
      setSeconds(
        Math.max(
          0,
          baseline.current.seconds - Math.floor((Date.now() - baseline.current.at) / 1000),
        ),
      )
    }, 1000)
    return () => clearInterval(timer)
  }, [running])
  useEffect(() => {
    if (!running) return
    let controller: AbortController | null = null
    const timer = setInterval(() => {
      controller?.abort()
      controller = new AbortController()
      void api<ExamAttempt>('/exam-attempts/' + attempt.id, { signal: controller.signal })
        .then((next) => {
          if (mounted.current) update(next)
        })
        .catch(() => {})
    }, 15000)
    return () => {
      clearInterval(timer)
      controller?.abort()
    }
  }, [running, attempt.id])
  useEffect(() => {
    if (!running) return
    const before = (e: BeforeUnloadEvent) => {
      if (dirty) {
        e.preventDefault()
        e.returnValue = ''
      }
    }
    window.addEventListener('beforeunload', before)
    return () => window.removeEventListener('beforeunload', before)
  }, [running, dirty])
  useEffect(() => {
    if (!running) return
    const controller = new AbortController()
    const report = (kind: string) => {
      void api<{ rule: string; message: string; status: string }>(
        '/exam-attempts/' + attempt.id + '/events',
        {
          method: 'POST',
          signal: controller.signal,
          json: {
            kind,
            clientEventId: crypto.randomUUID(),
            clientOccurredAt: new Date().toISOString(),
          },
        },
      )
        .then((result) => {
          if (!mounted.current) return
          if (result.message) setEventMessage(result.message)
          if (result.rule === 'CLOSED')
            void api<ExamAttempt>('/exam-attempts/' + attempt.id, {
              signal: controller.signal,
            }).then((next) => {
              if (mounted.current) update(next)
            })
        })
        .catch(() => {
          if (mounted.current) setEventMessage(t.eventUnsent)
        })
    }
    const visibility = () =>
      report(document.visibilityState === 'hidden' ? 'SCREEN_LEFT' : 'SCREEN_RETURNED')
    const offline = () => report('CONNECTION_LOST')
    const online = () => report('CONNECTION_RESTORED')
    const focus = () => report('FOCUS_LOST')
    document.addEventListener('visibilitychange', visibility)
    window.addEventListener('offline', offline)
    window.addEventListener('online', online)
    window.addEventListener('blur', focus)
    return () => {
      controller.abort()
      document.removeEventListener('visibilitychange', visibility)
      window.removeEventListener('offline', offline)
      window.removeEventListener('online', online)
      window.removeEventListener('blur', focus)
    }
  }, [running, attempt.id])
  return (
    <>
      <ActionError message={action.error} />
      <p role="status">{eventMessage}</p>
      {attempt.status === 'NOT_STARTED' ? (
        <>
          <div className="workflow__notice workflow__pre">{attempt.notice}</div>
          <Button
            disabled={action.busy}
            onClick={() =>
              void action.perform(async () =>
                update(
                  await api<ExamAttempt>('/exam-attempts/' + attempt.id + '/start', {
                    method: 'POST',
                  }),
                ),
              )
            }
          >
            {t.startExam}
          </Button>
        </>
      ) : closed ? (
        <>
          <p role="status">{t.examDone}</p>
          <p>
            {t.examState}: {t.statuses[attempt.status as keyof typeof t.statuses] ?? t.unknownState}
          </p>
          <p>
            {t.grading}:{' '}
            {t.statuses[attempt.gradingStatus as keyof typeof t.statuses] ?? t.unknownState}
          </p>
          <p>{t.noScore}</p>
          <Button to={'/postulaciones/' + attempt.applicationId + '/entrevista'}>
            {t.interview}
          </Button>
        </>
      ) : (
        <>
          <div className="workflow__timer" role="timer" aria-label={t.timeRemaining}>
            {t.timeRemaining}: {Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, '0')}
          </div>
          <details>
            <summary>{t.examRules}</summary>
            <p className="workflow__pre">{attempt.notice}</p>
          </details>
          <nav className="workflow__actions" aria-label={t.questionList}>
            {attempt.questions.map((item, i) => (
              <Button
                key={item.id}
                variant={i === active ? 'primary' : 'quiet'}
                aria-current={i === active ? 'step' : undefined}
                disabled={action.busy}
                onClick={() =>
                  void action.perform(async () => {
                    if (dirty) await saveCurrentRef.current()
                    setActive(i)
                  })
                }
              >
                {i + 1}
              </Button>
            ))}
          </nav>
          {q && (
            <ExamEditor
              key={q.id}
              question={q}
              attemptId={attempt.id}
              answer={attempt.answers.find((a) => a.questionId === q.id)}
              onDirty={setDirty}
              saveCurrentRef={saveCurrentRef}
              number={active + 1}
            />
          )}
          <Button disabled={action.busy} onClick={() => setConfirm(true)}>
            {t.submitExam}
          </Button>
        </>
      )}
      {confirm && (
        <Dialog title={t.submitExam} onClose={() => setConfirm(false)} busy={action.busy}>
          <p>{t.submitBody}</p>
          <p>{t.saveBeforeSubmit}</p>
          <ActionError message={action.error} />
          <Button
            disabled={action.busy}
            onClick={() =>
              void action.perform(async () => {
                await saveCurrentRef.current()
                update(
                  await api<ExamAttempt>('/exam-attempts/' + attempt.id + '/submit', {
                    method: 'POST',
                  }),
                )
                setDirty(false)
                setConfirm(false)
              })
            }
          >
            {t.submitExam}
          </Button>
          <Button variant="quiet" disabled={action.busy} onClick={() => setConfirm(false)}>
            {t.cancel}
          </Button>
        </Dialog>
      )}
      {blocker.state === 'blocked' && (
        <Dialog title={t.unsaved} onClose={() => blocker.reset()}>
          <p>{t.leaveAnswer}</p>
          <ActionError message={action.error} />
          <Button
            disabled={action.busy}
            onClick={() =>
              void action.perform(async () => {
                await saveCurrentRef.current()
                blocker.proceed()
              })
            }
          >
            {t.saveAndLeave}
          </Button>
          <Button variant="quiet" onClick={() => blocker.proceed()}>
            {t.leaveWithoutSaving}
          </Button>
          <Button variant="quiet" onClick={() => blocker.reset()}>
            {t.cancel}
          </Button>
        </Dialog>
      )}
    </>
  )
}
function ExamEditor({
  question: q,
  attemptId,
  answer,
  onDirty,
  saveCurrentRef,
  number,
}: {
  question: ExamQuestion
  attemptId: string
  answer?: ExamAnswer
  onDirty: (v: boolean) => void
  saveCurrentRef: React.RefObject<() => Promise<void>>
  number: number
}) {
  const original =
    answer?.choiceId ??
    answer?.text ??
    answer?.code ??
    (q.type === 'CODE' ? (q.initialCode ?? '') : '')
  const [value, setValue] = useState(original)
  const [saved, setSaved] = useState(original)
  const revision = useRef(answer?.revision ?? 0)
  const [run, setRun] = useState<ExamRun | null>(null)
  const action = useWorkflowAction()
  const [message, setMessage] = useState('')
  const lock = useRef<Promise<void> | null>(null)
  const dirty = value !== saved
  async function save() {
    if (lock.current) return lock.current
    if (!dirty && answer) return
    const operation = (async () => {
      const result = await api<{ revision: number }>(
        '/exam-attempts/' + attemptId + '/answers/' + q.id,
        {
          method: 'PUT',
          json: {
            revision: revision.current,
            [q.type === 'CHOICE' ? 'choiceId' : q.type === 'OPEN' ? 'text' : 'code']: value || null,
          },
        },
      )
      revision.current = result.revision
      setSaved(value)
      onDirty(false)
      setMessage(t.answerSaved)
    })()
    lock.current = operation
    try {
      await operation
    } finally {
      lock.current = null
    }
  }
  useEffect(() => {
    saveCurrentRef.current = save
    return () => {
      saveCurrentRef.current = async () => {}
    }
  })
  return (
    <section className="workflow__row">
      <h2>
        {t.question} {number}
      </h2>
      <Source value={q.generatedBy} />
      <p className="workflow__question">{q.prompt}</p>
      {q.rubric?.length > 0 && (
        <details>
          <summary>{t.rubric}</summary>
          <ul>
            {q.rubric.map((r) => (
              <li key={r.id}>
                {r.description}
                <ul>
                  {Object.entries(r.levels).map(([level, text]) => (
                    <li key={level}>
                      {level}: {text}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ul>
        </details>
      )}
      {q.type === 'CHOICE' ? (
        <fieldset>
          <legend>{t.answer}</legend>
          {q.options.map((option) => (
            <label key={option.id} className="workflow__choice">
              <input
                type="radio"
                name={q.id}
                value={option.id}
                checked={value === option.id}
                onChange={() => {
                  setValue(option.id)
                  onDirty(option.id !== saved)
                  setMessage('')
                }}
              />
              {option.text}
            </label>
          ))}
        </fieldset>
      ) : (
        <label>
          {t.answer} {q.language && ' · ' + q.language}
          <textarea
            className={'workflow__editor' + (q.type === 'CODE' ? ' workflow__code' : '')}
            maxLength={q.type === 'OPEN' ? 4000 : 60000}
            value={value}
            spellCheck={q.type !== 'CODE'}
            onChange={(e) => {
              setValue(e.target.value)
              onDirty(e.target.value !== saved)
              setMessage('')
            }}
          />
        </label>
      )}
      <p role="status">{dirty ? t.unsaved : message}</p>
      <ActionError message={action.error} />
      <Button disabled={action.busy} onClick={() => void action.perform(save)}>
        {action.busy ? t.working : t.save}
      </Button>
      {q.type === 'CODE' && (
        <>
          <h3>{t.visibleCases}</h3>
          {q.cases.map((c) => (
            <div className="workflow__row" key={c.id}>
              {c.input != null && (
                <p>
                  {t.input}: <code>{c.input}</code>
                </p>
              )}
              {c.expectedOutput != null && (
                <p>
                  {t.expected}: <code>{c.expectedOutput}</code>
                </p>
              )}
              {c.schema && <pre>{c.schema}</pre>}
              {c.tables?.map((table) => (
                <div key={table.name}>
                  <strong>{table.name}</strong>
                  <DataTable
                    table={{
                      columns: table.columns.map((column) => column.name),
                      rows: table.rows,
                    }}
                  />
                </div>
              ))}
              {c.expectedTable && (
                <>
                  <p>{t.expected}</p>
                  <DataTable table={c.expectedTable} />
                </>
              )}
            </div>
          ))}
          <Button
            variant="quiet"
            disabled={action.busy}
            onClick={() =>
              void action.perform(async () => {
                await save()
                setRun(
                  await api<ExamRun>('/exam-attempts/' + attemptId + '/answers/' + q.id + '/run', {
                    method: 'POST',
                  }),
                )
              })
            }
          >
            {t.run}
          </Button>
          {run && (
            <div role="status">
              {run.cases.map((c, i) => (
                <div className="workflow__row" key={i}>
                  <strong>
                    {i + 1}. {c.passed ? t.passed : t.failed}
                  </strong>
                  {c.output && <pre>{c.output}</pre>}
                  {c.error && <pre>{c.error}</pre>}
                  {c.table && <DataTable table={c.table} />}
                </div>
              ))}
            </div>
          )}
        </>
      )}
    </section>
  )
}
function DataTable({ table }: { table: SqlTable }) {
  return (
    <div className="workflow__table">
      <table>
        <thead>
          <tr>
            {table.columns.map((c, i) => (
              <th key={i} scope="col">
                {c}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {table.rows.map((row, i) => (
            <tr key={i}>
              {row.map((cell, j) => (
                <td key={j}>{cell ?? 'NULL'}</td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
