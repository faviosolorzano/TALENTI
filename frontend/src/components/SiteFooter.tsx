import { Link } from 'react-router'
import { useCookieConsent } from '../features/cookies/useCookieConsent'
import { LEGAL_PATHS } from '../features/legal/paths'
import { es } from '../i18n/es'
import './SiteFooter.css'

/**
 * Footer of every screen (spec 011, 011-08): one discreet line with Términos, Privacidad,
 * Transparencia and Cookies (which opens the preferences; the panel links to the Cookie
 * Policy), and the note that the companies and vacancies are made up. It does not depend on
 * the API or on the session.
 */
export function SiteFooter() {
  const { openPanel } = useCookieConsent()

  return (
    <footer className="site-footer">
      <div className="container site-footer__inner">
        <nav aria-label={es.footer.label}>
          <ul className="site-footer__list">
            <li>
              <Link to={LEGAL_PATHS.terms}>{es.footer.terms}</Link>
            </li>
            <li>
              <Link to={LEGAL_PATHS.privacy}>{es.footer.privacy}</Link>
            </li>
            <li>
              <Link to={LEGAL_PATHS.transparency}>{es.footer.transparency}</Link>
            </li>
            <li>
              <button
                type="button"
                aria-haspopup="dialog"
                onClick={(event) => openPanel(event.currentTarget)}
              >
                {es.footer.cookies}
              </button>
            </li>
          </ul>
        </nav>
        <p className="site-footer__note">{es.footer.sampleNote}</p>
      </div>
    </footer>
  )
}
