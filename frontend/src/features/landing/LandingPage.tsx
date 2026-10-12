import { DoraSection } from './DoraSection'
import { Hero } from './Hero'
import { HowItWorks } from './HowItWorks'

/**
 * Informative home page (`/`): hero with the compatibility card, "Cómo funciona" and DORA.
 * The order is fixed by the approved sample.
 */
export function LandingPage() {
  return (
    <>
      <Hero />
      {/* S2: aquí van las vacantes destacadas de empresas (después de la portada principal y antes de "Cómo funciona"). */}
      <HowItWorks />
      <DoraSection />
    </>
  )
}
