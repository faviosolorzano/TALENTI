import { Link, NavLink, Outlet } from 'react-router'
import { Logo } from '../components/Logo'
import { es } from '../i18n/es'
import './Layout.css'

const navItems = [
  { to: '/', label: es.nav.home, end: true },
  { to: '/registro', label: es.nav.register, end: false },
  { to: '/ingresar', label: es.nav.login, end: false },
  { to: '/panel', label: es.nav.dashboard, end: false },
] as const

export function Layout() {
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

      <main id="contenido" className="layout__main" tabIndex={-1}>
        <Outlet />
      </main>
    </div>
  )
}
