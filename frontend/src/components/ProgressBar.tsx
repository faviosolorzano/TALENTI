import { cssVars } from './cssVars'
import './ProgressBar.css'

interface ProgressBarProps {
  /** 0 to 100. The number comes from the server: this piece only draws it. */
  value: number
  /** What is measured, read by assistive technology ("Avance de tu perfil"). */
  label: string
}

/**
 * Progress bar (`role="progressbar"`). The fill grows when it appears and when the value
 * changes; with "reduce motion" it does not move. Only tokens for style.
 */
export function ProgressBar({ value, label }: ProgressBarProps) {
  const percent = Math.min(100, Math.max(0, Math.round(value)))
  return (
    <div
      className="progress"
      role="progressbar"
      aria-label={label}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={percent}
      aria-valuetext={`${percent} %`}
    >
      <span className="progress__fill" style={cssVars({ '--progress': percent / 100 })} />
    </div>
  )
}
