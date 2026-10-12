import { blendParams, followLevel } from './dynamics'
import { createSimulatedVoice, type SimulatedVoice } from './simulatedVoice'
import { createNoise2D, type Noise2D } from './noise'
import { ORB_PARAMS, SIMULATED_VOICE, STILL_LEVEL, type DoraMode, type OrbParams } from './modes'

/** Number of points around the edge of the orb. */
export const EDGE_POINTS = 96
/** Moment (seconds) used to draw the still image shown with reduced motion. */
export const STILL_TIME = 3.2

/**
 * Everything the canvas needs to draw one frame, in unit space: the orb has radius 1 and its
 * center is (0, 0). The drawing code only scales it; it has no logic of its own.
 */
export interface OrbFrame {
  /** Radius factor at each point around the edge (1 is the base radius). */
  edge: readonly number[]
  /** Current sound level, from 0 to 1. */
  level: number
  /** Strength of the glow, from the state parameters. */
  glow: number
  /** Expanding rings (only while listening). Radius in orb units and opacity. */
  rings: ReadonlyArray<{ radius: number; alpha: number }>
  /** Orbiting dots (only while thinking), position in orb units. */
  dots: ReadonlyArray<{ x: number; y: number; size: number; alpha: number }>
  /** Position of the inner light, in orb units. */
  highlight: { x: number; y: number }
}

interface FrameInput {
  noise: Noise2D
  t: number
  params: OrbParams
  level: number
}

/** Builds the frame for a moment in time. Pure: same input, same frame. */
export function buildOrbFrame({ noise, t, params, level }: FrameInput): OrbFrame {
  const breathe = 1 + 0.016 * noise(t * 0.45, 1.5) + level * 0.05
  const amp = params.amp + level * 0.26

  const edge: number[] = []
  for (let i = 0; i < EDGE_POINTS; i++) {
    const angle = (i / EDGE_POINTS) * Math.PI * 2
    const nx = Math.cos(angle) * params.freq
    const ny = Math.sin(angle) * params.freq
    const n =
      noise(nx + t * params.speed * 0.6, ny + t * params.speed * 0.45) * 0.7 +
      noise(nx * 2.1 - t * params.speed * 0.9, ny * 2.1 + 7.3) * 0.3
    edge.push(breathe * (1 + amp * n * 1.9))
  }

  const rings: Array<{ radius: number; alpha: number }> = []
  if (params.rings > 0.02) {
    for (let r = 0; r < 3; r++) {
      const phase = (t * 0.38 + r / 3) % 1
      rings.push({
        radius: 1.12 + phase * 0.6 + level * 0.1,
        alpha: (1 - phase) * 0.26 * params.rings * (0.55 + level),
      })
    }
  }

  const dots: Array<{ x: number; y: number; size: number; alpha: number }> = []
  if (params.dots > 0.02) {
    for (let d = 0; d < 3; d++) {
      const angle = t * 2.1 + d * ((Math.PI * 2) / 3) + noise(t * 0.6, d * 5) * 0.4
      dots.push({
        x: Math.cos(angle) * 1.45,
        y: Math.sin(angle) * 1.45,
        size: (9 - d * 1.5) / 220,
        alpha: (0.95 - d * 0.22) * params.dots,
      })
    }
  }

  return {
    edge,
    level,
    glow: params.glow,
    rings,
    dots,
    highlight: {
      x: -(0.35 - noise(t * 0.3, 9) * 0.12),
      y: -(0.45 - noise(t * 0.27, 4) * 0.1),
    },
  }
}

export interface OrbEngineOptions {
  /** Seed of the noise. Random when omitted; fixed in tests. */
  seed?: number
  /** Source of randomness for the simulated voice. */
  random?: () => number
}

export interface OrbEngine {
  /**
   * Advances `dt` seconds toward `mode` and returns the frame. `externalLevel` (0 to 1) is the
   * real sound level when there is one; it is used while listening or speaking.
   */
  step(dt: number, mode: DoraMode, externalLevel?: number | null): OrbFrame
  /** A motionless frame for the state: no rings, no dots, fixed level. */
  still(mode: DoraMode): OrbFrame
}

export function createOrbEngine({ seed, random = Math.random }: OrbEngineOptions = {}): OrbEngine {
  const noise = createNoise2D(seed ?? Math.floor(random() * 2 ** 31))
  const voice: SimulatedVoice = createSimulatedVoice(random)
  let params: OrbParams = { ...ORB_PARAMS.idle }
  let level = 0
  let t = 0

  function targetLevel(mode: DoraMode, externalLevel: number | null | undefined): number {
    const simulated = SIMULATED_VOICE[mode]
    if (simulated) {
      if (externalLevel != null) return Math.min(1, Math.max(0, externalLevel))
      return voice.sample(t, simulated.scale, simulated.pauseChance)
    }
    if (mode === 'thinking') return 0.16 + 0.07 * noise(t * 0.8, 3)
    return 0.06 + 0.04 * noise(t * 0.5, 5)
  }

  return {
    step(dt, mode, externalLevel) {
      t += dt
      params = blendParams(params, ORB_PARAMS[mode], dt)
      level = followLevel(level, targetLevel(mode, externalLevel), dt)
      return buildOrbFrame({ noise, t, params, level })
    },
    still(mode) {
      params = { ...ORB_PARAMS[mode], rings: 0, dots: 0 }
      level = STILL_LEVEL[mode]
      return buildOrbFrame({ noise, t: STILL_TIME, params, level })
    },
  }
}
