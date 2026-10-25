import { Button } from '../components/Button'
import { es } from '../i18n/es'
import './NotFoundPage.css'

/** An address that does not exist: the title says so and one button goes back (011-25). */
export function NotFoundPage() {
  return (
    <section className="container not-found" aria-labelledby="not-found-title">
      <h1 id="not-found-title" className="not-found__title">
        {es.pages.notFound.title}
      </h1>
      <Button to="/">{es.pages.notFound.backHome}</Button>
    </section>
  )
}
