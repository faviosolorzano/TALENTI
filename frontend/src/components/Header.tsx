import { useEffect, useState } from 'react'
import { Link, NavLink, useLocation } from 'react-router'
import { es } from '../i18n/es'
import { sessionPaths } from '../session/sessionPaths'
import { useSession } from '../session/useSession'
import { useSignOut } from '../session/useSignOut'
import { Logo } from './Logo'
import { TabBar } from './TabBar'
import './Header.css'

/** Scroll distance (px) after which the header shows its bottom line. */
const SCROLLED_AFTER = 8

/** After this many ms without an answer about the session, "Cerrar sesión" is offered anyway. */
const SLOW_SESSION_MS = 1500

/**
 * Fixed header (spec 011). It never uses the primary button: the main action of each screen
 * lives in the page. Nothing folds into a menu:
 * - visitor: logo · Vacantes · Ingresar · Crear cuenta (on /registro and /ingresar, only the
 *   logo and Vacantes, so the page's own form is the only way forward);
 * - student: from 860 px, logo · Inicio · Vacantes · Postulaciones · Perfil · Cerrar sesión;
 *   under 860 px, the logo here and the four sections in the tab bar at the bottom;
 * - another role: logo · Vacantes · Mi panel · Cerrar sesión.
 */
export function Header() {
  const session = useSession()
  const { pathname } = useLocation()
  const { signOut, pending: signingOut, failed: signOutFailed } = useSignOut()
  const [slow, setSlow] = useState(false)
  const [scrolled, setScrolled] = useState(() => window.scrollY > SCROLLED_AFTER)

  useEffect(() => {
    const update = () => setScrolled(window.scrollY > SCROLLED_AFTER)
    window.addEventListener('scroll', update, { passive: true })
    return () => window.removeEventListener('scroll', update)
  }, [])

  // The first answer about the session is slow: do not leave a signed-in person without a way out.
  useEffect(() => {
    if (session.status !== 'loading') return
    const timer = setTimeout(() => setSlow(true), SLOW_SESSION_MS)
    return () => clearTimeout(timer)
  }, [session.status])

  const status = session.status
  const student = status === 'authenticated' && session.isStudent
  const unavailable = status === 'unavailable'
  // "Cerrar sesión" does not depend on `GET /auth/me`: it is offered whenever a session may exist.
  const canSignOut = status === 'authenticated' || unavailable || (status === 'loading' && slow)
  // On the sign-up and sign-in pages the header does not offer the other door (011-07).
  const onAuthPage = pathname === sessionPaths.register || pathname === sessionPaths.login

  return (
    <header className="header" data-scrolled={scrolled || undefined}>
      <div className="container header__bar">
        <Link className="header__brand" to="/" aria-label={es.app.homeLink}>
          <Logo />
        </Link>

        {student ? (
          <TabBar />
        ) : (
          <nav className="header__nav" aria-label={es.app.mainNavLabel}>
            <ul className="header__list">
              <li>
                <NavLink className="header__link" to="/oportunidades">
                  {es.nav.opportunities}
                </NavLink>
              </li>
              {status === 'authenticated' && (
                <li>
                  <NavLink className="header__link" to={sessionPaths.dashboard}>
                    {es.nav.dashboard}
                  </NavLink>
                </li>
              )}
            </ul>
          </nav>
        )}

        <div className="header__actions" data-student={student || undefined}>
          {unavailable && (
            <button
              type="button"
              className="header__link header__link--button"
              onClick={() => void session.refresh()}
            >
              {es.common.retry}
            </button>
          )}
          {canSignOut && (
            <button
              type="button"
              className="header__link header__link--button header__logout"
              disabled={signingOut}
              onClick={() => void signOut()}
            >
              {es.identity.nav.logout}
            </button>
          )}
          {status === 'visitor' && !onAuthPage && (
            <>
              <NavLink className="header__link" to={sessionPaths.login}>
                {es.nav.login}
              </NavLink>
              <NavLink className="header__link header__link--accent" to={sessionPaths.register}>
                {es.nav.register}
              </NavLink>
            </>
          )}
        </div>

        {/* Visible notes under the bar: the session could not be checked / sign-out failed. */}
        {unavailable && (
          <p className="header__note" role="status">
            {es.identity.guard.unavailable}
          </p>
        )}
        {signOutFailed && (
          <p className="header__note header__note--error" role="alert">
            {es.identity.dashboard.logoutFailed}
          </p>
        )}
      </div>
    </header>
  )
}
