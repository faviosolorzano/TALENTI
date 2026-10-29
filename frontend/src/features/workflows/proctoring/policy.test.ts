import { describe, expect, it } from 'vitest'
import {
  calibrate,
  createPolicyState,
  step,
  type EpisodeEvent,
  type FaceObservation,
  type Pose,
  type ProctoringFrame,
} from './policy'
import { DEFAULT_THRESHOLDS as config, parseSnapshotInterval, parseThresholds } from './thresholds'

const FACE_MS = 200 // 5 cuadros por segundo
const OBJECT_MS = 500 // 2 cuadros por segundo

/** Reproduce cuadros de rostro a 5 fps durante `ms`, con la pose que diga `pose(t)` (t desde 0). */
function run(
  ms: number,
  frame: (t: number) => ProctoringFrame,
  base: Pose = { yawDeg: 0, pitchDeg: 0 },
  every = FACE_MS,
) {
  let state = createPolicyState(base)
  const events: (EpisodeEvent & { at: number })[] = []
  for (let t = 0; t <= ms; t += every) {
    const result = step(state, frame(t), config, 1000 + t)
    state = result.state
    events.push(...result.events.map((e) => ({ ...e, at: t })))
  }
  return { events, state }
}
const face = (yawDeg: number, pitchDeg = 0, score = 0.9): FaceObservation => ({
  yawDeg,
  pitchDeg,
  score,
})
const look = (yaw: number, pitch = 0) => ({ faces: [face(yaw, pitch)] })
const starts = (events: EpisodeEvent[], kind = 'GAZE_AWAY') =>
  events.filter((e) => e.kind === kind && e.phase === 'START')

/** Mira al frente, se desvía a `yaw` en t = 1 000 ms durante `hold` ms y vuelve. */
const turn =
  (yaw: number, hold: number, pitch = 0) =>
  (t: number) =>
    t >= 1000 && t < 1000 + hold ? look(yaw, pitch) : look(0)

describe('014-48: mirada fuera de la pantalla, lo pequeño o breve no cuenta', () => {
  it('giro de 20° durante 10 s no genera nada (por debajo del umbral)', () => {
    expect(run(12000, turn(20, 10000)).events).toEqual([])
  })

  it('giro de 40° durante 1,5 s no genera nada (grande pero breve)', () => {
    expect(run(6000, turn(40, 1500)).events).toEqual([])
  })

  it('giro de 40° durante 2,1 s genera un GAZE_AWAY con inicio y fin', () => {
    const { events } = run(6000, turn(40, 2100))
    expect(starts(events)).toHaveLength(1)
    expect(starts(events)[0]).toMatchObject({ sinceMs: 2000, notify: true })
    const end = events.find((e) => e.phase === 'END')
    expect(end).toMatchObject({ kind: 'GAZE_AWAY', seq: starts(events)[0]!.seq })
    expect(end!.durationMs).toBeGreaterThanOrEqual(2000)
  })

  it('giro brusco de 50° en 0,3 s sostenido 1,2 s genera un episodio', () => {
    expect(starts(run(6000, turn(50, 1200)).events)).toHaveLength(1)
  })

  it('giro brusco de 50° sostenido solo 0,6 s no genera nada', () => {
    expect(run(6000, turn(50, 600)).events).toEqual([])
  })

  it('ruido de ±8° alrededor de 25° no genera nada', () => {
    const noise = [8, -6, 3, -8, 7, -2, 5, -7, 8, -4]
    expect(run(20000, (t) => look(25 + noise[(t / FACE_MS) % noise.length]!)).events).toEqual([])
  })

  it('una oscilación justo en el umbral produce un solo episodio, no uno por cruce', () => {
    const wave = [28, 36, 29, 35, 31, 34]
    const { events } = run(15000, (t) => look(wave[(t / FACE_MS) % wave.length]!))
    expect(starts(events)).toHaveLength(1)
    expect(events.filter((e) => e.phase === 'END')).toHaveLength(0)
  })

  it('mirar al teclado (pitch −30°) durante 10 s no genera nada (margen hacia abajo de 35°)', () => {
    expect(run(12000, turn(0, 10000, -30)).events).toEqual([])
  })

  it('mirar hacia arriba 30° sostenido sí cuenta (umbral de 25° hacia arriba)', () => {
    expect(starts(run(6000, turn(0, 3000, 30)).events)).toHaveLength(1)
  })

  it('con base de 20° (cámara a un lado), mirar a la pantalla no genera nada', () => {
    expect(run(12000, () => look(20, 0), { yawDeg: 20, pitchDeg: 0 }).events).toEqual([])
  })

  it('termina con histéresis: volver a 27° (dentro del umbral pero no 5° adentro) no cierra', () => {
    const { events } = run(10000, (t) => (t < 1000 ? look(0) : t < 4000 ? look(40) : look(27)))
    expect(starts(events)).toHaveLength(1)
    expect(events.filter((e) => e.phase === 'END')).toHaveLength(0)
  })
})

