import { describe, expect, it } from 'vitest'
import {
  BLEND_TIME_CONSTANT,
  blendParams,
  blendToward,
  followLevel,
  frameSeconds,
  MAX_FRAME_SECONDS,
} from './dynamics'
import { levelFromTimeDomain, rmsFromTimeDomain, rmsToLevel } from './micLevel'
import { ORB_PARAMS } from './modes'
import { createNoise2D, createRandom } from './noise'
import { buildOrbFrame, createOrbEngine, EDGE_POINTS } from './orbEngine'
import {
  createSimulatedVoice,
  nextVoiceSegment,
  PAUSE_SECONDS,
  SYLLABLE_SECONDS,
} from './simulatedVoice'

describe('Gradient noise (003-20)', () => {
  it('is deterministic with the same seed', () => {
    const a = createNoise2D(42)
    const b = createNoise2D(42)
    for (let i = 0; i < 200; i++) {
      const x = i * 0.37
      const y = i * 0.11 + 3
      expect(a(x, y)).toBe(b(x, y))
    }
  })

  it('gives different noise with a different seed', () => {
    const a = createNoise2D(1)
    const b = createNoise2D(2)
    const differs = Array.from({ length: 50 }, (_, i) => a(i * 0.43 + 0.2, i * 0.29 + 0.1)).some(
      (value, i) => value !== b(i * 0.43 + 0.2, i * 0.29 + 0.1),
    )
    expect(differs).toBe(true)
  })

  it('stays within [-1, 1] (|n| <= 1)', () => {
    const noise = createNoise2D(7)
    let max = 0
    for (let ix = -40; ix < 40; ix++) {
      for (let iy = -40; iy < 40; iy++) {
        max = Math.max(max, Math.abs(noise(ix * 0.173, iy * 0.211)))
      }
    }
    expect(max).toBeLessThanOrEqual(1)
    expect(max).toBeGreaterThan(0.3)
  })

  it('is continuous: a tiny step in x or y moves the value only a tiny bit', () => {
    const noise = createNoise2D(99)
    const step = 1e-3
    for (let i = 0; i < 500; i++) {
      const x = i * 0.0731 - 10
      const y = i * 0.0517 + 2
      expect(Math.abs(noise(x + step, y) - noise(x, y))).toBeLessThan(0.01)
      expect(Math.abs(noise(x, y + step) - noise(x, y))).toBeLessThan(0.01)
    }
  })

  it('is smooth across the borders of the lattice cells', () => {
    const noise = createNoise2D(5)
    expect(Math.abs(noise(2 - 1e-6, 0.5) - noise(2 + 1e-6, 0.5))).toBeLessThan(1e-4)
  })

  it('the seeded random generator repeats and stays in [0, 1)', () => {
    const a = createRandom(3)
    const b = createRandom(3)
    for (let i = 0; i < 100; i++) {
      const value = a()
      expect(value).toBe(b())
      expect(value).toBeGreaterThanOrEqual(0)
      expect(value).toBeLessThan(1)
    }
  })
})

describe('Blend between states (003-20)', () => {
  it('approaches the target with a time constant of 0.2 s', () => {
    // After one time constant, 63.2 % of the distance is covered.
    expect(BLEND_TIME_CONSTANT).toBe(0.2)
    expect(blendToward(0, 1, 0.2)).toBeCloseTo(1 - Math.exp(-1), 6)
  })

  it('reaches the same point in many small steps as in one big step', () => {
    let value = 0
    for (let i = 0; i < 20; i++) value = blendToward(value, 1, 0.01)
    expect(value).toBeCloseTo(blendToward(0, 1, 0.2), 6)
  })

  it('never jumps or overshoots: every step is small and monotonic', () => {
    let value = 0
    let previous = 0
    for (let i = 0; i < 120; i++) {
      value = blendToward(value, 1, 1 / 60)
      expect(value).toBeGreaterThanOrEqual(previous)
      expect(value - previous).toBeLessThan(0.09)
      expect(value).toBeLessThanOrEqual(1)
      previous = value
    }
    expect(value).toBeGreaterThan(0.99)
  })

  it('changes nothing when no time has passed', () => {
    expect(blendToward(0.3, 1, 0)).toBe(0.3)
  })

  it('blends every parameter of a state toward the next one', () => {
    const next = blendParams(ORB_PARAMS.idle, ORB_PARAMS.listening, 0.2)
    expect(next.amp).toBeGreaterThan(ORB_PARAMS.idle.amp)
    expect(next.amp).toBeLessThan(ORB_PARAMS.listening.amp)
    expect(next.rings).toBeCloseTo(1 - Math.exp(-1), 6)
  })
})

