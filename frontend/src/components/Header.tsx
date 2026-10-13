import { useEffect, useId, useRef, useState } from 'react'
import { Link, NavLink } from 'react-router'
import { es } from '../i18n/es'
import { sessionPaths } from '../session/sessionPaths'
import { useSession } from '../session/useSession'
import { useSignOut } from '../session/useSignOut'
import { Button } from './Button'
import { Logo } from './Logo'
import './Header.css'

/** Scroll distance (px) after which the header shows its bottom line. */
const SCROLLED_AFTER = 8

/** After this many ms without an answer about the session, "Cerrar sesión" is offered anyway. */
const SLOW_SESSION_MS = 1500

/**
 * Fixed header. It shows a bottom line once the page scrolls more than 8 px. Under tablet
 * width the menu folds into the "Menú" button (`aria-expanded`, `aria-controls`); it closes
 * when a link is chosen or with Esc, and then focus goes back to the button.
 */
export function Header() {
  const menuId = useId()
  const session = useSession()
  const { signOut, pending: signingOut, failed: signOutFailed } = useSignOut()
  const [slow, setSlow] = useState(false)
  const [open, setOpen] = useState(false)
  const [scrolled, setScrolled] = useState(() => window.scrollY > SCROLLED_AFTER)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLElement>(null)
  const focusFirstLink = useRef(false)

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

  useEffect(() => {
    if (!open) return
    // Opening with the button moves focus to the first link, so the keyboard continues
    // inside the menu (the menu is drawn after the button has been pressed).
    if (focusFirstLink.current) {
      focusFirstLink.current = false
      menuRef.current?.querySelector('a')?.focus()
    }
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setOpen(false)
      buttonRef.current?.focus()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open])

  function toggleMenu() {
    focusFirstLink.current = !open
    setOpen(!open)
  }

  const closeMenu = () => setOpen(false)
  const status = session.status
  const unavailable = status === 'unavailable'
  // "Cerrar sesión" does not depend on `GET /auth/me`: it is offered whenever a session may exist.
  const canSignOut = status === 'authenticated' || unavailable || (status === 'loading' && slow)
  const logout = () => {
    closeMenu()
    void signOut()
  }

  return (
    <header className="header" data-scrolled={scrolled || undefined}>
      <div className="container header__bar">
        <Link className="header__brand" to="/" aria-label={es.app.homeLink} onClick={closeMenu}>
          <Logo />
        </Link>

        <nav
          ref={menuRef}
          id={menuId}
          className="header__menu"
          aria-label={es.app.mainNavLabel}
          data-open={open}
        >
          <ul className="header__list">
            <li>
              <NavLink className="header__link" to="/" end onClick={closeMenu}>
                {es.nav.home}
              </NavLink>
            </li>
            <li>
              <NavLink className="header__link" to="/oportunidades" onClick={closeMenu}>
                {es.nav.opportunities}
              </NavLink>
            </li>
            <li>
              <Link
                className="header__link"
                to={{ pathname: '/', hash: 'como-funciona' }}
                onClick={closeMenu}
              >
                {es.nav.how}
              </Link>
            </li>
            <li>
              <Link
                className="header__link"
                to={{ pathname: '/', hash: 'dora' }}
                onClick={closeMenu}
              >
                {es.nav.dora}
              </Link>
            </li>
            {/* Only in the folded menu: on wide screens the account actions sit on the right. */}
            {status === 'authenticated' && (
              <li className="header__list-login">
                <NavLink className="header__link" to={sessionPaths.dashboard} onClick={closeMenu}>
                  {es.nav.dashboard}
                </NavLink>
              </li>
            )}
            {canSignOut && (
              <li className="header__list-login">
                <button
                  type="button"
                  className="header__link header__link--button"
                  disabled={signingOut}
                  onClick={logout}
                >
                  {es.identity.nav.logout}
                </button>
              </li>
            )}
            {status === 'visitor' && (
              <li className="header__list-login">
                <NavLink className="header__link" to="/ingresar" onClick={closeMenu}>
                  {es.nav.login}
                </NavLink>
              </li>
            )}
          </ul>
        </nav>

        <div className="header__actions">
          {canSignOut && (
            <Button
              className="header__login"
              variant="quiet"
              size="sm"
              disabled={signingOut}
              onClick={logout}
            >
              {es.identity.nav.logout}
            </Button>
          )}
          {status === 'authenticated' && (
            <Button size="sm" to={sessionPaths.dashboard} onClick={closeMenu}>
              {es.nav.dashboard}
            </Button>
          )}
          {unavailable && (
            <Button size="sm" onClick={() => void session.refresh()}>
              {es.common.retry}
            </Button>
          )}
          {status === 'visitor' && (
            <>
              <Button
                className="header__login"
                variant="quiet"
                size="sm"
                to="/ingresar"
                onClick={closeMenu}
              >
                {es.nav.login}
              </Button>
              <Button size="sm" to="/registro" onClick={closeMenu}>
                {es.nav.register}
              </Button>
            </>
          )}
        </div>

        <button
          ref={buttonRef}
          type="button"
          className="header__menu-button"
          aria-expanded={open}
          aria-controls={menuId}
          onClick={toggleMenu}
        >
          {es.nav.menu}
        </button>

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
