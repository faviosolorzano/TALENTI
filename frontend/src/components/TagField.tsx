import { useId, useState, type KeyboardEvent, type ReactNode, type Ref } from 'react'
import { es } from '../i18n/es'
import { Button } from './Button'
import './TextField.css'
import './TagField.css'

interface TagFieldProps {
  label: string
  hint?: string
  error?: ReactNode
  /** The tags already added, in order. */
  values: readonly string[]
  onChange: (next: string[]) => void
  /** Text of the button that adds what was typed. */
  addLabel: string
  /** Accessible name of the "x" of a tag. */
  removeLabel: (tag: string) => string
  /** Accessible name of the list of tags. */
  listLabel: string
  maxLength?: number
  inputRef?: Ref<HTMLInputElement>
}

/**
 * A free-text list: type one word and press Enter (or the button) to add it as a tag; each tag
 * has its own "quitar" button. What is still typed when the field loses focus is added too, so
 * nothing typed is lost on the way to "Guardar". Repeats (ignoring case) are not added twice.
 */
export function TagField({
  label,
  hint,
  error,
  values,
  onChange,
  addLabel,
  removeLabel,
  listLabel,
  maxLength,
  inputRef,
}: TagFieldProps) {
  const inputId = useId()
  const hintId = `${inputId}-hint`
  const errorId = `${inputId}-error`
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ')
  const [draft, setDraft] = useState('')

  function commit() {
    const text = draft.trim()
    setDraft('')
    if (!text) return
    const exists = values.some((value) => value.toLowerCase() === text.toLowerCase())
    if (!exists) onChange([...values, text])
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter' || event.key === ',') {
      // Enter must not send the surrounding form.
      event.preventDefault()
      commit()
    } else if (event.key === 'Backspace' && draft === '' && values.length > 0) {
      onChange(values.slice(0, -1))
    }
  }

  return (
    <div className="field tags">
      <label className="field__label" htmlFor={inputId}>
        {label}
      </label>
      <div className="tags__entry">
        <input
          ref={inputRef}
          id={inputId}
          type="text"
          className="field__input"
          value={draft}
          maxLength={maxLength}
          autoComplete="off"
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy || undefined}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={onKeyDown}
          onBlur={commit}
        />
        <Button variant="quiet" onMouseDown={(event) => event.preventDefault()} onClick={commit}>
          {addLabel}
        </Button>
      </div>
      {values.length > 0 && (
        <ul className="tags__list" aria-label={listLabel}>
          {values.map((tag) => (
            <li key={tag} className="tags__item">
              <span className="tags__text">{tag}</span>
              <button
                type="button"
                className="tags__remove"
                aria-label={removeLabel(tag)}
                onClick={() => onChange(values.filter((value) => value !== tag))}
              >
                <span aria-hidden="true">×</span>
              </button>
            </li>
          ))}
        </ul>
      )}
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
    </div>
  )
}
