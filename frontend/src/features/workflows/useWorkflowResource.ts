import { ApiError } from '../../api/client'
import { useApiResource } from '../../hooks/useApiResource'
import { useSession } from '../../session/useSession'

/** A read that notices an expired session and keeps the requested destination. */
export function useWorkflowResource<T>(
  key: string,
  load: (signal: AbortSignal) => Promise<T>,
  options: { keepPrevious?: boolean } = {},
) {
  const { refresh } = useSession()
  return useApiResource(
    key,
    async (signal) => {
      try {
        return await load(signal)
      } catch (error) {
        if (!signal.aborted && error instanceof ApiError && error.status === 401) void refresh()
        throw error
      }
    },
    options,
  )
}
