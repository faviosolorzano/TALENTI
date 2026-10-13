import { useId, type ReactNode } from 'react'
import { es } from '../i18n/es'
import './TextField.css'
import './ChoiceChips.css'

export interface ChoiceOption {
  value: string
  label: string
}

interface ChoiceChipsProps {
  /** Question for the whole group ("Modalidades que aceptas"). */
  legend: string
  options: readonly ChoiceOption[]
  /** Values that are marked. */
  value: readonly string[]
  onChange: (next: string[]) => void
  hint?: string
  error?: ReactNode
}

/**
 * A group of checkboxes drawn as chips. They are native checkboxes (keyboard, Space, screen
 * readers), inside a `fieldset` with a `legend`; the chip is only the way they look.
 */
export function ChoiceChips({ legend, options, value, onChange, hint, error }: ChoiceChipsProps) {
  const groupId = useId()
  const hintId = `${groupId}-hint`
  const errorId = `${groupId}-error`
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ')

  function toggle(option: string, checked: boolean) {
    // Keep the order of the options, whatever the order of the clicks.
    const marked = new Set(value)
    if (checked) marked.add(option)
    else marked.delete(option)
    onChange(options.map((item) => item.value).filter((item) => marked.has(item)))
  }

  return (
    <fieldset className="chips" aria-describedby={describedBy || undefined}>
      <legend className="field__label">{legend}</legend>
      <div className="chips__options">
        {options.map((option) => (
          <label key={option.value} className="chip-option">
            <input
              type="checkbox"
              className="chip-option__input"
              name={option.value}
              checked={value.includes(option.value)}
              aria-invalid={error ? true : undefined}
              onChange={(event) => toggle(option.value, event.target.checked)}
            />
            <span className="chip-option__label">{option.label}</span>
          </label>
        ))}
      </div>
      {hint && (
        <p id={hintId} className="field__hint">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="field__error">
          <span className="field__error-mark" aria-hidden="true">
            {es.identity.form.errorMark}
          </span>
          <span>{error}</span>
        </p>
      )}
    </fieldset>
  )
}
