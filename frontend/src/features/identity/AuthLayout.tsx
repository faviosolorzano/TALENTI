import type { ReactNode } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router'
import { safeReturnTo } from '../../session/returnTo'
import { es } from '../../i18n/es'
import './AuthLayout.css'
interface AuthLayoutProps {
  title: string
  lead?: string
  aside?: boolean
  children: ReactNode
}
export function AuthLayout({ title, lead, children }: AuthLayoutProps) {
  const location = useLocation()
  const [params] = useSearchParams()
  const back = safeReturnTo(params.get('returnTo'))
  return (
    <div className="container auth">
      <section className="auth__form" aria-labelledby="auth-title">
        {back && (
          <Link className="auth__back" to={back} state={location.state}>
            {es.redesign.backVacancy}
          </Link>
        )}
        <h1 id="auth-title" className="auth__title">
          {title}
        </h1>
        {lead && <p className="auth__lead">{lead}</p>}
        {children}
      </section>
    </div>
  )
}
