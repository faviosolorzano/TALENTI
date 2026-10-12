import { es } from '../i18n/es'
import { nextLevel, type ChipLevel } from './chipLevel'
import './LevelChip.css'

const LEVEL_LABELS = [es.levels.none, es.levels.basic, es.levels.mastered] as const

interface LevelChipProps {
  name: string
  kind: 'required' | 'desirable'
  level: ChipLevel
  /** Called with the next level of the cycle: none, basic, mastered, none... */
  onLevelChange: (next: ChipLevel) => void
}

/**
 * A skill the person marks with a tap. The level is said in words and in the accessible name,
 * never only with color or bars. It is a native button: click, Enter and Space all work.
 */
export function LevelChip({ name, kind, level, onLevelChange }: LevelChipProps) {
  const levelLabel = LEVEL_LABELS[level]
  const kindLabel = kind === 'required' ? es.badge.required : es.badge.desirable

  return (
    <button
      type="button"
      className="level-chip"
      data-level={level}
      aria-label={es.levelChip.label(name, kindLabel, levelLabel)}
      onClick={() => onLevelChange(nextLevel(level))}
    >
      {name}
      <span className="level-chip__bars" aria-hidden="true">
        <b />
        <b />
      </span>
      <small className="level-chip__level">{levelLabel}</small>
    </button>
  )
}
