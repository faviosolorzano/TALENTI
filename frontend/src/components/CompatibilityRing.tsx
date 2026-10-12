import { useCountUp } from '../hooks/useCountUp'
import { usePrefersReducedMotion } from '../hooks/usePrefersReducedMotion'
import { es } from '../i18n/es'
import { ringCountDuration } from '../design/tokens'
import { cssVars } from './cssVars'
import './CompatibilityRing.css'

/** Geometry of the SVG (viewBox 120). */
const RADIUS = 52
const STROKE_WIDTH = 11
const CIRCUMFERENCE = 2 * Math.PI * RADIUS

interface CompatibilityRingProps {
  /** Whole percentage from the backend, or null when the information is insufficient. */
  value: number | null
  /** The percentage hit the cap for a missing required requirement: the arc turns to warning. */
  capped?: boolean
  /**
   * The ring waits (empty) until this is true, then fills. The card passes "is on screen".
   * With reduced motion it is ignored: the final value shows at once.
   */
  active?: boolean
}

/**
 * The compatibility ring. It only draws the number it is given: the calculation and the
 * decisions come from the backend. The arc fills in 1 s and the number counts in 0.9 s;
 * only the final value is announced (not the steps of the count).
 */
export function CompatibilityRing({
  value,
  capped = false,
  active = true,
}: CompatibilityRingProps) {
  const reducedMotion = usePrefersReducedMotion()
  const settled = active || reducedMotion
  const goal = settled && value !== null ? value : 0
  const shown = useCountUp(goal, ringCountDuration, !reducedMotion)
  const empty = value === null

  const offset = CIRCUMFERENCE * (1 - goal / 100)
  const announcement = !settled
    ? ''
    : value === null
      ? es.ring.announceNone
      : es.ring.announce(value)

  return (
    <div className="ring" data-capped={capped || undefined} data-empty={empty || undefined}>
      <svg className="ring__svg" viewBox="0 0 120 120" aria-hidden="true">
        <circle
          className="ring__track"
          cx="60"
          cy="60"
          r={RADIUS}
          fill="none"
          strokeWidth={STROKE_WIDTH}
        />
        <circle
          className="ring__arc"
          cx="60"
          cy="60"
          r={RADIUS}
          fill="none"
          strokeWidth={STROKE_WIDTH}
          strokeLinecap="round"
          strokeDasharray={CIRCUMFERENCE}
          style={cssVars({ '--ring-offset': offset })}
        />
      </svg>
      <div className="ring__label" aria-hidden="true">
        <strong className="ring__number">
          {empty ? (
            '—'
          ) : (
            <>
              {shown}
              <small>%</small>
            </>
          )}
        </strong>
        <span className="ring__caption">{es.ring.caption}</span>
      </div>
      <span className="visually-hidden" aria-live="polite" aria-atomic="true">
        {announcement}
      </span>
    </div>
  )
}
