import { Card } from '../../components/Card'
import { es } from '../../i18n/es'
import '../vacancies/Skeletons.css'
import './StudentHome.css'

/**
 * Neutral placeholder shown while the session is being checked (005-02): it does not look like
 * the public landing page or like the student Home, so nothing flashes and then changes.
 */
export function HomeLoading() {
  return (
    <div className="container home" aria-busy="true">
      <h1 className="visually-hidden">{es.pages.home.title}</h1>
      <p className="visually-hidden" role="status">
        {es.home.loading}
      </p>
      <div className="home__head" aria-hidden="true">
        <span className="skeleton skeleton--heading home-loading__title" />
      </div>
      <div className="home__grid" aria-hidden="true">
        <Card className="home-loading__card home-block--wide">
          <span className="skeleton skeleton--title" />
          <span className="skeleton skeleton--line" />
        </Card>
        <Card className="home-loading__card">
          <span className="skeleton skeleton--title" />
          <span className="skeleton skeleton--line" />
          <span className="skeleton skeleton--line skeleton--short" />
        </Card>
        <Card className="home-loading__card">
          <span className="skeleton skeleton--title" />
          <span className="skeleton skeleton--line" />
        </Card>
      </div>
    </div>
  )
}
