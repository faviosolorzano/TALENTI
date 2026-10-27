import { useSearchParams } from 'react-router'
import { safeReturnTo } from '../../session/returnTo'
import { useEffect, useRef, type FormEvent, type ReactNode } from 'react'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import { es } from '../../i18n/es'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import type { SubmitState } from './useSubmit'
import './SectionForm.css'

interface SectionFormProps {
  state: SubmitState
  /** Problems of fields that this form does not draw. */
  otherProblems?: readonly string[]
  /** Text of the save button when it is not the default "Guardar". */
  saveLabel?: string
  onSubmit: () => void
  onCancel: () => void
  children: ReactNode
}

/**
 * The frame of every edit form (006-19): fields, "Guardar"/"Cancelar", the "Guardando…" state
 * and the two kinds of failure. On a 422 the focus goes to the first field with a problem; on a
 * network or server error an alert offers "Reintentar" and what was typed stays.
 */
export function SectionForm({
  state,
  otherProblems = [],
  saveLabel = es.profile.actions.save,
  onSubmit,
  onCancel,
  children,
}: SectionFormProps) {
  const [query] = useSearchParams()
  const effectiveSaveLabel = safeReturnTo(query.get('returnTo'))
    ? es.redesign.saveReturn
    : saveLabel
  const formRef = useRef<HTMLFormElement>(null)
  const saving = state.status === 'saving'

  // After a 422: focus the first field marked as invalid (the DOM order is the reading order).
  useEffect(() => {
    if (state.status !== 'invalid') return
    formRef.current?.querySelector<HTMLElement>('[aria-invalid="true"]')?.focus()
  }, [state])

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!saving) onSubmit()
  }

  return (
    <form ref={formRef} className="section-form" noValidate aria-busy={saving} onSubmit={submit}>
      {state.status === 'invalid' && (
        <Notice tone="error" announce="alert">
          <p>{es.profile.reviewFields}</p>
          {otherProblems.length > 0 && (
            <ul className="section-form__problems">
              {otherProblems.map((message) => (
                <li key={message}>{message}</li>
              ))}
            </ul>
          )}
        </Notice>
      )}

      <div className="section-form__fields">{children}</div>

      {state.status === 'failed' && <ErrorNotice message={state.message} onRetry={onSubmit} />}

      <div className="section-form__actions">
        <Button type="submit" disabled={saving}>
          {saving ? es.profile.actions.saving : effectiveSaveLabel}
        </Button>
        <Button variant="quiet" onClick={onCancel} disabled={saving}>
          {es.profile.actions.cancel}
        </Button>
      </div>
    </form>
  )
}
