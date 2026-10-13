import { useEffect } from 'react'
import { useLocation } from 'react-router'
import { fetchLegalDocument, type LegalDocumentKey, type LegalSection } from '../../api/legal'
import { Markdown } from '../../components/Markdown'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import { DraftNotice } from './DraftNotice'
import { formatPublishedDate } from './format'
import { TableOfContents, type TocEntry } from './TableOfContents'
import '../vacancies/Skeletons.css'
import './LegalPage.css'

interface NumberedSection extends LegalSection {
  /** `seccion-N` for a section with a heading (N counts only those); `null` for the opening text. */
  id: string | null
}

/** Sections in the received order. Only those with a heading are numbered, so `seccion-2` is the second heading. */
function numberSections(sections: LegalSection[]): NumberedSection[] {
  let count = 0
  return sections.map((section) => ({
    ...section,
    id: section.heading ? `seccion-${++count}` : null,
  }))
}

function decodeHash(hash: string): string {
  try {
    return decodeURIComponent(hash.slice(1))
  } catch {
    return hash.slice(1)
  }
}

/**
 * A public legal page (terms, privacy or cookies). The text comes from `GET /legal/current` and
 * is never copied into the code: whatever sections arrive are shown, in that order (002-01).
 */
export function LegalDocumentPage({ documentKey }: { documentKey: LegalDocumentKey }) {
  const { hash } = useLocation()
  const { state, retry } = useApiResource(`legal:${documentKey}`, (signal) =>
    fetchLegalDocument(documentKey, signal),
  )
  const loaded = state.status === 'success'

  // A URL with #seccion-N opens in that section once the text has loaded (002-04). A section
  // that does not exist leaves the page at the top.
  useEffect(() => {
    if (!loaded || !hash) return
    const target = document.getElementById(decodeHash(hash))
    if (!target) return
    target.scrollIntoView?.()
    target.focus({ preventScroll: true })
  }, [loaded, hash])

  const title = loaded ? state.data.document.title : es.legal.fallbackTitles[documentKey]
  const sections = loaded ? numberSections(state.data.document.sections) : []
  const entries: TocEntry[] = sections.flatMap((section) =>
    section.id ? [{ id: section.id, heading: section.heading }] : [],
  )

  let version: string | null = null
  if (loaded) {
    const date = formatPublishedDate(state.data.publishedAt)
    version = date
      ? es.legal.version(state.data.version, date)
      : es.legal.versionPendingDate(state.data.version)
  }

  return (
    <article className="container legal">
      <header className="legal__header">
        <h1 className="legal__title">{title}</h1>
        {version && <p className="legal__meta">{version}</p>}
        <DraftNotice />
      </header>

      <div className="legal__content" aria-busy={state.status === 'loading'}>
        {state.status === 'loading' && (
          <>
            <p className="visually-hidden" role="status">
              {es.legal.loading}
            </p>
            <div className="legal-skeleton" aria-hidden="true">
              <span className="skeleton skeleton--heading" />
              <span className="skeleton skeleton--line" />
              <span className="skeleton skeleton--line" />
              <span className="skeleton skeleton--line skeleton--short" />
              <span className="skeleton skeleton--block" />
            </div>
          </>
        )}

        {state.status === 'error' && (
          <ErrorNotice message={es.legal.errorMessage} onRetry={retry} />
        )}

        {loaded && (
          <div className="legal__layout">
            {entries.length > 0 && <TableOfContents entries={entries} />}
            <div className="legal__sections">
              {sections.map((section, index) => (
                <section key={index} className="legal-section">
                  {section.id && (
                    <h2 id={section.id} tabIndex={-1} className="legal-section__heading">
                      {section.heading}
                    </h2>
                  )}
                  <Markdown>{section.body}</Markdown>
                </section>
              ))}
            </div>
          </div>
        )}
      </div>
    </article>
  )
}
