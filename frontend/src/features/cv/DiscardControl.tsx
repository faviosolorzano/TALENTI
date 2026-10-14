import { useState } from 'react'
import { ApiError } from '../../api/client'
import { discardAnalysis } from '../../api/cv'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import { es } from '../../i18n/es'
import { useSession } from '../../session/useSession'
import './DiscardControl.css'

const t = es.cv.discard

interface DiscardControlProps {
  analysisId: string
  /** The CV is gone (or was already gone): go back to the first step. */
  onDiscarded: () => void
}

/** "Borrar mi CV": always in sight, asks before deleting and says the profile does not change. */
export function DiscardControl({ analysisId, onDiscarded }: DiscardControlProps) {
  const { refresh } = useSession()
  const [asking, setAsking] = useState(false)
  const [busy, setBusy] = useState(false)
  const [failed, setFailed] = useState(false)

  async function confirm() {
    setBusy(true)
    setFailed(false)
    try {
      await discardAnalysis(analysisId)
      onDiscarded()
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void refresh()
      // Already closed somewhere else: the CV is gone, which is what the person wanted.
      if (error instanceof ApiError && error.status === 404) {
        onDiscarded()
        return
      }
      setFailed(true)
    } finally {
      setBusy(false)
    }
  }

  if (!asking) {
    return (
      <div className="discard">
        <Button variant="quiet" size="sm" onClick={() => setAsking(true)}>
          {t.button}
        </Button>
      </div>
    )
  }

  return (
    <div className="discard">
      <Notice tone="info" announce="none" className="discard__ask">
        <p className="discard__title">{t.title}</p>
        <p>{t.body}</p>
        {failed && (
          <p role="alert" className="discard__failed">
            {t.failed}
          </p>
        )}
        <div className="discard__buttons">
          <Button variant="danger" size="sm" disabled={busy} onClick={() => void confirm()}>
            {busy ? t.deleting : t.yes}
          </Button>
          <Button variant="quiet" size="sm" disabled={busy} onClick={() => setAsking(false)}>
            {t.cancel}
          </Button>
        </div>
      </Notice>
    </div>
  )
}
