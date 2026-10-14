import type { CompatibilitySummary, SummariesAnswer } from '../../api/compatibility'
import { fetchSummaries, MAX_SUMMARY_IDS } from '../../api/compatibility'
import { useApiResource } from '../../hooks/useApiResource'
import { useSession } from '../../session/useSession'
import { useSessionLost } from './useSessionLost'

export interface CardCompatibility {
  /** `null` until the server has answered (or when there is nothing to ask). */
  sufficient: boolean | null
  byId: ReadonlyMap<string, CompatibilitySummary>
}

const NOTHING: CardCompatibility = { sufficient: null, byId: new Map() }

/**
 * The compatibility of the cards of ONE page of results: a single `summaries` call for all of
 * them, only for a student with a session. If the call fails the cards simply have no badge
 * (spec 007-23): no error is shown. Nothing is stored.
 */
export function useCardCompatibility(ids: readonly string[]): CardCompatibility {
  const session = useSession()
  const sessionLost = useSessionLost()
  const asked = ids.slice(0, MAX_SUMMARY_IDS)
  const enabled = session.isStudent && asked.length > 0
  const key = enabled ? `summaries:${asked.join(',')}` : 'summaries:off'

  const resource = useApiResource<SummariesAnswer | null>(
    key,
    async (signal) => {
      if (!enabled) return null
      try {
        return await fetchSummaries(asked, signal)
      } catch (error) {
        sessionLost(error, false)
        throw error
      }
    },
    { keepPrevious: true },
  )

  if (!enabled || resource.state.status !== 'success' || !resource.state.data) return NOTHING
  const { sufficient, items } = resource.state.data
  return { sufficient, byId: new Map(items.map((item) => [item.vacancyId, item])) }
}