describe('Sound level: fast attack, slow release (003-20)', () => {
  it('rises more than 85 % of the jump in 0.1 s', () => {
    expect(followLevel(0, 1, 0.1)).toBeGreaterThan(0.85)
  })

  it('falls less than half of the jump in 0.1 s', () => {
    const drop = 1 - followLevel(1, 0, 0.1)
    expect(drop).toBeLessThan(0.5)
    expect(drop).toBeGreaterThan(0)
  })

  it('limits one frame to 50 ms of simulated time (003-23)', () => {
    expect(MAX_FRAME_SECONDS).toBe(0.05)
    expect(frameSeconds(16)).toBeCloseTo(0.016, 6)
    expect(frameSeconds(10_000)).toBe(0.05)
    expect(frameSeconds(-5)).toBe(0)
  })
})

describe('Simulated voice (003-20)', () => {
  it('makes syllables of 0.09 to 0.26 s', () => {
    expect(nextVoiceSegment(() => 0.99, 1, 0)).toMatchObject({ kind: 'syllable' })
    expect(nextVoiceSegment(() => 0.5, 1, 0).seconds).toBeGreaterThan(SYLLABLE_SECONDS.min)

    const random = createRandom(11)
    for (let i = 0; i < 500; i++) {
      const { seconds } = nextVoiceSegment(random, 1, 0)
      expect(seconds).toBeGreaterThanOrEqual(0.09)
      expect(seconds).toBeLessThanOrEqual(0.26)
    }
  })

  it('makes pauses of 0.3 to 0.8 s', () => {
    const random = createRandom(12)
    for (let i = 0; i < 500; i++) {
      const segment = nextVoiceSegment(random, 1, 1)
      expect(segment.kind).toBe('pause')
      expect(segment.seconds).toBeGreaterThanOrEqual(PAUSE_SECONDS.min)
      expect(segment.seconds).toBeLessThanOrEqual(PAUSE_SECONDS.max)
    }
  })

  it('gives non-uniform durations, not a fixed rhythm', () => {
    const random = createRandom(13)
    const durations = Array.from({ length: 200 }, () => nextVoiceSegment(random, 1, 0.2).seconds)
    expect(new Set(durations.map((d) => d.toFixed(3))).size).toBeGreaterThan(100)
    const mean = durations.reduce((a, b) => a + b, 0) / durations.length
    const variance = durations.reduce((a, b) => a + (b - mean) ** 2, 0) / durations.length
    expect(Math.sqrt(variance)).toBeGreaterThan(0.05)
  })

  it('scales the loudness and keeps the level inside [0, 1]', () => {
    const quiet = nextVoiceSegment(() => 0.9, 0.7, 0)
    const loud = nextVoiceSegment(() => 0.9, 1, 0)
    expect(quiet.level).toBeLessThan(loud.level)
    expect(loud.level).toBeLessThanOrEqual(1)
  })

  it('holds a segment until its time is over, then draws the next one', () => {
    let draws = 0
    const voice = createSimulatedVoice(() => {
      draws += 1
      return 0.5
    })
    voice.sample(0, 1, 0)
    const afterFirst = draws
    voice.sample(0.05, 1, 0)
    expect(draws).toBe(afterFirst)
    voice.sample(1, 1, 0)
    expect(draws).toBeGreaterThan(afterFirst)
  })
})

