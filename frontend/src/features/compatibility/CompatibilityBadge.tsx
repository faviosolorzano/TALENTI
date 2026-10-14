import type { CompatibilitySummary } from '../../api/compatibility'
import { es } from '../../i18n/es'
import './CompatibilityBadge.css'

interface CompatibilityBadgeProps {
  summary: CompatibilitySummary
  /** `label` writes "82 % de compatibilidad"; `short` writes "82 %" and keeps the rest for readers. */
  variant?: 'label' | 'short'
}

/**
 * Compact compatibility of a card. It only shows what the server sent: an insufficient result
 * has no number, so nothing is drawn. The color dot repeats the level but the number says it all.
 */
export function CompatibilityBadge({ summary, variant = 'label' }: CompatibilityBadgeProps) {
  if (summary.status !== 'CALCULATED' || summary.percent === null) return null
  const text = es.compatibility.badge(summary.percent)
  const tone = summary.capped ? 'capped' : (summary.level?.toLowerCase() ?? 'low')

  return (
    <span className="compat-badge" data-tone={tone}>
      <span className="compat-badge__dot" aria-hidden="true" />
      {variant === 'short' ? (
        <>
          <span aria-hidden="true">{summary.percent} %</span>
          <span className="visually-hidden">{text}</span>
        </>
      ) : (
        text
      )}
    </span>
  )
}
