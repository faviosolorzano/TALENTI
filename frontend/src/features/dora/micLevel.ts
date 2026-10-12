/**
 * Sound level of the microphone. The orb reacts only to volume: it does not interpret what
 * is said, the accent or any emotion (constitution, principle 3).
 */

/** RMS below this is room noise and counts as silence. */
export const MIC_NOISE_FLOOR = 0.012
/** The RMS of speech is small; this gain brings it close to the 0 to 1 range. */
export const MIC_GAIN = 5

/** Converts an RMS value (0 to 1) into the orb level: 0 under the noise floor, at most 1. */
export function rmsToLevel(rms: number): number {
  if (!(rms >= MIC_NOISE_FLOOR)) return 0
  return Math.min(1, rms * MIC_GAIN)
}

/** RMS of time-domain bytes as given by `AnalyserNode.getByteTimeDomainData` (128 is silence). */
export function rmsFromTimeDomain(bytes: ArrayLike<number>): number {
  if (bytes.length === 0) return 0
  let sum = 0
  for (let i = 0; i < bytes.length; i++) {
    const v = ((bytes[i] ?? 128) - 128) / 128
    sum += v * v
  }
  return Math.sqrt(sum / bytes.length)
}

/** Orb level from analyser bytes. */
export function levelFromTimeDomain(bytes: ArrayLike<number>): number {
  return rmsToLevel(rmsFromTimeDomain(bytes))
}