describe('Microphone level (003-20)', () => {
  it('gives 0 when the RMS is below 0.012', () => {
    expect(rmsToLevel(0)).toBe(0)
    expect(rmsToLevel(0.0119)).toBe(0)
    expect(rmsToLevel(0.012)).toBeGreaterThan(0)
  })

  it('is capped at 1', () => {
    expect(rmsToLevel(0.2)).toBe(1)
    expect(rmsToLevel(5)).toBe(1)
    expect(rmsToLevel(0.1)).toBeCloseTo(0.5, 6)
  })

  it('ignores values that are not numbers', () => {
    expect(rmsToLevel(Number.NaN)).toBe(0)
  })

  it('measures silence and a loud signal from analyser bytes', () => {
    expect(rmsFromTimeDomain(new Uint8Array(512).fill(128))).toBe(0)
    expect(levelFromTimeDomain(new Uint8Array(512).fill(128))).toBe(0)
    const loud = Uint8Array.from({ length: 512 }, (_, i) => (i % 2 === 0 ? 255 : 1))
    expect(levelFromTimeDomain(loud)).toBe(1)
    expect(rmsFromTimeDomain(new Uint8Array(0))).toBe(0)
  })
})

describe('Orb engine', () => {
  it('draws the same frames with the same seed and the same randomness', () => {
    const make = () => createOrbEngine({ seed: 5, random: createRandom(8) })
    const a = make()
    const b = make()
    for (let i = 0; i < 30; i++) {
      expect(a.step(1 / 60, 'speaking')).toEqual(b.step(1 / 60, 'speaking'))
    }
  })

  it('moves the edge with noise: a frame has EDGE_POINTS points that differ over time', () => {
    const engine = createOrbEngine({ seed: 5, random: createRandom(8) })
    const first = engine.step(0.016, 'idle')
    for (let i = 0; i < 60; i++) engine.step(0.016, 'idle')
    const later = engine.step(0.016, 'idle')
    expect(first.edge).toHaveLength(EDGE_POINTS)
    expect(later.edge).not.toEqual(first.edge)
  })

  it('shows rings only while listening and dots only while thinking', () => {
    const engine = createOrbEngine({ seed: 1, random: createRandom(2) })
    let frame = engine.step(0.016, 'idle')
    expect(frame.rings).toHaveLength(0)
    expect(frame.dots).toHaveLength(0)
    for (let i = 0; i < 60; i++) frame = engine.step(0.016, 'listening')
    expect(frame.rings.length).toBe(3)
    for (let i = 0; i < 90; i++) frame = engine.step(0.016, 'thinking')
    expect(frame.dots.length).toBe(3)
    expect(frame.rings).toHaveLength(0)
  })

  it('does not jump when the state changes: the edge changes a little on the next frame', () => {
    const engine = createOrbEngine({ seed: 3, random: createRandom(4) })
    let before = engine.step(0.016, 'idle')
    for (let i = 0; i < 60; i++) before = engine.step(0.016, 'idle')
    const after = engine.step(0.016, 'speaking')
    const biggest = Math.max(
      ...before.edge.map((value, i) => Math.abs((after.edge[i] ?? 0) - value)),
    )
    expect(biggest).toBeLessThan(0.05)
  })

  it('follows a real sound level when one is given while listening', () => {
    const engine = createOrbEngine({ seed: 3, random: createRandom(4) })
    let frame = engine.step(0.016, 'listening', 0)
    for (let i = 0; i < 30; i++) frame = engine.step(0.016, 'listening', 0.9)
    expect(frame.level).toBeGreaterThan(0.8)
  })

  it('the still image has no rings or dots and a fixed level for each state', () => {
    const engine = createOrbEngine({ seed: 3, random: createRandom(4) })
    for (const mode of ['idle', 'listening', 'thinking', 'speaking'] as const) {
      const frame = engine.still(mode)
      expect(frame.rings).toHaveLength(0)
      expect(frame.dots).toHaveLength(0)
      expect(engine.still(mode)).toEqual(frame)
    }
  })

  it('builds a frame from pure inputs', () => {
    const noise = createNoise2D(1)
    const input = { noise, t: 2, params: { ...ORB_PARAMS.idle }, level: 0.1 }
    expect(buildOrbFrame(input)).toEqual(buildOrbFrame(input))
  })
})
