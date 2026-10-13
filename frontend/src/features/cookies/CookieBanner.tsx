import { useEffect, useRef } from 'react'
import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { es } from '../../i18n/es'
import { LEGAL_PATHS } from '../legal/paths'
import { useCookieConsent } from './useCookieConsent'
import './CookieBanner.css'

/** Custom property that tells the page how much room the notice takes at the bottom. */
const SPACE_VARIABLE = '--cookie-banner-space'

/**
 * Cookie notice (002-11 to 002-13, 002-15). It is not a modal: no layer, no focus trap. It
 * sticks to the bottom edge of the window while it is on screen, and its place in the page is
 * right before the footer, so the footer stays reachable. "Aceptar" and "Rechazar" are the same
 * button in size and style, one click each. Which categories exist comes from the catalog.
 */
export function CookieBanner() {
  const { catalog, consent, acceptAll, rejectOptional, openPanel } = useCookieConsent()
  const bannerRef = useRef<HTMLElement>(null)
  const visible = consent === null

  // The page scrolls with the notice's height as bottom margin, so a focused element is never
  // left behind the notice (WCAG 2.4.11).
  useEffect(() => {
    const banner = bannerRef.current
    const root = document.documentElement
    if (!visible || !banner || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      root.style.setProperty(SPACE_VARIABLE, `${banner.offsetHeight}px`)
    })
    observer.observe(banner)
    return () => {
      observer.disconnect()
      root.style.removeProperty(SPACE_VARIABLE)
    }
  }, [visible])

  if (!visible) return null

  const optional = catalog.categories.filter((category) => !category.required)

  return (
    <section ref={bannerRef} className="cookie-banner" aria-label={es.cookies.banner.label}>
      <div className="container cookie-banner__inner">
        <div className="cookie-banner__text">
          <p>
            {optional.length === 0
              ? es.cookies.banner.onlyNecessary
              : es.cookies.banner.withOptional(
                  optional.map((category) => category.name).join(', '),
                )}{' '}
            <Link to={LEGAL_PATHS.cookies}>{es.cookies.banner.policyLink}</Link>
          </p>
        </div>
        <div className="cookie-banner__actions">
          <Button variant="quiet" onClick={acceptAll}>
            {es.cookies.banner.accept}
          </Button>
          <Button variant="quiet" onClick={rejectOptional}>
            {es.cookies.banner.reject}
          </Button>
          <button
            type="button"
            className="cookie-banner__configure"
            onClick={(event) => openPanel(event.currentTarget)}
          >
            {es.cookies.banner.configure}
          </button>
        </div>
      </div>
    </section>
  )
}
