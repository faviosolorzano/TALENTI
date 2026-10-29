import { FeaturedVacancies } from '../vacancies/FeaturedVacancies'
import { Hero } from './Hero'
import { es } from '../../i18n/es'

export function LandingPage() {
  return (
    <>
      <Hero />
      <FeaturedVacancies />
      <section
        id="como-funciona"
        className="container landing-how"
        tabIndex={-1}
        aria-labelledby="how-title"
      >
        <h2 id="how-title">{es.nav.how}</h2>
        <ol>
          {es.how.steps.map((step) => (
            <li key={step.title}>
              <h3>{step.title}</h3>
              <p>{step.description}</p>
            </li>
          ))}
        </ol>
      </section>
    </>
  )
}
