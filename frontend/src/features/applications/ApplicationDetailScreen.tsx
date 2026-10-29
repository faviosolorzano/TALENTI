import { safeReturnTo } from '../../session/returnTo'
import { useRef, useState, type ReactNode } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { fetchApplication, type Application } from '../../api/applications'
import { ApiError } from '../../api/client'
import { Button } from '../../components/Button'
import { Card } from '../../components/Card'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { useSession } from '../../session/useSession'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import '../vacancies/Skeletons.css'
import { ProcessLine } from './ProcessLine'
import { statusLabel } from './status'
import { WithdrawDialog } from './WithdrawDialog'
import './Applications.css'

const t = es.applications.detail

/** Page /postulaciones/:id (008-19): the vacancy, the state, the line of stages and the withdrawal. */
export function ApplicationDetailScreen() {
  const { id = '' } = useParams()
  const location = useLocation()
  const previous = safeReturnTo(location.state?.applicationList)
  const { refresh } = useSession()
  const resource = useApiResource(`application-${id}`, async (signal) => {
    try {
      return await fetchApplication(id, signal)
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void refresh()
      throw error
    }
  })

  const back = (
    <Link
      className="applications__back"
      to={previous?.startsWith('/postulaciones?') ? previous : sessionPaths.applications}
    >
      <span aria-hidden="true">←</span>
      {t.back}
    </Link>
  )

  if (resource.state.status === 'loading') {
    return (
      <div className="container applications" aria-busy="true">
        {back}
        <p className="visually-hidden" role="status">
          {t.loading}
        </p>
        <Card className="application-item" aria-hidden="true">
          <span className="skeleton skeleton--title" />
          <span className="skeleton skeleton--line" />
          <span className="skeleton skeleton--line skeleton--short" />
        </Card>
      </div>
    )
  }

  if (resource.state.status === 'error') {
    const missing = resource.state.error instanceof ApiError && resource.state.error.status === 404
    return (
      <div className="container applications">
        {back}
        {missing ? (
          <Card className="applications__empty">
            <h1 className="applications__title">{t.notFoundTitle}</h1>
            <div>
              <Button to={sessionPaths.applications}>{t.notFoundLink}</Button>
            </div>
          </Card>
        ) : (
          <ErrorNotice message={t.loadError} onRetry={resource.retry} />
        )}
      </div>
    )
  }

  return (
    <ApplicationView
      key={resource.state.data.id}
      initial={resource.state.data}
      back={back}
      reload={resource.retry}
    />
  )
}

function ApplicationView({
  initial,
  back,
  reload,
}: {
  initial: Application
  back: ReactNode
  reload: () => void
}) {
  const [application, setApplication] = useState(initial)
  const [dialogOpen, setDialogOpen] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const messageRef = useRef<HTMLParagraphElement>(null)
  const { vacancy } = application

  return (
    <div className="container applications">
      {back}
      <div className="applications__head">
        <h1 className="applications__title">{vacancy.title}</h1>
        <p className="applications__lead">
          {vacancy.company.name} · {vacancy.company.city}
        </p>
        <p>
          <Link className="applications__link" to={`/oportunidades/${vacancy.id}`}>
            {t.viewVacancy}
          </Link>
        </p>
      </div>

      <p ref={messageRef} tabIndex={-1} role="status" className="applications__message">
        {message}
      </p>

      <Card as="section" className="applications__panel" aria-labelledby="application-process">
        <h2 id="application-process" className="applications__subtitle">
          {t.process}
        </h2>
        <p className="applications__state">
          {es.applications.statusLabel}: <strong>{statusLabel(application.status)}</strong>
        </p>
        <ProcessLine application={application} />
        {['SUBMITTED', 'EXAM_PENDING', 'EXAM_DONE', 'INTERVIEW_PENDING', 'INTERVIEW_DONE'].includes(
          application.status,
        ) && (
          <div className="workflow__actions">
            <Button to={'/postulaciones/' + application.id + '/examen'}>{es.workflows.exam}</Button>
            {['EXAM_DONE', 'INTERVIEW_PENDING', 'INTERVIEW_DONE'].includes(application.status) && (
              <Button variant="quiet" to={'/postulaciones/' + application.id + '/entrevista'}>
                {es.workflows.interview}
              </Button>
            )}
          </div>
        )}
        {vacancy.status === 'CLOSED' && <p className="applications__note">{t.vacancyClosed}</p>}
        <p className="applications__note">{es.applications.finalDecision}</p>
        <p className="applications__note">{es.applications.practicePrivate}</p>
        {['SUBMITTED', 'EXAM_PENDING', 'EXAM_DONE', 'INTERVIEW_PENDING', 'INTERVIEW_DONE'].includes(
          application.status,
        ) && (
          <div>
            <Button variant="quiet" onClick={() => setDialogOpen(true)}>
              {t.withdraw}
            </Button>
          </div>
        )}
      </Card>

      {dialogOpen && (
        <WithdrawDialog
          application={application}
          onClose={() => setDialogOpen(false)}
          onDone={(next) => {
            setDialogOpen(false)
            if (next) {
              setApplication(next)
              setMessage(t.withdrawn)
            } else {
              reload()
            }
            setTimeout(() => messageRef.current?.focus(), 0)
          }}
        />
      )}
    </div>
  )
}
