import type { ProctoringThresholds } from './thresholds'

/**
 * Decisión de la supervisión del examen (spec 014, 014-46 a 014-50) como función pura: recibe cuadros con su hora
 * (`performance.now()`, en ms) y devuelve episodios. No conoce la cámara, el detector ni la red.
 *
 * Solo usa presencia, número de rostros y orientación de la cabeza (yaw/pitch, en grados; pitch positivo = arriba);
 * nunca expresiones ni rasgos (principio 3). Los umbrales no dependen de la persona.
 */

export type EpisodeKind = 'GAZE_AWAY' | 'FACE_MISSING' | 'MULTIPLE_FACES' | 'PHONE_VISIBLE'

export interface FaceObservation {
  yawDeg: number
  pitchDeg: number
  /** Confianza de la detección del rostro (0 a 1). */
  score: number
}

/**
 * Un cuadro analizado. `faces` existe si el cuadro pasó por el detector de rostros; `phoneScore` (la mayor
 * confianza de "cell phone", 0 si no hay) si pasó por el de objetos. Un cuadro puede traer uno o ambos.
 */
export interface ProctoringFrame {
  faces?: FaceObservation[]
  phoneScore?: number
}

export interface Pose {
  yawDeg: number
  pitchDeg: number
}

export interface EpisodeEvent {
  kind: EpisodeKind
  phase: 'START' | 'END'
  /** Número del episodio dentro de esta sesión; quien llama lo convierte en `episodeId` (UUID). */
  seq: number
  /** Al empezar: lo transcurrido desde que empezó la desviación. */
  sinceMs?: number
  /** Al terminar: duración del episodio. */
  durationMs?: number
  /** Con `MULTIPLE_FACES`: cuántos rostros. */
  faces?: number
  /** Solo en `START`: si corresponde mostrar el aviso y tomar la foto (espera entre avisos, 014-50). */
  notify: boolean
}

interface OpenEpisode {
  seq: number
  onsetAt: number
  /** Desde cuándo dejó de cumplirse la condición (para cerrar). */
  clearSince: number | null
}

interface Tracker {
  /** Desde cuándo se cumple la condición sin episodio abierto. */
  since: number | null
  episode: OpenEpisode | null
}

export interface PolicyState {
  base: Pose
  smooth: Pose | null
  /** Poses crudas recientes, para el giro brusco. */
  recent: { at: number; yawDeg: number; pitchDeg: number }[]
  gaze: Tracker & { abruptAt: number | null }
  missing: Tracker
  multi: Tracker & { faces: number }
  phone: { hits: { at: number; hit: boolean }[]; episode: OpenEpisode | null }
  lastFrameAt: number | null
  lastNoticeAt: Partial<Record<EpisodeKind, number>>
  seq: number
}

/** Un hueco mayor que este entre cuadros (pestaña suspendida, cámara perdida) reinicia el estado sin episodios. */
export const GAP_MS = 2500

export function createPolicyState(base: Pose): PolicyState {
  return {
    base,
    smooth: null,
    recent: [],
    gaze: { since: null, episode: null, abruptAt: null },
    missing: { since: null, episode: null },
    multi: { since: null, episode: null, faces: 0 },
    phone: { hits: [], episode: null },
    lastFrameAt: null,
    lastNoticeAt: {},
    seq: 0,
  }
}

function startEpisode(
  state: PolicyState,
  kind: EpisodeKind,
  onsetAt: number,
  now: number,
  config: ProctoringThresholds,
  extra: Partial<EpisodeEvent> = {},
): { episode: OpenEpisode; event: EpisodeEvent } {
  const last = state.lastNoticeAt[kind]
  const notify = last === undefined || now - last >= config.noticeCooldownMs
  if (notify) state.lastNoticeAt[kind] = now
  const seq = ++state.seq
  return {
    episode: { seq, onsetAt, clearSince: null },
    event: { kind, phase: 'START', seq, sinceMs: Math.round(now - onsetAt), notify, ...extra },
  }
}

function endEvent(kind: EpisodeKind, episode: OpenEpisode, endAt: number): EpisodeEvent {
  return {
    kind,
    phase: 'END',
    seq: episode.seq,
    durationMs: Math.max(0, Math.round(endAt - episode.onsetAt)),
    notify: false,
  }
}

/** Lleva la cuenta del cierre: devuelve el evento `END` si la condición dejó de cumplirse durante `hold`. */
function settle(
  kind: EpisodeKind,
  episode: OpenEpisode,
  conditionHolds: boolean,
  now: number,
  hold: number,
): EpisodeEvent | null {
  if (conditionHolds) {
    episode.clearSince = null
    return null
  }
  episode.clearSince ??= now
  return now - episode.clearSince >= hold ? endEvent(kind, episode, episode.clearSince) : null
}

