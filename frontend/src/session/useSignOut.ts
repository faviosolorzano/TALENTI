import { useCallback, useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { sessionPaths } from './sessionPaths'
import { useSession } from './useSession'

/**
 * "Cerrar sesión": signs out on the server and then goes to the sign-in page with the notice
 * "Cerraste sesión". The header, the panel and the acceptance screen all use it, so the
 * destination and the message are the same everywhere.
 *
 * If the server could not be reached the person stays where they are and `failed` is true. It
 * clears on the next attempt and when the person goes to another page.
 */
export function useSignOut() {
  const session = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const [pending, setPending] = useState(false)
  // The page (history entry) where the last attempt failed; `failed` only holds while still there.
  const [failedAt, setFailedAt] = useState<string | null>(null)

  const signOut = useCallback(async () => {
    setPending(true)
    setFailedAt(null)
    try {
      const done = await session.signOut()
      if (done) navigate(sessionPaths.login, { replace: true, state: { notice: 'signedOut' } })
      else setFailedAt(location.key)
    } finally {
      setPending(false)
    }
  }, [session, navigate, location.key])

  return { signOut, pending, failed: failedAt !== null && failedAt === location.key }
}
