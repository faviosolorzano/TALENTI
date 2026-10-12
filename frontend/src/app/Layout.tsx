import { useEffect, useRef } from 'react'
import { Link, NavLink, Outlet, useLocation, useMatches } from 'react-router'
import { Logo } from '../components/Logo'
import { es } from '../i18n/es'
import { isRouteHandle } from './routeHandle'
import './Layout.css'

const navItems = [
  { to: '/', label: es.nav.home, end: true },
  { to: '/registro', label: es.nav.register, end: false },
  { to: '/ingresar', label: es.nav.login, end: false },
  { to: '/panel', label: es.nav.dashboard, end: false },
] as const

export function Layout() {
  const { pathname } = useLocation()
  const matches = useMatches()
  const mainRef = useRef<HTMLElement>(null)
  const previousPathname = useRef(pathname)

  const title = matches.map((match) => match.handle).findLast(isRouteHandle)?.title

  // On every route: the tab title follows the page.
  useEffect(() => {
    document.title = title ? `${title} · ${es.app.name}` : es.app.name
  }, [title])

  // On route change (not on first load): move focus to the main content so keyboard and
  // screen reader users start at the new page.
  useEffect(() => {
    if (previousPathname.current === pathname) return
    previousPathname.current = pathname
    mainRef.current?.focus()
  }, [pathname])

  return (
    <div className="layout">
      <a className="layout__skip-link" href="#contenido">
        {es.app.skipToContent}
      </a>

      <header className="layout__header">
        <div className="layout__header-inner">
          <Link className="layout__brand" to="/">
            <Logo />
          </Link>

          <nav className="layout__nav" aria-label={es.app.mainNavLabel}>
            <ul className="layout__nav-list">
              {navItems.map((item) => (
                <li key={item.to}>
                  <NavLink className="layout__nav-link" to={item.to} end={item.end}>
                    {item.label}
                  </NavLink>
                </li>
              ))}
            </ul>
          </nav>
        </div>
      </header>

      <main id="contenido" className="layout__main" tabIndex={-1} ref={mainRef}>
        <Outlet />
      </main>
    </div>
  )
}
