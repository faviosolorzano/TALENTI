import { useId, type InputHTMLAttributes, type ReactNode, type Ref } from 'react'
import { es } from '../i18n/es'
import './CheckboxField.css'
import './TextField.css'

export interface CheckboxFieldProps extends Omit<
  InputHTMLAttributes<HTMLInputElement>,
  'id' | 'type' | 'children'
> {
  /** Label content. It may hold links: they keep their own behavior. */
  children: ReactNode
  /** Help read with the checkbox. */
  hint?: string
  error?: ReactNode
  ref?: Ref<HTMLInputElement>
}

/** A checkbox with its label to the right, a 44 px tall touch area, hint and linked error. */
export function CheckboxField({
  children,
  hint,
  error,
  ref,
  className,
  ...inputProps
}: CheckboxFieldProps) {
  const inputId = useId()
  const hintId = `${inputId}-hint`
  const errorId = `${inputId}-error`
  const describedBy =
    [hint ? hintId : null, error ? errorId : null, inputProps['aria-describedby']]
      .filter(Boolean)
      .join(' ') || undefined

  return (
    <div className={className ? `check ${className}` : 'check'}>
      <div className="check__row">
        <input
          {...inputProps}
          id={inputId}
          ref={ref}
          type="checkbox"
          className="check__input"
          aria-invalid={error ? true : undefined}
          aria-describedby={describedBy}
        />
        <label className="check__label" htmlFor={inputId}>
          {children}
        </label>
      </div>
      {hint && (
        <p id={hintId} className="check__hint">
          {hint}
        </p>
      )}
      {error && (
        <p id={errorId} className="check__error">
          <span className="field__error-mark" aria-hidden="true">
            {es.identity.form.errorMark}
          </span>
          <span>{error}</span>
        </p>
      )}
    </div>
  )
}
