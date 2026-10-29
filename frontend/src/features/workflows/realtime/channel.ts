import type { DoraAvailability } from '../../../api/realtime'
import type { DoraSession } from '../../../api/workflows'

/** Why the session goes by turns, in words for the person (012-58). `null`: nothing to say. */
export type TurnReason =
  'simulated' | 'disabled' | 'not-configured' | 'spend-limit' | 'unsupported' | 'mic-denied'

export type ChannelChoice =
  { channel: 'REALTIME' } | { channel: 'TURN_BASED'; reason: TurnReason | null }

const ENDED = new Set(['COMPLETED', 'ABANDONED', 'FAILED'])

/**
 * Real time only when the backend offers it, the browser can do it and the session can still
 * use it. A session already going by turns (it started that way or fell back, 012-53) stays so.
 */
export function chooseChannel(
  dora: DoraAvailability | undefined,
  session: Pick<DoraSession, 'status' | 'voice' | 'channel'>,
  supported: boolean,
): ChannelChoice {
  if (ENDED.has(session.status) || !dora) return { channel: 'TURN_BASED', reason: null }
  if (dora.provider === 'SIMULATED') return { channel: 'TURN_BASED', reason: 'simulated' }
  if (!dora.realtime.available) {
    const reason = dora.realtime.reason
    return {
      channel: 'TURN_BASED',
      reason:
        reason === 'SPEND_LIMIT'
          ? 'spend-limit'
          : reason === 'NOT_CONFIGURED'
            ? 'not-configured'
            : 'disabled',
    }
  }
  if (!session.voice) return { channel: 'TURN_BASED', reason: null }
  if (session.status === 'IN_PROGRESS' && session.channel !== 'REALTIME')
    return { channel: 'TURN_BASED', reason: null }
  if (!supported) return { channel: 'TURN_BASED', reason: 'unsupported' }
  return { channel: 'REALTIME' }
}
