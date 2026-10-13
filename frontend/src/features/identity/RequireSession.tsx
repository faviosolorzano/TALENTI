import { useEffect, type ReactNode } from 'react'
import { Navigate, useLocation } from 'react-router'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import { es } from '../../i18n/es'
import { acceptDocumentsPath } from '../legal/paths'
import { loginPathWithReturn } from '../../session/returnTo'
import { useSession } from '../../session/useSession'
import { useSignOut } from '../../session/useSignOut'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import '../vacancies/Skeletons.css'
import './RequireSession.css'

/**
 * Wraps a private page (001-13, 001-14, 001-30). Without a session it goes to
 * `/ingresar?returnTo=...`; with a legal acceptance pending it goes to the acceptance screen
 * (spec 002). Each time a private page opens, the session is checked again with the server,
 * so a session that expired while the tab was idle is noticed here ("Tu sesión expiró").
 */
export function RequireSession({ children }: { children: ReactNode }) {
  const session = useSession()
  const location = useLocation()
  const { refresh } = session
  const { signOut, pending: signingOut, failed: signOutFailed } = useSignOut()

  // "Cerrar sesión" never depends on the check of the session (`GET /auth/me`).
  const signOutAction = (
    <div className="guard__actions">
      {signOutFailed && (
        <Notice tone="error" announce="alert">
          <p>{es.identity.dashboard.logoutFailed}</p>
        </Notice>
      )}
      <div>
        <Button variant="quiet" disabled={signingOut} onClick={() => void signOut()}>
          {signingOut ? es.identity.dashboard.loggingOut : es.identity.dashboard.logout}
        </Button>
      </div>
    </div>
  )

  useEffect(() => {
    // `onlyIfStale`: the first check of the app (or one just made) is not repeated.
    void refresh({ onlyIfStale: true })
  }, [refresh, location.pathname])

  if (session.status === 'loading') {
    return (
      <div className="container guard" aria-busy="true">
        <h1 className="visually-hidden">{es.pages.dashboard.title}</h1>
        <p className="visually-hidden" role="status">
          {es.identity.guard.loading}
        </p>
        <div className="guard__skeleton" aria-hidden="true">
          <span className="skeleton skeleton--heading" />
          <span className="skeleton skeleton--line" />
          <span className="skeleton skeleton--line skeleton--short" />
        </div>
        {signOutAction}
      </div>
    )
  }

  if (session.status === 'unavailable') {
    return (
      <div className="container guard">
        <h1 className="visually-hidden">{es.pages.dashboard.title}</h1>
        <ErrorNotice
          message={es.identity.guard.unavailable}
          onRetry={() => void session.refresh()}
        />
        {signOutAction}
      </div>
    )
  }

  const here = `${location.pathname}${location.search}${location.hash}`

  if (session.status === 'visitor') {
    return (
      <Navigate
        to={loginPathWithReturn(here)}
        replace
        state={session.notice ? { notice: session.notice } : undefined}
      />
    )
  }

  if (session.legalAcceptancePending) {
    return <Navigate to={acceptDocumentsPath(here)} replace />
  }

  return children
}
