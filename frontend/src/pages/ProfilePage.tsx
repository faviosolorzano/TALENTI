import { Notice } from '../components/Notice'
import { RequireSession } from '../features/identity/RequireSession'
import { ProfileScreen } from '../features/profile/ProfileScreen'
import { es } from '../i18n/es'
import { useSession } from '../session/useSession'
import '../features/profile/ProfileScreen.css'

/** Private: without a session it leads to /ingresar (spec 001). Only students have a profile. */
export function ProfilePage() {
  return (
    <RequireSession>
      <StudentOnly />
    </RequireSession>
  )
}

function StudentOnly() {
  const session = useSession()
  if (session.isStudent) return <ProfileScreen />
  return (
    <div className="container profile">
      <Notice tone="info" announce="none">
        <h1 className="notice__title">{es.profile.studentsOnlyTitle}</h1>
        <p>{es.profile.studentsOnlyBody}</p>
      </Notice>
    </div>
  )
}
