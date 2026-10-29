import { Button } from '../../components/Button'
import { es } from '../../i18n/es'
import './Hero.css'

export function Hero() {
  return (
    <section className="hero" aria-labelledby="hero-title">
      <div className="container hero__content">
        <h1 id="hero-title" className="hero__title">
          {es.hero.titleStart}
          <span>{es.hero.titleEmphasis}</span>
        </h1>
        <p className="hero__lead">{es.hero.lead}</p>
        <Button className="hero__primary" to="/oportunidades">
          {es.hero.primaryAction}
          <span aria-hidden="true">→</span>
        </Button>
        <figure className="hero__scene">
          <figcaption>{es.hero.example}</figcaption>
          <picture>
            <source
              media="(max-width: 47.5rem)"
              srcSet="/illustrations/hero-movil.webp"
              width="1536"
              height="1024"
            />
            <img
              src="/illustrations/hero-desktop.webp"
              width="1791"
              height="878"
              alt={es.hero.sceneDescription}
              fetchPriority="high"
            />
          </picture>
        </figure>
      </div>
    </section>
  )
}
