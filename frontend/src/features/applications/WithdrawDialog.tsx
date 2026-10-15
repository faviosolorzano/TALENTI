import { useState } from 'react'
import { withdrawApplication, type Application } from '../../api/applications'
import { ApiError } from '../../api/client'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { Notice } from '../../components/Notice'
import { es } from '../../i18n/es'
import { useSession } from '../../session/useSession'

const t = es.applications.withdraw

interface WithdrawDialogProps {
  application: Application
  onClose: () => void
  /** The new state of the application (200), or `null` to ask the server again (409). */
  onDone: (application: Application | null) => void
}

/** "¿Retirar tu postulación a …?" (008-20): says what happens and offers to keep it. */
export function WithdrawDialog({ application, onClose, onDone }: WithdrawDialogProps) {
  const { refresh } = useSession()
  const [sending, setSending] = useState(false)
  const [failed, setFailed] = useState(false)

  async function confirm() {
    if (sending) return
    setSending(true)
    setFailed(false)
    try {
      onDone(await withdrawApplication(application.id))
    } catch (error) {
      if (error instanceof ApiError) {
        if (error.status === 401) void refresh()
        if (error.problem?.code === 'application-not-withdrawable') return onDone(null)
      }
      setFailed(true)
      setSending(false)
    }
  }

  return (
    <Dialog title={t.title(application.vacancy.title)} onClose={onClose} busy={sending}>
      <p>{t.body}</p>
      {failed && (
        <Notice tone="error" announce="alert">
          {t.error}
        </Notice>
      )}
      <div className="dialog__actions">
        <Button variant="danger" disabled={sending} onClick={() => void confirm()}>
          {sending ? t.sending : failed ? es.common.retry : t.yes}
        </Button>
        <Button variant="quiet" disabled={sending} onClick={onClose}>
          {t.keep}
        </Button>
      </div>
    </Dialog>
  )
}
