import { Notice } from '../components/Notice'
import { CvReaderScreen } from '../features/cv/CvReaderScreen'
import { RequireSession } from '../features/identity/RequireSession'
import { es } from '../i18n/es'
import { useSession } from '../session/useSession'
import '../features/profile/ProfileScreen.css'

/** Private: without a session it leads to /ingresar. Only students read a CV. */
export function CvReaderPage() {
  return (
    <RequireSession>
      <StudentOnly />
    </RequireSession>
  )
}

function StudentOnly() {
  const session = useSession()
  if (session.isStudent) return <CvReaderScreen />
  return (
    <div className="container profile">
      <Notice tone="info" announce="none">
        <h1 className="notice__title">{es.profile.studentsOnlyTitle}</h1>
        <p>{es.profile.studentsOnlyBody}</p>
      </Notice>
    </div>
  )
}
