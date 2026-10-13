import { RequireSession } from '../features/identity/RequireSession'
import { HomeLoading } from '../features/home/HomeLoading'
import { StudentHome } from '../features/home/StudentHome'
import { LandingPage } from '../features/landing/LandingPage'
import { useSession } from '../session/useSession'

/**
 * `/` depends on who is here (spec 005-01, 005-02): a student with a session sees the Home;
 * a visitor, a company or an admin sees the public landing page. While the session is being
 * checked a neutral placeholder is shown, so the landing page does not flash first. If the
 * check fails (`unavailable`) the landing page is shown with the usual note in the header.
 */
export function HomePage() {
  const session = useSession()

  if (session.status === 'loading') return <HomeLoading />

  if (session.isStudent) {
    return (
      <RequireSession>
        <StudentHome />
      </RequireSession>
    )
  }

  return <LandingPage />
}
