import { Link } from 'react-router'
import { useCookieConsent } from '../features/cookies/useCookieConsent'
import { LEGAL_PATHS } from '../features/legal/paths'
import { es } from '../i18n/es'
import './SiteFooter.css'

/**
 * Footer of every screen (002-08). It does not depend on the API or on the session: the legal
 * links and the cookie preferences are always there. In this order: Términos, Privacidad,
 * Cookies, Transparencia, and the "Preferencias de cookies" button.
 */
export function SiteFooter() {
  const { openPanel } = useCookieConsent()

  return (
    <footer className="site-footer">
      <nav className="container site-footer__nav" aria-label={es.footer.label}>
        <ul className="site-footer__list">
          <li>
            <Link to={LEGAL_PATHS.terms}>{es.footer.terms}</Link>
          </li>
          <li>
            <Link to={LEGAL_PATHS.privacy}>{es.footer.privacy}</Link>
          </li>
          <li>
            <Link to={LEGAL_PATHS.cookies}>{es.footer.cookies}</Link>
          </li>
          <li>
            <Link to={LEGAL_PATHS.transparency}>{es.footer.transparency}</Link>
          </li>
          <li>
            <button type="button" onClick={(event) => openPanel(event.currentTarget)}>
              {es.footer.cookiePreferences}
            </button>
          </li>
        </ul>
      </nav>
    </footer>
  )
}
