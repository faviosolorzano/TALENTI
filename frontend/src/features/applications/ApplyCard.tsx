import { useRef, useState } from 'react'
import { Link } from 'react-router'
import { fetchApplications, isActiveStatus } from '../../api/applications'
import { Button } from '../../components/Button'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import { formatDate } from '../vacancies/format'
import '../vacancies/Skeletons.css'
import { ApplyDialog, type ApplyOutcome } from './ApplyDialog'
import { statusLabel } from './status'
import './ApplyCard.css'

const t = es.applications.card

interface ApplyCardProps {
  vacancyId: string
  vacancyTitle: string
  companyName: string
}

/**
 * The application part of the vacancy detail for a student with an OPEN vacancy (008-14):
 * "Postular", "Postulaste el…" with the link to it, or "Postular de nuevo" after a withdrawal.
 * The server decides every state; this card only asks for the latest application to this vacancy.
 */
export function ApplyCard({ vacancyId, vacancyTitle, companyName }: ApplyCardProps) {
  const [version, setVersion] = useState(0)
  const latest = useApiResource(
    `apply-card-${vacancyId}-${version}`,
    async (signal) => (await fetchApplications({ vacancyId, size: 1 }, signal)).items[0] ?? null,
    { keepPrevious: true },
  )
  const [dialogOpen, setDialogOpen] = useState(false)
  const [message, setMessage] = useState<string | null>(null)
  const messageRef = useRef<HTMLParagraphElement>(null)

  function onDone(outcome: ApplyOutcome) {
    setDialogOpen(false)
    setMessage(
      outcome.kind === 'sent' ? t.sent : outcome.kind === 'exists' ? t.alreadyApplied : t.closed,
    )
    setVersion((value) => value + 1)
    // The button that opened the dialog is gone: the announcement takes the focus.
    setTimeout(() => messageRef.current?.focus(), 0)
  }

  if (latest.state.status === 'loading') {
    return (
      <div aria-busy="true">
        <p className="visually-hidden" role="status">
          {t.loading}
        </p>
        <span className="skeleton skeleton--heading" aria-hidden="true" />
      </div>
    )
  }
  if (latest.state.status === 'error') {
    return <ErrorNotice message={t.loadError} onRetry={latest.retry} />
  }

  const application = latest.state.data
  const active = application !== null && isActiveStatus(application.status)

  return (
    <div className="apply-card">
      <p ref={messageRef} tabIndex={-1} role="status" className="apply-card__message">
        {message}
      </p>
      {active ? (
        <>
          <p className="apply-card__title">{t.applied(formatDate(application.submittedAt))}</p>
          <p className="apply-card__status">
            {es.applications.statusLabel}: <strong>{statusLabel(application.status)}</strong>
          </p>
          <Link className="apply-card__link" to={`${sessionPaths.applications}/${application.id}`}>
            {t.view}
          </Link>
        </>
      ) : (
        <>
          {application && (
            <p className="apply-card__title">
              {t.withdrawn(formatDate(application.withdrawnAt ?? application.submittedAt))}
            </p>
          )}
          <Button onClick={() => setDialogOpen(true)}>
            {application ? t.applyAgain : es.vacancyDetail.apply}
          </Button>
        </>
      )}
      {dialogOpen && (
        <ApplyDialog
          vacancyId={vacancyId}
          vacancyTitle={vacancyTitle}
          companyName={companyName}
          onClose={() => setDialogOpen(false)}
          onDone={onDone}
        />
      )}
    </div>
  )
}
