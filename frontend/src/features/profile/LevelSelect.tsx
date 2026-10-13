import { useId } from 'react'
import type { SkillLevel } from '../../api/profile'
import { es } from '../../i18n/es'
import './LevelSelect.css'

const LEVELS: readonly SkillLevel[] = [1, 2, 3]

interface LevelSelectProps {
  /** Name of the skill, for the accessible name of the group. */
  skillName: string
  value: SkillLevel
  onChange: (level: SkillLevel) => void
}

/**
 * Básico / Intermedio / Avanzado (RN-03) as a group of native radio buttons: arrow keys move
 * between them and the choice is told in words, not only with color.
 */
export function LevelSelect({ skillName, value, onChange }: LevelSelectProps) {
  const name = useId()
  return (
    <div
      className="level-select"
      role="radiogroup"
      aria-label={es.profile.skills.levelGroup(skillName)}
    >
      {LEVELS.map((level) => (
        <label key={level} className="level-select__option">
          <input
            type="radio"
            className="level-select__input"
            name={name}
            value={level}
            checked={value === level}
            onChange={() => onChange(level)}
          />
          <span className="level-select__label">{es.profile.skills.levels[level]}</span>
        </label>
      ))}
    </div>
  )
}
