import { useId, type ReactNode, type Ref, type TextareaHTMLAttributes } from 'react'
import { es } from '../i18n/es'
import './TextField.css'
import './TextAreaField.css'

export interface TextAreaFieldProps extends Omit<
  TextareaHTMLAttributes<HTMLTextAreaElement>,
  'id'
> {
  label: string
  hint?: string
  error?: ReactNode
  /** A small text under the field, at the right ("120 de 500 caracteres"). Not read aloud. */
  counter?: string
  ref?: Ref<HTMLTextAreaElement>
  id?: string
}

/** Same pattern as `TextField` for longer text: visible label, hint and error tied to it. */
export function TextAreaField({
  label,
  hint,
  error,
  counter,
  ref,
  id,
  className,
  ...areaProps
}: TextAreaFieldProps) {
  const generatedId = useId()
  const areaId = id ?? generatedId
  const hintId = `${areaId}-hint`
  const errorId = `${areaId}-error`
  const describedBy =
    [hint ? hintId : null, error ? errorId : null, areaProps['aria-describedby']]
      .filter(Boolean)
      .join(' ') || undefined

  return (
    <div className={className ? `field ${className}` : 'field'}>
      <label className="field__label" htmlFor={areaId}>
        {label}
      </label>
      <textarea
        rows={4}
        {...areaProps}
        id={areaId}
        ref={ref}
        className="field__input field__input--area"
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
      />
      {(hint || counter) && (
        <div className="field__foot">
          {hint && (
            <p id={hintId} className="field__hint">
              {hint}
            </p>
          )}
          {counter && (
            <p className="field__counter" aria-hidden="true">
              {counter}
            </p>
          )}
        </div>
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
