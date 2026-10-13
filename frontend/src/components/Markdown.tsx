import type { ReactNode } from 'react'
import ReactMarkdown, { type Components } from 'react-markdown'
import { Link } from 'react-router'
import remarkGfm from 'remark-gfm'
import { es } from '../i18n/es'
import './Markdown.css'

/** An address inside the app: one "/" at the start, never "//" (which would leave the site). */
function isInternal(href: string): boolean {
  return (href.startsWith('/') && !href.startsWith('//')) || href.startsWith('#')
}

function MarkdownLink({ href, children }: { href?: string; children?: ReactNode }) {
  // react-markdown already empties dangerous addresses (javascript:, data:, vbscript:).
  if (!href) return <span>{children}</span>
  if (isInternal(href)) return <Link to={href}>{children}</Link>
  if (/^https?:/i.test(href)) {
    return (
      <a href={href} target="_blank" rel="noopener noreferrer">
        {children} <span className="visually-hidden">{es.legal.opensInNewTab}</span>
      </a>
    )
  }
  return <a href={href}>{children}</a>
}

// The section heading is an h2 and the page title the only h1, so headings inside a body start
// at h3.
const components: Components = {
  a: ({ href, children }) => <MarkdownLink href={href}>{children}</MarkdownLink>,
  h1: ({ children }) => <h3>{children}</h3>,
  h2: ({ children }) => <h3>{children}</h3>,
  h3: ({ children }) => <h3>{children}</h3>,
  h4: ({ children }) => <h4>{children}</h4>,
  h5: ({ children }) => <h4>{children}</h4>,
  h6: ({ children }) => <h4>{children}</h4>,
  // A wide table scrolls inside its own box, which the keyboard can reach (002-07).
  table: ({ children }) => (
    <div className="markdown__scroll" tabIndex={0} role="group" aria-label={es.legal.tableLabel}>
      <table>{children}</table>
    </div>
  ),
  // The content security policy only allows images from the site itself: show the alternative text.
  img: ({ alt }) => <span>{alt}</span>,
}

/**
 * Renders Markdown safely: lists, tables, bold, links. Raw HTML and comments are dropped
 * (`skipHtml`), nothing is injected as HTML, and there are no plugins that enable it. Internal
 * links navigate without reloading the page.
 */
export function Markdown({ children }: { children: string }) {
  return (
    <div className="markdown">
      <ReactMarkdown remarkPlugins={[remarkGfm]} skipHtml components={components}>
        {children}
      </ReactMarkdown>
    </div>
  )
}
