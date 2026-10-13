import { useEffect, useRef } from 'react'
import { Outlet, useLocation, useMatches } from 'react-router'
import { Header } from '../components/Header'
import { SiteFooter } from '../components/SiteFooter'
import { CookieBanner } from '../features/cookies/CookieBanner'
import { CookieConsentProvider } from '../features/cookies/CookieConsentProvider'
import { CookiePanel } from '../features/cookies/CookiePanel'
import { es } from '../i18n/es'
import { isRouteHandle } from './routeHandle'
import { useScrollToHash } from './useScrollToHash'
import './Layout.css'

export function Layout() {
  const { pathname } = useLocation()
  const matches = useMatches()
  const mainRef = useRef<HTMLElement>(null)
  const previousPathname = useRef(pathname)

  const title = matches.map((match) => match.handle).findLast(isRouteHandle)?.title

  useScrollToHash()

  // On every route: the tab title follows the page.
  useEffect(() => {
    document.title = title ? `${title} · ${es.app.name}` : es.app.name
  }, [title])

  // On route change (not on first load): move focus to the main content so keyboard and
  // screen reader users start at the new page.
  useEffect(() => {
    if (previousPathname.current === pathname) return
    previousPathname.current = pathname
    mainRef.current?.focus({ preventScroll: true })
  }, [pathname])

  return (
    <CookieConsentProvider>
      <div className="layout">
        <a className="layout__skip-link" href="#contenido">
          {es.app.skipToContent}
        </a>

        <Header />

        <main id="contenido" className="layout__main" tabIndex={-1} ref={mainRef}>
          <Outlet />
        </main>

        {/* Spec 002: the cookie notice goes right before the footer. */}
        <CookieBanner />
        <SiteFooter />
      </div>
      <CookiePanel />
    </CookieConsentProvider>
  )
}