function outside(dYaw: number, dPitch: number, config: ProctoringThresholds, margin = 0) {
  return (
    Math.abs(dYaw) > config.yawDeg - margin ||
    dPitch > config.pitchUpDeg - margin ||
    dPitch < -(config.pitchDownDeg - margin)
  )
}

/** Cierra los episodios abiertos en `at` y reinicia el estado (conserva base, avisos y numeración). */
export function suspendPolicy(
  state: PolicyState,
  at: number,
): { state: PolicyState; events: EpisodeEvent[] } {
  const events: EpisodeEvent[] = []
  if (state.gaze.episode) events.push(endEvent('GAZE_AWAY', state.gaze.episode, at))
  if (state.missing.episode) events.push(endEvent('FACE_MISSING', state.missing.episode, at))
  if (state.multi.episode) events.push(endEvent('MULTIPLE_FACES', state.multi.episode, at))
  if (state.phone.episode) events.push(endEvent('PHONE_VISIBLE', state.phone.episode, at))
  const next = createPolicyState(state.base)
  next.lastNoticeAt = { ...state.lastNoticeAt }
  next.seq = state.seq
  return { state: next, events }
}

/**
 * Avanza la decisión con un cuadro tomado en `now`. Pura: no modifica `state`; devuelve el siguiente estado y los
 * eventos (`START`/`END`) que produjo este cuadro.
 */
export function step(
  previous: PolicyState,
  frame: ProctoringFrame,
  config: ProctoringThresholds,
  now: number,
): { state: PolicyState; events: EpisodeEvent[] } {
  let state = structuredClone(previous)
  const events: EpisodeEvent[] = []
  if (state.lastFrameAt !== null && now - state.lastFrameAt > GAP_MS) {
    // El hueco no crea episodios: se cierran los abiertos en el último cuadro visto y se empieza de nuevo.
    const suspended = suspendPolicy(state, state.lastFrameAt)
    state = suspended.state
    events.push(...suspended.events)
  }
  state.lastFrameAt = now

  if (frame.faces) {
    const faces = frame.faces.filter((f) => f.score >= config.faceScore)
    const count = faces.length

    // Ausencia de rostro (014-49).
    const missing = state.missing
    if (missing.episode) {
      const end = settle('FACE_MISSING', missing.episode, count === 0, now, config.clearHoldMs)
      if (end) {
        events.push(end)
        missing.episode = null
        missing.since = null
      }
    } else if (count === 0) {
      missing.since ??= now
      if (now - missing.since >= config.faceMissingMs) {
        const started = startEpisode(state, 'FACE_MISSING', missing.since, now, config)
        missing.episode = started.episode
        events.push(started.event)
      }
    } else missing.since = null

    // Dos o más rostros (014-49).
    const multi = state.multi
    if (multi.episode) {
      const end = settle('MULTIPLE_FACES', multi.episode, count >= 2, now, config.clearHoldMs)
      if (end) {
        events.push(end)
        multi.episode = null
        multi.since = null
      }
    } else if (count >= 2) {
      multi.since ??= now
      multi.faces = Math.max(multi.faces, count)
      if (now - multi.since >= config.multiFaceMs) {
        const started = startEpisode(state, 'MULTIPLE_FACES', multi.since, now, config, {
          faces: Math.min(10, multi.faces),
        })
        multi.episode = started.episode
        events.push(started.event)
      }
    } else {
      multi.since = null
      multi.faces = 0
    }

    // Mirada fuera de la pantalla (014-47, 014-48).
    stepGaze(state, faces[0], config, now, events)
  }

  if (frame.phoneScore !== undefined) {
    const phone = state.phone
    phone.hits.push({ at: now, hit: frame.phoneScore >= config.phoneScore })
    if (phone.hits.length > config.phoneWindow)
      phone.hits.splice(0, phone.hits.length - config.phoneWindow)
    const hits = phone.hits.filter((h) => h.hit)
    const met = hits.length >= config.phoneHits
    if (phone.episode) {
      const end = settle('PHONE_VISIBLE', phone.episode, met, now, config.clearHoldMs)
      if (end) {
        events.push(end)
        phone.episode = null
      }
    } else if (met) {
      const started = startEpisode(state, 'PHONE_VISIBLE', hits[0]!.at, now, config)
      phone.episode = started.episode
      events.push(started.event)
    }
  }
  return { state, events }
}

