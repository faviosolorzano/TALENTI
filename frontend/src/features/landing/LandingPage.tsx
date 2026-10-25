import { FeaturedVacancies } from '../vacancies/FeaturedVacancies'
import { DoraSection } from './DoraSection'
import { Hero } from './Hero'
import { HowItWorks } from './HowItWorks'

/**
 * Home page (`/`): hero with the compatibility card, featured vacancies (with the real count of
 * open ones), "Cómo funciona" and DORA. The order comes from the
 * approved sample and from spec 004-15.
 */
export function LandingPage() {
  return (
    <>
      <Hero />
      <FeaturedVacancies />
      <HowItWorks />
      <DoraSection />
    </>
  )
}