describe('014-49: ausencia, dos rostros y celular', () => {
  const absent = (ms: number) => (t: number) =>
    t >= 1000 && t < 1000 + ms ? { faces: [] } : look(0)

  it('sin rostro 2,9 s no genera nada', () => {
    expect(run(8000, absent(2900)).events).toEqual([])
  })

  it('sin rostro 3,1 s genera un FACE_MISSING que termina al volver', () => {
    const { events } = run(8000, absent(3100))
    expect(starts(events, 'FACE_MISSING')).toHaveLength(1)
    expect(events.find((e) => e.phase === 'END')).toMatchObject({ kind: 'FACE_MISSING' })
  })

  const two = (ms: number) => (t: number) =>
    t >= 1000 && t < 1000 + ms ? { faces: [face(0), face(10, 0, 0.8)] } : look(0)

  it('dos rostros 1,9 s no generan nada', () => {
    expect(run(8000, two(1900)).events).toEqual([])
  })

  it('dos rostros 2,1 s generan MULTIPLE_FACES con el número de rostros', () => {
    const { events } = run(8000, two(2100))
    expect(starts(events, 'MULTIPLE_FACES')).toEqual([
      expect.objectContaining({ faces: 2, sinceMs: 2000 }),
    ])
  })

  it('un segundo rostro con puntaje menor a 0,6 no cuenta', () => {
    const { events } = run(8000, () => ({ faces: [face(0), face(0, 0, 0.5)] }))
    expect(events).toEqual([])
  })

  /** Patrón de celular por cuadro de objetos (true = visto con confianza 0,9). */
  const phone = (pattern: boolean[]) => (t: number) => ({
    phoneScore: pattern[t / OBJECT_MS] ? 0.9 : 0.1,
  })

  it('celular en 4 de 8 cuadros no genera nada', () => {
    const pattern = [true, false, true, false, true, false, true, false, false, false]
    expect(run(5000, phone(pattern), undefined, OBJECT_MS).events).toEqual([])
  })

  it('celular en 5 de 8 cuadros genera PHONE_VISIBLE', () => {
    const pattern = [true, false, true, true, false, true, true, false, false, false]
    const { events } = run(5000, phone(pattern), undefined, OBJECT_MS)
    expect(starts(events, 'PHONE_VISIBLE')).toHaveLength(1)
  })

  it('un celular con confianza menor a 0,6 no cuenta', () => {
    const { events } = run(6000, () => ({ phoneScore: 0.55 }), undefined, OBJECT_MS)
    expect(events).toEqual([])
  })
})

describe('014-50: eventos y espera entre avisos', () => {
  it('dos episodios separados por 20 s dan dos eventos de inicio pero un solo aviso', () => {
    const { events } = run(30000, (t) =>
      (t >= 1000 && t < 4000) || (t >= 21000 && t < 24000) ? look(45) : look(0),
    )
    const begun = starts(events)
    expect(begun).toHaveLength(2)
    expect(begun.map((e) => e.notify)).toEqual([true, false])
    expect(new Set(begun.map((e) => e.seq)).size).toBe(2)
    expect(events.filter((e) => e.phase === 'END')).toHaveLength(2)
  })

  it('pasados 30 s desde el último aviso del mismo tipo, vuelve a avisar', () => {
    const { events } = run(45000, (t) =>
      (t >= 1000 && t < 4000) || (t >= 33000 && t < 36000) ? look(45) : look(0),
    )
    expect(starts(events).map((e) => e.notify)).toEqual([true, true])
  })

  it('la espera es por tipo: un FACE_MISSING justo después de un GAZE_AWAY también avisa', () => {
    const { events } = run(15000, (t) =>
      t >= 1000 && t < 4000 ? look(45) : t >= 5000 && t < 9000 ? { faces: [] } : look(0),
    )
    expect(starts(events, 'GAZE_AWAY')[0]?.notify).toBe(true)
    expect(starts(events, 'FACE_MISSING')[0]?.notify).toBe(true)
  })

  it('un hueco largo entre cuadros (pestaña suspendida) cierra lo abierto y no crea episodios', () => {
    let state = createPolicyState({ yawDeg: 0, pitchDeg: 0 })
    const all: EpisodeEvent[] = []
    for (let t = 0; t <= 3000; t += FACE_MS) {
      const r = step(state, look(45), config, t)
      state = r.state
      all.push(...r.events)
    }
    expect(starts(all)).toHaveLength(1)
    const resumed = step(state, look(45), config, 60000)
    expect(resumed.events).toEqual([expect.objectContaining({ kind: 'GAZE_AWAY', phase: 'END' })])
    expect(step(resumed.state, look(45), config, 60200).events).toEqual([])
  })

  it('step es pura: no cambia el estado que recibe', () => {
    const state = createPolicyState({ yawDeg: 0, pitchDeg: 0 })
    const copy = structuredClone(state)
    step(state, look(80), config, 10)
    expect(state).toEqual(copy)
  })
})

