import type { ReactNode } from 'react'
import { NavLink } from 'react-router'
import { es } from '../i18n/es'
import { sessionPaths } from '../session/sessionPaths'
import './TabBar.css'

/** Simple line icons (24 px), drawn with the current color. Decorative: the name goes beside. */
const ICONS = {
  home: <path d="M4 10.4 12 4l8 6.4V19a1 1 0 0 1-1 1h-4.5v-5.5h-5V20H5a1 1 0 0 1-1-1z" />,
  vacancies: (
    <>
      <rect x="3.5" y="7.5" width="17" height="12" rx="2" />
      <path d="M9 7.5V6a1.5 1.5 0 0 1 1.5-1.5h3A1.5 1.5 0 0 1 15 6v1.5M3.5 12.5h17" />
    </>
  ),
  applications: (
    <>
      <path d="M14 3.5H7.5A1.5 1.5 0 0 0 6 5v14a1.5 1.5 0 0 0 1.5 1.5h9A1.5 1.5 0 0 0 18 19V7.5z" />
      <path d="M14 3.5v4h4M9.2 14l2 2 3.6-3.8" />
    </>
  ),
  profile: (
    <>
      <circle cx="12" cy="8.5" r="3.5" />
      <path d="M5 20c.8-3.6 3.6-5.5 7-5.5s6.2 1.9 7 5.5" />
    </>
  ),
} satisfies Record<string, ReactNode>

const TABS = [
  { to: '/', label: es.nav.home, icon: ICONS.home, end: true },
  { to: '/oportunidades', label: es.nav.opportunities, icon: ICONS.vacancies, end: false },
  {
    to: sessionPaths.applications,
    label: es.nav.myApplications,
    icon: ICONS.applications,
    end: false,
  },
  { to: sessionPaths.profile, label: es.nav.myProfile, icon: ICONS.profile, end: false },
] as const

/**
 * Navigation of a student (011-06): Inicio, Vacantes, Postulaciones and Perfil. Under 860 px it
 * is a tab bar fixed at the bottom of the screen (icon + name); from 860 px the same links sit in
 * the header as text. The tab of a section stays marked on its inner pages (a vacancy, an
 * application), because `NavLink` matches the start of the address.
 */
export function TabBar() {
  return (
    <nav className="tabbar" aria-label={es.app.mainNavLabel}>
      <ul className="tabbar__list">
        {TABS.map((tab) => (
          <li key={tab.to}>
            <NavLink className="tabbar__link" to={tab.to} end={tab.end}>
              <svg
                className="tabbar__icon"
                viewBox="0 0 24 24"
                aria-hidden="true"
                focusable="false"
              >
                {tab.icon}
              </svg>
              <span className="tabbar__label">{tab.label}</span>
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}
