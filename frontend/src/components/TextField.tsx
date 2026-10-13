import { useId, type InputHTMLAttributes, type ReactNode, type Ref } from 'react'
import { es } from '../i18n/es'
import './TextField.css'

export interface TextFieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'id'> {
  label: string
  /** Help shown under the field (read with the field). */
  hint?: string
  /** Problem with the value. Shown under the field and tied to it with `aria-describedby`. */
  error?: ReactNode
  /** A control placed at the right end of the label line (for example "Mostrar"). */
  labelAction?: ReactNode
  ref?: Ref<HTMLInputElement>
  /** Id of the input. Optional: it is generated when missing. */
  id?: string
}

/**
 * Label + input + hint + error. The label is always visible; the error and the hint are linked
 * with `aria-describedby`; `aria-invalid` marks a field with a problem. Only tokens for style.
 */
export function TextField({
  label,
  hint,
  error,
  labelAction,
  ref,
  id,
  className,
  ...inputProps
}: TextFieldProps) {
  const generatedId = useId()
  const inputId = id ?? generatedId
  const hintId = `${inputId}-hint`
  const errorId = `${inputId}-error`
  const describedBy =
    [hint ? hintId : null, error ? errorId : null, inputProps['aria-describedby']]
      .filter(Boolean)
      .join(' ') || undefined

  return (
    <div className={className ? `field ${className}` : 'field'}>
      <div className="field__head">
        <label className="field__label" htmlFor={inputId}>
          {label}
        </label>
        {labelAction}
      </div>
      <input
        {...inputProps}
        id={inputId}
        ref={ref}
        className="field__input"
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
      />
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