describe('014-47: calibración', () => {
  const frames = (n: number, frame: ProctoringFrame) => Array.from({ length: n }, () => frame)

  it('toma la mediana de yaw y pitch como base', () => {
    const series = [10, 12, 11, 40, 9, 11, 12, 10, 11, 13, 11].map((yaw) => look(yaw, -5))
    expect(calibrate(series, config)).toEqual({
      ok: true,
      base: { yawDeg: 11, pitchDeg: -5 },
      frames: 11,
    })
  })

  it('admite una cámara a un lado hasta 35°', () => {
    expect(calibrate(frames(12, look(30, 0)), config).ok).toBe(true)
  })

  it('con menos de 10 cuadros válidos pide repetir', () => {
    const series = [...frames(9, look(0)), ...frames(5, { faces: [] })]
    expect(calibrate(series, config)).toEqual({ ok: false, reason: 'frames', frames: 9 })
  })

  it('cuadros con dos rostros no cuentan para la calibración', () => {
    expect(calibrate(frames(15, { faces: [face(0), face(0)] }), config)).toMatchObject({
      ok: false,
      reason: 'frames',
    })
  })

  it('si la base se aleja más de 35° del frente pide repetir', () => {
    expect(calibrate(frames(12, look(40, 0)), config)).toMatchObject({
      ok: false,
      reason: 'angle',
    })
  })
})

describe('014-46: umbrales desde el servidor', () => {
  it('lee camelCase, guiones, segundos e ISO-8601 y completa lo que falta', () => {
    const parsed = parseThresholds({
      yawDeg: 28,
      'pitch-down-deg': 40,
      sustain: 'PT3S',
      endHoldMs: 700,
      faceMissing: 4,
      'frame-budget': 80,
      thresholdsVersion: 't-2',
    })
    expect(parsed).toMatchObject({
      yawDeg: 28,
      pitchDownDeg: 40,
      sustainMs: 3000,
      endHoldMs: 700,
      faceMissingMs: 4000,
      frameBudgetMs: 80,
      version: 't-2',
      pitchUpDeg: 25,
    })
  })

  it('acepta las claves que entrega el backend (episodeEndMs para el cierre de episodios)', () => {
    expect(parseThresholds({ episodeEndMs: 1500, noticeCooldownMs: 20000 })).toMatchObject({
      clearHoldMs: 1500,
      noticeCooldownMs: 20000,
    })
  })

  it('nunca baja de 3 y 1 cuadros por segundo', () => {
    expect(parseThresholds({ degradedFaceFps: 1, degradedObjectFps: 0 })).toMatchObject({
      degradedFaceFps: 3,
      degradedObjectFps: 1,
    })
  })

  it('valores no válidos usan el valor por defecto', () => {
    expect(parseThresholds({ yawDeg: 'mucho', smoothingAlpha: 3 })).toMatchObject({
      yawDeg: 30,
      smoothingAlpha: 0.3,
    })
    expect(parseThresholds(null)).toEqual(config)
  })

  it('el intervalo de fotos acepta segundos o ISO-8601 y por defecto es 60 s', () => {
    expect(parseSnapshotInterval(90)).toBe(90000)
    expect(parseSnapshotInterval('PT45S')).toBe(45000)
    expect(parseSnapshotInterval(undefined)).toBe(60000)
  })
})
