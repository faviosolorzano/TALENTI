import { useId } from 'react'
import type { LegalCatalog } from '../../api/legal'
import { Markdown } from '../../components/Markdown'
import type { ResourceState } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import '../vacancies/Skeletons.css'
import { formatPublishedDate } from '../legal/format'
import { consentDocument } from './legalDocuments'
import './ConsentPanel.css'

interface ConsentPanelProps {
  legal: ResourceState<LegalCatalog>
  onRetry: () => void
}

/**
 * The consent text, from `GET /legal/current`. While it loads there is a skeleton; if it does
 * not load there is a "Reintentar" button (and the form cannot be sent: what was not read
 * cannot be accepted). When it is there it opens on demand, with its version and date.
 */
export function ConsentPanel({ legal, onRetry }: ConsentPanelProps) {
  const headingId = useId()

  if (legal.status === 'loading') {
    return (
      <section className="consent" aria-labelledby={headingId} aria-busy="true">
        <h2 id={headingId} className="consent__title">
          {es.identity.consent.sectionTitle}
        </h2>
        <p className="visually-hidden" role="status">
          {es.identity.consent.loading}
        </p>
        <div className="consent__skeleton" aria-hidden="true">
          <span className="skeleton skeleton--line" />
          <span className="skeleton skeleton--line" />
          <span className="skeleton skeleton--line skeleton--short" />
        </div>
      </section>
    )
  }

  const catalog = legal.status === 'success' ? legal.data : null
  const consent = catalog ? consentDocument(catalog) : null
  if (!catalog || !consent) {
    return (
      <section className="consent" aria-labelledby={headingId}>
        <h2 id={headingId} className="consent__title">
          {es.identity.consent.sectionTitle}
        </h2>
        <ErrorNotice message={es.identity.consent.loadError} onRetry={onRetry} />
      </section>
    )
  }

  const published = formatPublishedDate(catalog.publishedAt)
  return (
    <section className="consent" aria-labelledby={headingId}>
      <h2 id={headingId} className="consent__title">
        {es.identity.consent.sectionTitle}
      </h2>
      <details className="consent__details">
        <summary>{es.identity.consent.summary}</summary>
        <div className="consent__text">
          <p className="consent__version">
            {published
              ? es.legal.version(catalog.version, published)
              : es.legal.versionPendingDate(catalog.version)}
          </p>
          {consent.sections.map((section, index) => (
            <div key={`${section.heading}-${index}`} className="consent__section">
              {section.heading && <h3>{section.heading}</h3>}
              <Markdown>{section.body}</Markdown>
            </div>
          ))}
        </div>
      </details>
    </section>
  )
}
