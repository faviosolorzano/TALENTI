import type { ReactNode } from 'react'
import { BrandMark } from '../../components/BrandMark'
import { Card } from '../../components/Card'
import { es } from '../../i18n/es'
import './AuthLayout.css'

interface AuthLayoutProps {
  title: string
  lead?: string
  /** Show the side message on wide screens (sign-in and sign-up). */
  aside?: boolean
  children: ReactNode
}

/**
 * Frame of the sign-in, sign-up and recovery pages: one column on phones and tablets; on wide
 * screens the form sits next to a navy panel that says who decides what. One h1 per page.
 */
export function AuthLayout({ title, lead, aside = true, children }: AuthLayoutProps) {
  return (
    <div className="container auth">
      <div className="auth__grid" data-with-aside={aside || undefined}>
        <Card as="section" className="auth__card" aria-labelledby="auth-title">
          <h1 id="auth-title" className="auth__title">
            {title}
          </h1>
          {lead && <p className="auth__lead">{lead}</p>}
          {children}
        </Card>

        {aside && (
          <aside className="auth__aside" aria-label={es.identity.aside.title}>
            <BrandMark />
            <p className="auth__aside-title">{es.identity.aside.title}</p>
            <p>{es.identity.aside.body}</p>
            <p className="auth__aside-note">{es.identity.aside.privacy}</p>
          </aside>
        )}
      </div>
    </div>
  )
}
