import { Link } from 'react-router'
import { PlaceholderPage } from '../components/PlaceholderPage'
import { es } from '../i18n/es'

export function NotFoundPage() {
  return (
    <PlaceholderPage title={es.pages.notFound.title} description={es.pages.notFound.description}>
      <p>
        <Link to="/">{es.pages.notFound.backHome}</Link>
      </p>
    </PlaceholderPage>
  )
}
