import { ApplicationDetailScreen } from '../features/applications/ApplicationDetailScreen'
import { RequireSession } from '../features/identity/RequireSession'
import { StudentOnly } from './ApplicationsPage'

/** Private: without a session it leads to /ingresar. */
export function ApplicationDetailPage() {
  return (
    <RequireSession>
      <StudentOnly>
        <ApplicationDetailScreen />
      </StudentOnly>
    </RequireSession>
  )
}
