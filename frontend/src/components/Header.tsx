import { useEffect, useId, useRef, useState } from 'react'
import { Link, NavLink, useLocation } from 'react-router'
import { es } from '../i18n/es'
import { sessionPaths } from '../session/sessionPaths'
import { useSession } from '../session/useSession'
import { useSignOut } from '../session/useSignOut'
import { Logo } from './Logo'
import './Header.css'

const SLOW_SESSION_MS = 1500

/** One top navigation, with the same destinations on a phone and a desktop. */
export function Header() {
  const menuId = useId()
  const accountId = useId()
  const location = useLocation()
  const session = useSession()
  const { signOut, pending: signingOut, failed: signOutFailed } = useSignOut()
  const [slow, setSlow] = useState(false)
  const [openAt, setOpenAt] = useState<string | null>(null)
  const [accountOpen, setAccountOpen] = useState(false)
  const [scrolled, setScrolled] = useState(() => window.scrollY > 8)
  const headerRef = useRef<HTMLElement>(null)
  const buttonRef = useRef<HTMLButtonElement>(null)
  const accountRef = useRef<HTMLButtonElement>(null)
  const menuRef = useRef<HTMLElement>(null)
  const focusFirstLink = useRef(false)
  const open = openAt === location.key
  const status = session.status
  const unavailable = status === 'unavailable'
  const authenticated = status === 'authenticated'
  const canSignOut = authenticated || unavailable || (status === 'loading' && slow)

  const closeMenu = () => {
    setOpenAt(null)
    setAccountOpen(false)
  }

  useEffect(() => {
    const update = () => setScrolled(window.scrollY > 8)
    window.addEventListener('scroll', update, { passive: true })
    return () => window.removeEventListener('scroll', update)
  }, [])

  useEffect(() => {
    if (!window.matchMedia) return
    const width = window.matchMedia('(min-width: 53.75rem)')
    const close = () => {
      setOpenAt(null)
      setAccountOpen(false)
    }
    width.addEventListener('change', close)
    return () => width.removeEventListener('change', close)
  }, [])

  useEffect(() => {
    if (status !== 'loading') return
    const timer = setTimeout(() => setSlow(true), SLOW_SESSION_MS)
    return () => clearTimeout(timer)
  }, [status])

  useEffect(() => {
    if (open && focusFirstLink.current) {
      focusFirstLink.current = false
      menuRef.current?.querySelector('a')?.focus()
    }
    if (!open && !accountOpen) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      event.preventDefault()
      if (accountOpen) {
        setAccountOpen(false)
        accountRef.current?.focus()
      } else {
        setOpenAt(null)
        buttonRef.current?.focus()
      }
    }
    const outside = (event: PointerEvent) => {
      if (!headerRef.current?.contains(event.target as Node)) closeMenu()
    }
    document.addEventListener('keydown', onKeyDown)
    document.addEventListener('pointerdown', outside)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      document.removeEventListener('pointerdown', outside)
    }
  }, [open, accountOpen])

  const logout = () => {
    closeMenu()
    void signOut()
  }

  return (
    <header
      ref={headerRef}
      className="header"
      data-scrolled={scrolled || undefined}
      onBlur={(event) => {
        if (!event.currentTarget.contains(event.relatedTarget as Node | null)) closeMenu()
      }}
    >
      <div className="container header__bar">
        <Link className="header__brand" to="/" aria-label={es.app.homeLink} onClick={closeMenu}>
          <Logo />
        </Link>
        <button
          ref={buttonRef}
          type="button"
          className="header__menu-button"
          aria-expanded={open}
          aria-controls={menuId}
          onClick={() => {
            focusFirstLink.current = !open
            setAccountOpen(false)
            setOpenAt(open ? null : location.key)
          }}
        >
          <svg viewBox="0 0 24 24" aria-hidden="true">
            <path d="M4 6h16M4 12h16M4 18h16" />
          </svg>
          {es.nav.menu}
        </button>
        <nav
          ref={menuRef}
          id={menuId}
          className="header__menu"
          aria-label={es.app.mainNavLabel}
          data-open={open}
        >
          <ul className="header__list">
            <li>
              <NavLink className="header__link" to={sessionPaths.opportunities} onClick={closeMenu}>
                {es.nav.opportunities}
              </NavLink>
            </li>
            {session.isStudent ? (
              <>
                <li>
                  <NavLink
                    className="header__link"
                    to={sessionPaths.applications}
                    onClick={closeMenu}
                  >
                    {es.nav.myApplications}
                  </NavLink>
                </li>
                <li>
                  <NavLink className="header__link" to={sessionPaths.profile} onClick={closeMenu}>
                    {es.nav.myProfile}
                  </NavLink>
                </li>
              </>
            ) : (
              <li>
                <Link
                  className="header__link"
                  to={{ pathname: '/', hash: 'como-funciona' }}
                  onClick={closeMenu}
                >
                  {es.nav.how}
                </Link>
              </li>
            )}
            {authenticated && !session.isStudent && (
              <li>
                <NavLink className="header__link" to={sessionPaths.dashboard} onClick={closeMenu}>
                  {es.nav.dashboard}
                </NavLink>
              </li>
            )}
            {authenticated && (
              <li className="header__account">
                <button
                  ref={accountRef}
                  type="button"
                  className="header__link header__link--button"
                  aria-expanded={accountOpen}
                  aria-controls={accountId}
                  onClick={() => setAccountOpen(!accountOpen)}
                >
                  {es.nav.account}
                  <svg viewBox="0 0 24 24" aria-hidden="true">
                    <path d="m6 9 6 6 6-6" />
                  </svg>
                </button>
                <ul id={accountId} className="header__account-panel" hidden={!accountOpen}>
                  <li>
                    <button
                      type="button"
                      className="header__link header__link--button"
                      disabled={signingOut}
                      onClick={logout}
                    >
                      {es.identity.nav.logout}
                    </button>
                  </li>
                </ul>
              </li>
            )}
            {canSignOut && !authenticated && (
              <li>
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
            {unavailable && (
              <li>
                <button
                  className="header__link header__link--button"
                  type="button"
                  onClick={() => void session.refresh()}
                >
                  {es.common.retry}
                </button>
              </li>
            )}
            {status === 'visitor' && (
              <li>
                <NavLink
                  className="btn btn--primary btn--sm header__login"
                  to={sessionPaths.login}
                  onClick={closeMenu}
                >
                  {es.nav.login}
                </NavLink>
              </li>
            )}
          </ul>
        </nav>
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
