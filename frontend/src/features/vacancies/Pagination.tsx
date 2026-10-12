import { Link } from 'react-router'
import { es } from '../../i18n/es'
import { pageItems } from './pageItems'
import './Pagination.css'

interface PaginationProps {
  page: number
  totalPages: number
  /** Query string ("?pagina=2") of the address for a page. */
  searchFor: (page: number) => string
  /** Called when a page link is chosen (the screen moves focus to the results title). */
  onNavigate: () => void
}

/** Page links in a `nav`. The current page is marked with `aria-current="page"`. */
export function Pagination({ page, totalPages, searchFor, onNavigate }: PaginationProps) {
  if (totalPages <= 1) return null

  return (
    <nav className="pagination" aria-label={es.opportunities.pagination.label}>
      <ul className="pagination__list">
        {page > 1 && (
          <li>
            <Link
              className="pagination__item pagination__item--wide"
              to={{ search: searchFor(page - 1) }}
              onClick={onNavigate}
            >
              {es.opportunities.pagination.previous}
            </Link>
          </li>
        )}
        {pageItems(page, totalPages).map((item, index) =>
          item === null ? (
            <li key={`gap-${index}`} className="pagination__gap" aria-hidden="true">
              …
            </li>
          ) : (
            <li key={item}>
              {item === page ? (
                <span
                  className="pagination__item pagination__item--current"
                  aria-current="page"
                  aria-label={es.opportunities.pagination.page(item)}
                >
                  {item}
                </span>
              ) : (
                <Link
                  className="pagination__item"
                  to={{ search: searchFor(item) }}
                  aria-label={es.opportunities.pagination.page(item)}
                  onClick={onNavigate}
                >
                  {item}
                </Link>
              )}
            </li>
          ),
        )}
        {page < totalPages && (
          <li>
            <Link
              className="pagination__item pagination__item--wide"
              to={{ search: searchFor(page + 1) }}
              onClick={onNavigate}
            >
              {es.opportunities.pagination.next}
            </Link>
          </li>
        )}
      </ul>
    </nav>
  )
}
