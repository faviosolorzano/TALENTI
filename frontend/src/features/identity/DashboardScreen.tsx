import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import { es } from '../../i18n/es'
import { useSession } from '../../session/useSession'
import { useSignOut } from '../../session/useSignOut'
import './DashboardScreen.css'

/**
 * Private page /panel for a company or admin account: they have no screens yet (001-21), so it
 * greets them and offers "Cerrar sesión". A student does not come here: `/panel` takes them to
 * the Home (spec 005-04).
 */
export function DashboardScreen() {
  const session = useSession()
  const { signOut, pending, failed } = useSignOut()
  const email = session.user?.email ?? ''

  return (
    <div className="container dashboard">
      <h1 className="dashboard__title">{es.pages.dashboard.title}</h1>

      <p className="dashboard__greeting">{es.identity.dashboard.greeting(email)}</p>

      <Notice tone="info" announce="none">
        <p className="notice__title">{es.identity.dashboard.noScreensTitle}</p>
        <p>{es.identity.dashboard.noScreensBody}</p>
      </Notice>

      {failed && (
        <Notice tone="error" announce="alert">
          <p>{es.identity.dashboard.logoutFailed}</p>
        </Notice>
      )}

      <div className="dashboard__actions">
        <Button variant="quiet" onClick={() => void signOut()} disabled={pending}>
          {pending ? es.identity.dashboard.loggingOut : es.identity.dashboard.logout}
        </Button>
      </div>
    </div>
  )
}
