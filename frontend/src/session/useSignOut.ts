import { useCallback, useState } from 'react'
import { useNavigate } from 'react-router'
import { sessionPaths } from './sessionPaths'
import { useSession } from './useSession'

/**
 * "Cerrar sesión": signs out on the server and then goes to the sign-in page with the notice
 * "Cerraste sesión". If the server could not be reached the person stays where they are
 * (`session.signOutFailed` tells the screen to say so).
 */
export function useSignOut() {
  const session = useSession()
  const navigate = useNavigate()
  const [pending, setPending] = useState(false)

  const signOut = useCallback(async () => {
    setPending(true)
    try {
      const done = await session.signOut()
      if (done) navigate(sessionPaths.login, { replace: true, state: { notice: 'signedOut' } })
    } finally {
      setPending(false)
    }
  }, [session, navigate])

  return { signOut, pending }
}
