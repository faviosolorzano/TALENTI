import { useLocation } from 'react-router'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { useSession } from '../../session/useSession'
import { useSignOut } from '../../session/useSignOut'
import './DashboardScreen.css'

/**
 * Private page /panel (spec 001, almost empty on purpose): a greeting, "Cerrar sesión" and, for
 * a student who has just signed up, "Tu cuenta está lista". S2 and S3 fill it later. A company
 * or admin account (seeded) is told it has no screens yet (001-21).
 */
export function DashboardScreen() {
  const session = useSession()
  const location = useLocation()
  const { signOut, pending } = useSignOut()
  const justRegistered = (location.state as { welcome?: unknown } | null)?.welcome === true
  const email = session.user?.email ?? ''

  return (
    <div className="container dashboard">
      <h1 className="dashboard__title">{es.pages.dashboard.title}</h1>

      {justRegistered && session.isStudent && (
        <Notice tone="success" announce="status">
          <p className="notice__title">{es.identity.dashboard.welcomeTitle}</p>
          <p>{es.identity.dashboard.welcomeBody}</p>
        </Notice>
      )}

      <p className="dashboard__greeting">{es.identity.dashboard.greeting(email)}</p>

      {session.isStudent ? (
        <p className="dashboard__lead">{es.identity.dashboard.studentLead}</p>
      ) : (
        <Notice tone="info" announce="none">
          <p className="notice__title">{es.identity.dashboard.noScreensTitle}</p>
          <p>{es.identity.dashboard.noScreensBody}</p>
        </Notice>
      )}

      {session.signOutFailed && (
        <Notice tone="error" announce="alert">
          <p>{es.identity.dashboard.logoutFailed}</p>
        </Notice>
      )}

      <div className="dashboard__actions">
        {session.isStudent && (
          <Button to={sessionPaths.opportunities}>{es.identity.dashboard.explore}</Button>
        )}
        <Button variant="quiet" onClick={() => void signOut()} disabled={pending}>
          {pending ? es.identity.dashboard.loggingOut : es.identity.dashboard.logout}
        </Button>
      </div>
    </div>
  )
}
