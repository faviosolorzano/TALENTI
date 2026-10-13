import { Button } from '../../components/Button'
import { es } from '../../i18n/es'
import { sessionPaths } from '../../session/sessionPaths'
import { AuthLayout } from './AuthLayout'

/**
 * Public page /recuperar-cuenta (001-23): only informs. It does NOT recover anything and there
 * is no endpoint behind it; it will work when e-mail sending exists.
 */
export function RecoverScreen() {
  return (
    <AuthLayout title={es.identity.recover.title} lead={es.identity.recover.body} aside={false}>
      <div>
        <Button variant="quiet" to={sessionPaths.login}>
          {es.identity.recover.back}
        </Button>
      </div>
    </AuthLayout>
  )
}
