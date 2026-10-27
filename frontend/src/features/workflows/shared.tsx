import { type ReactNode } from 'react'
import { Link } from 'react-router'
import { es } from '../../i18n/es'
import { useSession } from '../../session/useSession'
import { RequireSession } from '../identity/RequireSession'
import { Button } from '../../components/Button'
import './workflows.css'
import { t } from './workflowLabels'
export function StudentWorkflow({ children }: { children: ReactNode }) {
  const session = useSession()
  return (
    <RequireSession>
      {session.isStudent ? (
        children
      ) : (
        <div className="container workflow">
          <h1>{es.profile.studentsOnlyTitle}</h1>
          <p>{es.profile.studentsOnlyBody}</p>
        </div>
      )}
    </RequireSession>
  )
}
export function WorkflowFrame({
  title,
  back = '/perfil',
  children,
}: {
  title: string
  back?: string
  children: ReactNode
}) {
  return (
    <div className="container workflow">
      <Link className="workflow__back" to={back}>
        {t.back}
      </Link>
      <h1>{title}</h1>
      {children}
    </div>
  )
}
export function ActionError({ message }: { message: string }) {
  return message ? (
    <p role="alert" className="workflow__error">
      {message}
    </p>
  ) : null
}
export function Loading() {
  return (
    <p role="status" aria-busy="true">
      {t.loading}
    </p>
  )
}
export function Source({ value }: { value: string }) {
  return (
    <small className="workflow__source">
      {value === 'AI'
        ? t.ai
        : value === 'BANK'
          ? t.bank
          : value === 'TEMPLATE'
            ? t.template
            : t.rules}
    </small>
  )
}
export function ConfirmActions({
  busy,
  onConfirm,
  onCancel,
  label = t.confirm,
}: {
  busy: boolean
  onConfirm: () => void
  onCancel: () => void
  label?: string
}) {
  return (
    <div className="workflow__actions">
      <Button disabled={busy} onClick={onConfirm}>
        {busy ? t.working : label}
      </Button>
      <Button disabled={busy} variant="quiet" onClick={onCancel}>
        {t.cancel}
      </Button>
    </div>
  )
}
