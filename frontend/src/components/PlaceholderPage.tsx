import type { ReactNode } from 'react'
import { es } from '../i18n/es'
import './PlaceholderPage.css'

interface PlaceholderPageProps {
  title: string
  description?: string
  /** Extra content under the text (for example a link). */
  children?: ReactNode
}

/** Provisional page: title and "under construction" notice. Replaced by the real screens. */
export function PlaceholderPage({
  title,
  description = es.placeholder.description,
  children,
}: PlaceholderPageProps) {
  return (
    <section className="placeholder container">
      <h1 className="placeholder__title">{title}</h1>
      <p className="placeholder__status">{es.placeholder.underConstruction}</p>
      <p className="placeholder__text">{description}</p>
      {children}
    </section>
  )
}
