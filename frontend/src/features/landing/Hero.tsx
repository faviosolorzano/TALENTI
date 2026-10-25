import { Link } from 'react-router'
import { Button } from '../../components/Button'
import { es } from '../../i18n/es'
import { CompatibilityCard } from './CompatibilityCard'
import './Hero.css'

/** Main block of the landing page: the headline, one main action and the live card. */
export function Hero() {
  return (
    <section className="hero" aria-labelledby="hero-title">
      <div className="container hero__grid">
        <div>
          <h1 id="hero-title" className="hero__title">
            {es.hero.titleStart}
            <em>{es.hero.titleEmphasis}</em>
          </h1>
          <p className="hero__lead">{es.hero.lead}</p>
          <div className="hero__actions">
            <Button to={{ pathname: '/', hash: 'compatibilidad-card' }}>
              {es.hero.primaryAction}
            </Button>
            <Link className="hero__link" to={{ pathname: '/', hash: 'como-funciona' }}>
              {es.hero.secondaryAction}
              <span aria-hidden="true">↓</span>
            </Link>
          </div>
        </div>

        <CompatibilityCard />
      </div>
    </section>
  )
}
