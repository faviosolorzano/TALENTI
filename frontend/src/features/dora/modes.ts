/** The four states of DORA and how the orb moves in each one. */

export type DoraMode = 'idle' | 'listening' | 'thinking' | 'speaking'

export const DORA_MODES: readonly DoraMode[] = ['idle', 'listening', 'thinking', 'speaking']

/** Movement parameters of one state. Every field is blended smoothly between states. */
export interface OrbParams {
  /** Deformation of the edge. */
  amp: number
  /** Speed of the noise movement. */
  speed: number
  /** Frequency of the noise around the edge. */
  freq: number
  /** Strength of the glow. */
  glow: number
  /** Opacity of the expanding rings (1 only while listening). */
  rings: number
  /** Opacity of the orbiting dots (1 only while thinking). */
  dots: number
  [key: string]: number
}

export const ORB_PARAMS: Readonly<Record<DoraMode, OrbParams>> = {
  idle: { amp: 0.05, speed: 0.35, freq: 0.9, glow: 0.35, rings: 0, dots: 0 },
  listening: { amp: 0.09, speed: 0.75, freq: 1.1, glow: 0.55, rings: 1, dots: 0 },
  thinking: { amp: 0.08, speed: 0.55, freq: 1.3, glow: 0.5, rings: 0, dots: 1 },
  speaking: { amp: 0.11, speed: 0.85, freq: 1.2, glow: 0.6, rings: 0, dots: 0 },
}

/** Loudness and pause chance of the simulated voice in the states that use it. */
export const SIMULATED_VOICE: Readonly<
  Partial<Record<DoraMode, { scale: number; pauseChance: number }>>
> = {
  listening: { scale: 0.7, pauseChance: 0.28 },
  speaking: { scale: 1, pauseChance: 0.18 },
}

/** Fixed sound level of the still image used with reduced motion. */
export const STILL_LEVEL: Readonly<Record<DoraMode, number>> = {
  idle: 0.06,
  listening: 0.2,
  thinking: 0.16,
  speaking: 0.35,
}
