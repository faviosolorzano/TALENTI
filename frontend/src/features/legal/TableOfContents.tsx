import { useId, useState } from 'react'
import { Link } from 'react-router'
import { es } from '../../i18n/es'
import './TableOfContents.css'

export interface TocEntry {
  id: string
  heading: string
}

/**
 * Index of the sections. On wide screens it is a fixed side column; on narrow ones it folds
 * into a "Contenido" button above the text. Activating a link goes to the section (the page
 * moves focus to its heading).
 */
export function TableOfContents({ entries }: { entries: TocEntry[] }) {
  const [open, setOpen] = useState(false)
  const listId = useId()

  return (
    <nav className="toc" aria-label={es.legal.toc}>
      <button
        type="button"
        className="toc__toggle"
        aria-expanded={open}
        aria-controls={listId}
        onClick={() => setOpen((value) => !value)}
      >
        {es.legal.toc}
        <span className="toc__chevron" aria-hidden="true" />
      </button>
      <p className="toc__title" aria-hidden="true">
        {es.legal.toc}
      </p>
      <ol id={listId} className="toc__list" hidden={!open}>
        {entries.map((entry) => (
          <li key={entry.id}>
            <Link to={{ hash: `#${entry.id}` }} onClick={() => setOpen(false)}>
              {entry.heading}
            </Link>
          </li>
        ))}
      </ol>
    </nav>
  )
}
