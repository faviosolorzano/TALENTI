import type { ReactNode } from 'react'
import { ApplicationsScreen } from '../features/applications/ApplicationsScreen'
import { StudentOnlyNotice } from '../features/applications/StudentOnlyNotice'
import { RequireSession } from '../features/identity/RequireSession'
import { useSession } from '../session/useSession'

/** Only students have applications: any other account gets a short notice. */
export function StudentOnly({ children }: { children: ReactNode }) {
  const session = useSession()
  return session.isStudent ? children : <StudentOnlyNotice />
}

/** Private: without a session it leads to /ingresar. */
export function ApplicationsPage() {
  return (
    <RequireSession>
      <StudentOnly>
        <ApplicationsScreen />
      </StudentOnly>
    </RequireSession>
  )
}
