import { useEffect } from 'react'
import { es } from '../i18n/es'
import './PlaceholderPage.css'

interface PlaceholderPageProps {
  title: string
  description?: string
}

/** Provisional page: title and "under construction" notice. Replaced by the real screens. */
export function PlaceholderPage({
  title,
  description = es.placeholder.description,
}: PlaceholderPageProps) {
  useEffect(() => {
    document.title = `${title} · ${es.app.name}`
  }, [title])

  return (
    <section className="placeholder">
      <h1 className="placeholder__title">{title}</h1>
      <p className="placeholder__status">{es.placeholder.underConstruction}</p>
      <p className="placeholder__text">{description}</p>
    </section>
  )
}
