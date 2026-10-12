import { Button } from '../../components/Button'
import { es } from '../../i18n/es'
import './ErrorNotice.css'

interface ErrorNoticeProps {
  message: string
  onRetry: () => void
}

/** Error of one section: announced right away and with a "Reintentar" button. */
export function ErrorNotice({ message, onRetry }: ErrorNoticeProps) {
  return (
    <div className="error-notice" role="alert">
      <p className="error-notice__message">{message}</p>
      <Button variant="quiet" size="sm" onClick={onRetry}>
        {es.common.retry}
      </Button>
    </div>
  )
}
