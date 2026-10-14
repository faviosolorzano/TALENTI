import { useCallback } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { ApiError } from '../../api/client'
import { loginPathWithReturn } from '../../session/returnTo'
import { useSession } from '../../session/useSession'

/**
 * What to do when a compatibility call answers 401 (spec 007-25): the session is checked again
 * and, if `redirect` is set, the person goes to /ingresar with the "Tu sesión expiró" notice.
 * Any other error is ignored here (each piece shows its own error).
 */
export function useSessionLost() {
  const { refresh } = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const here = `${location.pathname}${location.search}`

  return useCallback(
    (error: unknown, redirect: boolean) => {
      if (!(error instanceof ApiError) || error.status !== 401) return
      void refresh().then(() => {
        if (redirect)
          navigate(loginPathWithReturn(here), { replace: true, state: { notice: 'expired' } })
      })
    },
    [refresh, navigate, here],
  )
}
