import { useCallback, useEffect, useState } from 'react'
import { ApiError } from '../../api/client'
import { fetchCurrentAnalysis, type CvAnalysis } from '../../api/cv'
import { useApiResource } from '../../hooks/useApiResource'
import { useSession } from '../../session/useSession'

const FIRST_DELAY_MS = 2000
const STEP_MS = 500
const MAX_DELAY_MS = 6000

/**
 * The open CV analysis of the person (`GET /cv/analyses/current`, `null` = none). While it is
 * `PENDING` or `ANALYZING` it asks again softly: every 2 s at first, slowing down to 6 s, and
 * never while the tab is hidden (it asks once when the person comes back). Nothing is stored.
 */
export function useCurrentAnalysis() {
  const { refresh: refreshSession } = useSession()
  const [version, setVersion] = useState(0)
  const resource = useApiResource(
    `cv-current-${version}`,
    async (signal) => {
      try {
        return await fetchCurrentAnalysis(signal)
      } catch (error) {
        // An expired session is told by the server; the guard then goes to /ingresar.
        if (error instanceof ApiError && error.status === 401) void refreshSession()
        throw error
      }
    },
    { keepPrevious: true },
  )
  const analysis: CvAnalysis | null | undefined =
    resource.state.status === 'success' ? resource.state.data : undefined
  const waiting = analysis?.status === 'PENDING' || analysis?.status === 'ANALYZING'
  const refreshing = resource.refreshing

  const refresh = useCallback(() => setVersion((value) => value + 1), [])

  useEffect(() => {
    if (!waiting || refreshing) return
    const delay = Math.min(FIRST_DELAY_MS + version * STEP_MS, MAX_DELAY_MS)
    let timer: ReturnType<typeof setTimeout> | undefined
    const onVisible = () => {
      if (document.visibilityState === 'visible') refresh()
    }
    if (document.visibilityState === 'visible') {
      timer = setTimeout(onVisible, delay)
    }
    // Hidden tab: no timer; the person coming back asks once.
    document.addEventListener('visibilitychange', onVisible)
    return () => {
      clearTimeout(timer)
      document.removeEventListener('visibilitychange', onVisible)
    }
  }, [waiting, refreshing, version, refresh])

  return { state: resource.state, analysis, retry: resource.retry, refresh }
}
