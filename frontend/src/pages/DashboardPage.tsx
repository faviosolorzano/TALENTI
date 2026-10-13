import { DashboardScreen } from '../features/identity/DashboardScreen'
import { RequireSession } from '../features/identity/RequireSession'

/** Private: without a session it leads to /ingresar (spec 001). */
export function DashboardPage() {
  return (
    <RequireSession>
      <DashboardScreen />
    </RequireSession>
  )
}
