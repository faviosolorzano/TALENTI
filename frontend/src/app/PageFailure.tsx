import { Button } from '../components/Button'
import { es } from '../i18n/es'
export function PageFailure() {
  return (
    <div className="container workflow">
      <h1>{es.errors.generic}</h1>
      <p>{es.errors.network}</p>
      <div className="workflow__actions">
        <Button onClick={() => window.location.reload()}>{es.common.retry}</Button>
        <Button variant="quiet" to="/oportunidades">
          {es.nav.opportunities}
        </Button>
      </div>
    </div>
  )
}
