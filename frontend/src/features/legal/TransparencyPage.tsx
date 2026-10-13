import { Link } from 'react-router'
import { Markdown } from '../../components/Markdown'
import { es } from '../../i18n/es'
import { DraftNotice } from './DraftNotice'
import './LegalPage.css'

/** `/transparencia` (002-10): a plain-language summary of the Privacy Policy. Static text in `es.ts`. */
export function TransparencyPage() {
  return (
    <article className="container legal">
      <header className="legal__header">
        <h1 className="legal__title">{es.transparency.title}</h1>
        <DraftNotice />
      </header>

      <div className="legal__content">
        <div className="legal__lead">
          <Markdown>{es.transparency.lead}</Markdown>
        </div>
        <div className="legal__sections">
          {es.transparency.blocks.map((block) => (
            <section key={block.id} className="legal-section" aria-labelledby={`${block.id}-title`}>
              <h2 id={`${block.id}-title`} className="legal-section__heading">
                {block.heading}
              </h2>
              <Markdown>{block.body}</Markdown>
              {block.links.length > 0 && (
                <ul className="legal-links">
                  {block.links.map((link) => (
                    <li key={link.to}>
                      <Link to={link.to}>{link.label}</Link>
                    </li>
                  ))}
                </ul>
              )}
            </section>
          ))}
        </div>
      </div>
    </article>
  )
}
