import { Navigate, useLocation } from 'react-router'
import { DashboardScreen } from '../features/identity/DashboardScreen'
import { RequireSession } from '../features/identity/RequireSession'
import { sessionPaths } from '../session/sessionPaths'
import { useSession } from '../session/useSession'

/**
 * Private: without a session it leads to /ingresar (spec 001). A student no longer has a panel:
 * `/panel` takes them to the Home, keeping the "Tu cuenta está lista" mark (005-04). A company
 * or admin account still gets its notice (001-21).
 */
export function DashboardPage() {
  return (
    <RequireSession>
      <PanelOrHome />
    </RequireSession>
  )
}

function PanelOrHome() {
  const session = useSession()
  const location = useLocation()
  if (session.isStudent) {
    return <Navigate to={sessionPaths.home} replace state={location.state} />
  }
  return <DashboardScreen />
}
