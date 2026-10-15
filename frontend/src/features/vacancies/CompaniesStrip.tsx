import { Link } from 'react-router'
import { fetchFeaturedCompanies } from '../../api/vacancies'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { CompanyAvatar } from './CompanyAvatar'
import { ErrorNotice } from './ErrorNotice'
import { CompanySkeletons } from './Skeletons'
import './CompaniesStrip.css'

/**
 * "Empresas que buscan talento": featured companies with at least one open vacancy. Each one
 * leads to the list searched by its name. No image is loaded: the avatar is made of initials.
 */
export function CompaniesStrip() {
  const companies = useApiResource('featured-companies', (signal) =>
    fetchFeaturedCompanies(undefined, signal),
  )

  if (companies.state.status === 'success' && companies.state.data.length === 0) return null

  return (
    <section className="companies" aria-labelledby="companies-title">
      <div className="container">
        <div className="companies__header">
          <h2 id="companies-title" className="companies__title">
            {es.landingVacancies.companiesTitle}
          </h2>
        </div>

        {companies.state.status === 'loading' && <CompanySkeletons count={4} />}
        {companies.state.status === 'error' && (
          <ErrorNotice message={es.landingVacancies.companiesError} onRetry={companies.retry} />
        )}
        {companies.state.status === 'success' && (
          <ul className="companies__list" aria-label={es.landingVacancies.companiesList}>
            {companies.state.data.map((company) => (
              <li key={company.id}>
                <Link
                  className="companies__item"
                  to={{
                    pathname: '/oportunidades',
                    search: `?${new URLSearchParams({ q: company.name }).toString()}`,
                  }}
                >
                  <CompanyAvatar name={company.name} size="sm" />
                  <span className="companies__text">
                    <strong className="companies__name">{company.name}</strong>
                    <span className="companies__meta">{company.city}</span>
                    <span className="companies__meta companies__meta--open">
                      {es.vacancies.companyVacancies(company.openVacancies)}
                    </span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  )
}
