/**
 * Smoothing of the orb: how parameters blend between states and how the sound level follows
 * the voice. Pure functions; time always comes in as `dt` (seconds).
 */

/** Time constant of the blend between states, in seconds (about 200 ms to settle). */
export const BLEND_TIME_CONSTANT = 0.2

/** Rates per second of the sound level: it rises fast and falls slowly, like a body with inertia. */
export const LEVEL_ATTACK_RATE = 22
export const LEVEL_RELEASE_RATE = 6

/** Longest simulated time of one frame, in seconds (a hidden tab must not cause a jump). */
export const MAX_FRAME_SECONDS = 0.05

/** Moves `current` toward `target` with an exponential approach. `dt <= 0` changes nothing. */
export function blendToward(
  current: number,
  target: number,
  dt: number,
  timeConstant: number = BLEND_TIME_CONSTANT,
): number {
  if (dt <= 0) return current
  return current + (target - current) * (1 - Math.exp(-dt / timeConstant))
}

/** Blends every numeric parameter of `current` toward `goal`. Returns a new object. */
export function blendParams<T extends Record<string, number>>(current: T, goal: T, dt: number): T {
  const next: Record<string, number> = {}
  for (const key of Object.keys(goal)) {
    next[key] = blendToward(current[key] ?? 0, goal[key] ?? 0, dt)
  }
  return next as T
}

/** Follows the target sound level: fast attack when it rises, slow release when it falls. */
export function followLevel(level: number, target: number, dt: number): number {
  if (dt <= 0) return level
  const rate = target > level ? LEVEL_ATTACK_RATE : LEVEL_RELEASE_RATE
  return level + (target - level) * (1 - Math.exp(-dt * rate))
}

/** Turns the elapsed time between two frames (ms) into simulated seconds, at most 50 ms. */
export function frameSeconds(elapsedMs: number): number {
  return Math.min(MAX_FRAME_SECONDS, Math.max(0, elapsedMs / 1000))
}
