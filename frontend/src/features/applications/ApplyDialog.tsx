import { useState } from 'react'
import { Link } from 'react-router'
import { ApiError } from '../../api/client'
import { submitApplication, type Application } from '../../api/applications'
import { fetchProfile } from '../../api/profile'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Notice } from '../../components/Notice'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { useSession } from '../../session/useSession'
import '../vacancies/Skeletons.css'

const t = es.applications.apply

export type ApplyOutcome =
  { kind: 'sent'; application: Application } | { kind: 'exists' } | { kind: 'closed' }

interface ApplyDialogProps {
  vacancyId: string
  vacancyTitle: string
  companyName: string
  onClose: () => void
  onDone: (outcome: ApplyOutcome) => void
}

/**
 * Confirmation before applying (008-15): what is shared and what is not, how complete the
 * profile is (a neutral note, it never blocks) and the final decision belongs to the company.
 * "Confirmar" waits for the profile to load.
 */
export function ApplyDialog({
  vacancyId,
  vacancyTitle,
  companyName,
  onClose,
  onDone,
}: ApplyDialogProps) {
  const { refresh } = useSession()
  const profile = useApiResource('apply-profile', (signal) => fetchProfile(signal))
  const [sending, setSending] = useState(false)
  const [failed, setFailed] = useState(false)

  async function confirm() {
    if (sending) return
    setSending(true)
    setFailed(false)
    try {
      const application = await submitApplication(vacancyId)
      onDone({ kind: 'sent', application })
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.status === 401) void refresh()
        const code = error.problem?.code
        if (code === 'application-already-exists') return onDone({ kind: 'exists' })
        if (code === 'vacancy-closed') return onDone({ kind: 'closed' })
      }
      setFailed(true)
      setSending(false)
    }
  }

  const percent =
    profile.state.status === 'success' ? profile.state.data.completeness.percent : null

  return (
    <Dialog title={t.title(vacancyTitle)} onClose={onClose} busy={sending}>
      <p className="apply-dialog__company">{t.company(companyName)}</p>
      <p>{t.shared}</p>
      <p>{t.notShared}</p>
      <p>{es.applications.finalDecision}</p>

      {profile.state.status === 'loading' && (
        <div aria-busy="true">
          <p className="visually-hidden" role="status">
            {t.loading}
          </p>
          <span className="skeleton skeleton--line" aria-hidden="true" />
        </div>
      )}
      {profile.state.status === 'error' && (
        <Notice tone="error" announce="alert">
          <p>{t.loadError}</p>
          <Button variant="quiet" size="sm" onClick={profile.retry}>
            {es.common.retry}
          </Button>
        </Notice>
      )}
      {percent !== null && percent < 100 && (
        <Notice tone="info" announce="none">
          <p>
            {t.profile(percent)}{' '}
            <Link to={sessionPaths.profile} onClick={onClose}>
              {t.completeProfile}
            </Link>
          </p>
        </Notice>
      )}

      {failed && (
        <Notice tone="error" announce="alert">
          {t.sendError}
        </Notice>
      )}

      <div className="dialog__actions">
        <Button disabled={sending || percent === null} onClick={() => void confirm()}>
          {sending ? t.sending : failed ? es.common.retry : t.confirm}
        </Button>
        <Button variant="quiet" disabled={sending} onClick={onClose}>
          {t.cancel}
        </Button>
      </div>
    </Dialog>
  )
}