function stepGaze(
  state: PolicyState,
  face: FaceObservation | undefined,
  config: ProctoringThresholds,
  now: number,
  events: EpisodeEvent[],
) {
  const gaze = state.gaze
  if (!face) {
    // Sin rostro no hay mirada que medir: lo cubre `FACE_MISSING`. Un episodio abierto espera al rostro.
    if (!gaze.episode) {
      gaze.since = null
      gaze.abruptAt = null
      state.smooth = null
      state.recent = []
    }
    return
  }
  const alpha = config.smoothingAlpha
  state.smooth = state.smooth
    ? {
        yawDeg: alpha * face.yawDeg + (1 - alpha) * state.smooth.yawDeg,
        pitchDeg: alpha * face.pitchDeg + (1 - alpha) * state.smooth.pitchDeg,
      }
    : { yawDeg: face.yawDeg, pitchDeg: face.pitchDeg }
  const raw = { yaw: face.yawDeg - state.base.yawDeg, pitch: face.pitchDeg - state.base.pitchDeg }
  const smooth = {
    yaw: state.smooth.yawDeg - state.base.yawDeg,
    pitch: state.smooth.pitchDeg - state.base.pitchDeg,
  }
  // Giro brusco: más de `abruptDeg` respecto de alguna pose de la última ventana. Se mide en crudo porque la media
  // móvil, a 5 cuadros por segundo, esconde cualquier cambio rápido.
  state.recent = state.recent.filter((r) => now - r.at < config.abruptWindowMs)
  const abrupt = state.recent.some(
    (r) => Math.hypot(face.yawDeg - r.yawDeg, face.pitchDeg - r.pitchDeg) > config.abruptDeg,
  )
  state.recent.push({ at: now, yawDeg: face.yawDeg, pitchDeg: face.pitchDeg })

  const rawOut = outside(raw.yaw, raw.pitch, config)
  const smoothOut = outside(smooth.yaw, smooth.pitch, config)

  if (gaze.episode) {
    // Vuelve cuando la pose cruda y la suavizada están dentro del umbral menos la histéresis.
    const back =
      !outside(raw.yaw, raw.pitch, config, config.hysteresisDeg) &&
      !outside(smooth.yaw, smooth.pitch, config, config.hysteresisDeg)
    const end = settle('GAZE_AWAY', gaze.episode, !back, now, config.endHoldMs)
    if (end) {
      events.push(end)
      gaze.episode = null
      gaze.since = null
      gaze.abruptAt = null
    }
    return
  }
  if (!rawOut && !smoothOut) {
    gaze.since = null
    gaze.abruptAt = null
    return
  }
  // La desviación empieza con el primer cuadro fuera y sigue mientras la pose cruda o la suavizada estén fuera.
  gaze.since ??= now
  if (abrupt && rawOut) gaze.abruptAt ??= now
  const sustained = smoothOut && now - gaze.since >= config.sustainMs
  const abruptHeld =
    gaze.abruptAt !== null && rawOut && now - gaze.abruptAt >= config.abruptSustainMs
  if (sustained || abruptHeld) {
    const started = startEpisode(state, 'GAZE_AWAY', gaze.since, now, config)
    gaze.episode = started.episode
    events.push(started.event)
  }
}

export type CalibrationResult =
  | { ok: true; base: Pose; frames: number }
  | { ok: false; reason: 'frames' | 'angle'; frames: number }

const median = (values: number[]) => {
  const sorted = [...values].sort((a, b) => a - b)
  const middle = Math.floor(sorted.length / 2)
  return sorted.length % 2 ? sorted[middle]! : (sorted[middle - 1]! + sorted[middle]!) / 2
}

/**
 * Calibración (014-47): con al menos `calibrationMinFrames` cuadros con un solo rostro, la mediana de yaw y pitch es
 * la pose base; si se aleja más de `maxBaseDeg` del frente, se pide repetir.
 */
export function calibrate(
  frames: ProctoringFrame[],
  config: ProctoringThresholds,
): CalibrationResult {
  const valid = frames
    .map((frame) => frame.faces?.filter((f) => f.score >= config.faceScore))
    .filter((faces): faces is FaceObservation[] => faces?.length === 1)
    .map((faces) => faces[0]!)
  if (valid.length < config.calibrationMinFrames)
    return { ok: false, reason: 'frames', frames: valid.length }
  const base = {
    yawDeg: median(valid.map((f) => f.yawDeg)),
    pitchDeg: median(valid.map((f) => f.pitchDeg)),
  }
  if (Math.hypot(base.yawDeg, base.pitchDeg) > config.maxBaseDeg)
    return { ok: false, reason: 'angle', frames: valid.length }
  return { ok: true, base, frames: valid.length }
}
