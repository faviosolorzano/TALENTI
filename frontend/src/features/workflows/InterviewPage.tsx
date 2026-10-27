import { Markdown } from '../../components/Markdown'
import { useState } from 'react'
import { useParams } from 'react-router'
import { workflowApi as api, type DoraSession, type PracticeOptions } from '../../api/workflows'
import { useApiResource } from '../../hooks/useApiResource'
import { Button } from '../../components/Button'
import { WorkflowFrame, StudentWorkflow, Loading, ActionError } from './shared'
import { useWorkflowAction } from './useWorkflowAction'
import { t } from './workflowLabels'
import { DoraConversation } from './PracticePage'
interface InterviewView {
  applicationId: string
  sessionId: string
  status: string
  attempt: number
  maxAttempts: number
  canRetry: boolean
  nextPosition: number | null
  questions: {
    position: number
    text: string
    generatedBy: string
    transcriptRaw: string | null
    transcriptFinal: string | null
  }[]
}
export function InterviewPage() {
  const { id = '' } = useParams()
  return (
    <StudentWorkflow>
      <Interview id={id} />
    </StudentWorkflow>
  )
}
function Interview({ id }: { id: string }) {
  const resource = useApiResource(
    'interview:' + id,
    (signal) => api<InterviewView | undefined>('/applications/' + id + '/interview', { signal }),
    { keepPrevious: true },
  )
  const options = useApiResource('interview-notice', (signal) =>
    api<PracticeOptions>('/practice/options', { signal }),
  )
  const action = useWorkflowAction()
  const [turnIds, setTurnIds] = useState<Record<number, string>>({})
  const [accepted, setAccepted] = useState(false)
  const [created, setCreated] = useState<DoraSession | null>(null)
  const view = resource.state.status === 'success' ? resource.state.data : null
  const notice = options.state.status === 'success' ? options.state.data.interviewNotice : null
  const session: DoraSession | null = view
    ? {
        id: view.sessionId,
        mode: 'INTERVIEW',
        basis: 'VACANCY',
        topic: null,
        questionCount: created?.id === view.sessionId ? created.questionCount : null,
        voice: true,
        versions: created?.id === view.sessionId ? created.versions : undefined,
        status: view.status,
        nextPosition: view.nextPosition,
        answered: view.questions.filter((q) => q.transcriptFinal != null).length,
        degraded: [],
        createdAt: '',
        questions: view.questions.map((q) => ({
          ...q,
          source: q.generatedBy,
          answer:
            q.transcriptFinal != null
              ? {
                  turnId: turnIds[q.position] ?? '',
                  attempt: view.attempt,
                  inputMode: 'TEXT',
                  transcriptRaw: q.transcriptRaw ?? '',
                  transcriptFinal: q.transcriptFinal,
                  corrected: q.transcriptFinal !== q.transcriptRaw,
                  feedback: null,
                }
              : null,
        })),
      }
    : created
  return (
    <WorkflowFrame title={t.interview} back={'/postulaciones/' + id}>
      <ActionError message={action.error} />
      {resource.state.status === 'loading' ? (
        <Loading />
      ) : resource.state.status === 'error' ? (
        <>
          <ActionError message={t.error} />
          <Button onClick={resource.retry}>{t.retry}</Button>
        </>
      ) : (
        <>
          {session && (
            <DoraConversation
              session={session}
              refresh={resource.retry}
              interview
              onTranscript={(position, turnId) =>
                setTurnIds((current) => ({ ...current, [position]: turnId }))
              }
            />
          )}
          {(!session || view?.canRetry) && (
            <>
              <p>{t.interviewIntro}</p>
              {notice ? (
                <>
                  <div className="workflow__notice">
                    <Markdown>{notice.text}</Markdown>
                  </div>
                  <label className="workflow__choice">
                    <input
                      type="checkbox"
                      checked={accepted}
                      onChange={(e) => setAccepted(e.target.checked)}
                    />
                    {t.acceptInterview}
                  </label>
                  <Button
                    disabled={action.busy || !accepted}
                    onClick={() =>
                      void action.perform(async () => {
                        setCreated(
                          await api<DoraSession>('/applications/' + id + '/interview', {
                            method: 'POST',
                            json: { noticeAccepted: true, noticeVersion: notice.version },
                          }),
                        )
                        resource.retry()
                      })
                    }
                  >
                    {t.startInterview}
                  </Button>
                </>
              ) : (
                <>
                  <ActionError message={options.state.status === 'error' ? t.error : ''} />
                  <Button onClick={options.retry}>{t.retry}</Button>
                </>
              )}
            </>
          )}
          <p>{t.noScore}</p>
        </>
      )}
    </WorkflowFrame>
  )
}
