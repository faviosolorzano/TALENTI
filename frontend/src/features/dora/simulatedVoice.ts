/**
 * Simulated voice: syllables of irregular length separated by pauses, not a periodic wave.
 * It drives the orb in the "listening" and "speaking" demo states when there is no real audio.
 */

/** Duration range of a syllable, in seconds. */
export const SYLLABLE_SECONDS = { min: 0.09, max: 0.26 } as const
/** Duration range of a pause, in seconds. */
export const PAUSE_SECONDS = { min: 0.3, max: 0.8 } as const
/** Level during a pause. */
export const PAUSE_LEVEL = 0.05

export interface VoiceSegment {
  kind: 'syllable' | 'pause'
  /** Sound level from 0 to 1 during the segment. */
  level: number
  /** Length of the segment in seconds. */
  seconds: number
}

/**
 * Draws the next segment. `scale` is the loudness of the voice (0 to 1) and `pauseChance`
 * the probability that the next segment is a pause. `random` returns numbers in [0, 1).
 */
export function nextVoiceSegment(
  random: () => number,
  scale: number,
  pauseChance: number,
): VoiceSegment {
  if (random() < pauseChance) {
    return {
      kind: 'pause',
      level: PAUSE_LEVEL,
      seconds: PAUSE_SECONDS.min + random() * (PAUSE_SECONDS.max - PAUSE_SECONDS.min),
    }
  }
  return {
    kind: 'syllable',
    level: (0.25 + random() * 0.75) * scale,
    seconds: SYLLABLE_SECONDS.min + random() * (SYLLABLE_SECONDS.max - SYLLABLE_SECONDS.min),
  }
}

export interface SimulatedVoice {
  /** Level at time `t` (seconds). Draws a new segment when the current one is over. */
  sample(t: number, scale: number, pauseChance: number): number
}

export function createSimulatedVoice(random: () => number = Math.random): SimulatedVoice {
  let level = 0
  let until = 0
  return {
    sample(t, scale, pauseChance) {
      if (t >= until) {
        const segment = nextVoiceSegment(random, scale, pauseChance)
        level = segment.level
        until = t + segment.seconds
      }
      return level
    },
  }
}
