import { ProgressBar } from '../../components/ProgressBar'
import { es } from '../../i18n/es'
import './ProgressHeadline.css'

/**
 * "Tu perfil está al 45 %" with its bar. The number is the one the server calculated
 * (`completeness.percent`): this piece only shows it (RN-11).
 */
export function ProgressHeadline({ percent }: { percent: number }) {
  const t = es.profile.summary
  return (
    <div className="progress-headline">
      <p className="progress-headline__text">
        {t.percentLead} <strong className="progress-headline__number">{percent} %</strong>
      </p>
      <ProgressBar value={percent} label={t.barLabel} />
    </div>
  )
}
