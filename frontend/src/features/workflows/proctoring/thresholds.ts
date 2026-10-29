/**
 * Umbrales de la supervisión del examen (spec 014, Enmienda 1, E1.3). Llegan desde el servidor en
 * `proctoring.thresholds` y se guardan por intento (principio 11); estos valores por defecto solo se usan si falta
 * alguno o llega con un valor no válido.
 *
 * Contrato esperado (JSON, camelCase, con la unidad en el nombre): `faceFps`, `objectFps`, `degradedFaceFps`,
 * `degradedObjectFps`, `frameBudgetMs`, `calibrationMs`, `calibrationMinFrames`, `maxBaseDeg`, `smoothingAlpha`,
 * `yawDeg`, `pitchUpDeg`, `pitchDownDeg`, `sustainMs`, `abruptDeg`, `abruptWindowMs`, `abruptSustainMs`,
 * `hysteresisDeg`, `endHoldMs`, `clearHoldMs`, `faceMissingMs`, `multiFaceMs`, `faceScore`, `phoneScore`,
 * `phoneHits`, `phoneWindow`, `noticeCooldownMs`, `version`. Por tolerancia, cada `xxxMs` también se acepta como
 * `xxx` en segundos o en formato ISO-8601 (`PT2S`), y las claves con guiones (`face-fps`).
 */
export interface ProctoringThresholds {
  version: string
  faceFps: number
  objectFps: number
  degradedFaceFps: number
  degradedObjectFps: number
  frameBudgetMs: number
  calibrationMs: number
  calibrationMinFrames: number
  maxBaseDeg: number
  smoothingAlpha: number
  yawDeg: number
  pitchUpDeg: number
  pitchDownDeg: number
  sustainMs: number
  abruptDeg: number
  abruptWindowMs: number
  abruptSustainMs: number
  hysteresisDeg: number
  endHoldMs: number
  /** Tiempo que una condición debe dejar de cumplirse para cerrar ausencia, dos rostros o celular (014-49). */
  clearHoldMs: number
  faceMissingMs: number
  multiFaceMs: number
  faceScore: number
  phoneScore: number
  phoneHits: number
  phoneWindow: number
  noticeCooldownMs: number
}

export const DEFAULT_THRESHOLDS: ProctoringThresholds = {
  version: 'default-1',
  faceFps: 5,
  objectFps: 2,
  degradedFaceFps: 3,
  degradedObjectFps: 1,
  frameBudgetMs: 60,
  calibrationMs: 3000,
  calibrationMinFrames: 10,
  maxBaseDeg: 35,
  smoothingAlpha: 0.3,
  yawDeg: 30,
  pitchUpDeg: 25,
  pitchDownDeg: 35,
  sustainMs: 2000,
  abruptDeg: 45,
  abruptWindowMs: 500,
  abruptSustainMs: 1000,
  hysteresisDeg: 5,
  endHoldMs: 500,
  clearHoldMs: 1000,
  faceMissingMs: 3000,
  multiFaceMs: 2000,
  faceScore: 0.6,
  phoneScore: 0.6,
  phoneHits: 5,
  phoneWindow: 8,
  noticeCooldownMs: 30000,
}

/** Intervalo de las fotos periódicas por defecto (014-53). */
export const DEFAULT_SNAPSHOT_INTERVAL_MS = 60000

const kebab = (key: string) => key.replace(/[A-Z]/g, (c) => '-' + c.toLowerCase())

/** Convierte una duración (ms, segundos o ISO-8601 `PT…S`) a milisegundos. */
function durationMs(value: unknown, unit: 'ms' | 's'): number | null {
  if (typeof value === 'number' && Number.isFinite(value) && value >= 0)
    return unit === 'ms' ? value : value * 1000
  if (typeof value === 'string') {
    const iso = /^PT(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/i.exec(value.trim())
    if (iso && (iso[1] || iso[2]))
      return Math.round((Number(iso[1] ?? 0) * 60 + Number(iso[2] ?? 0)) * 1000)
    const number = Number(value)
    if (value.trim() && Number.isFinite(number) && number >= 0)
      return unit === 'ms' ? number : number * 1000
  }
  return null
}

function pick(raw: Record<string, unknown>, key: string): unknown {
  return raw[key] ?? raw[kebab(key)]
}

/** Lee los umbrales del servidor; lo que falte o no sea válido toma el valor por defecto. */
export function parseThresholds(raw: unknown): ProctoringThresholds {
  const source = raw && typeof raw === 'object' ? (raw as Record<string, unknown>) : {}
  const result = { ...DEFAULT_THRESHOLDS }
  for (const key of Object.keys(DEFAULT_THRESHOLDS) as (keyof ProctoringThresholds)[]) {
    if (key === 'version') {
      const version = pick(source, 'version') ?? pick(source, 'thresholdsVersion')
      if (typeof version === 'string' && version) result.version = version
      continue
    }
    if (key.endsWith('Ms')) {
      const base = key.slice(0, -2)
      // `frame-budget` se expresa en milisegundos en la spec; las demás duraciones, en segundos.
      const value =
        durationMs(pick(source, key), 'ms') ??
        durationMs(pick(source, base), key === 'frameBudgetMs' ? 'ms' : 's')
      if (value !== null) result[key] = value
      continue
    }
    const value = pick(source, key)
    const number = typeof value === 'string' ? Number(value) : value
    if (typeof number === 'number' && Number.isFinite(number) && number >= 0) result[key] = number
  }
  // Nunca menos de 3 y 1 cuadros por segundo (014-46).
  result.degradedFaceFps = Math.max(3, Math.min(result.degradedFaceFps, result.faceFps))
  result.degradedObjectFps = Math.max(1, Math.min(result.degradedObjectFps, result.objectFps))
  result.phoneWindow = Math.max(1, Math.round(result.phoneWindow))
  result.phoneHits = Math.min(result.phoneWindow, Math.max(1, Math.round(result.phoneHits)))
  if (result.smoothingAlpha <= 0 || result.smoothingAlpha > 1)
    result.smoothingAlpha = DEFAULT_THRESHOLDS.smoothingAlpha
  return result
}

/** Intervalo de fotos del servidor (`snapshotInterval` en segundos o ISO-8601), con 60 s por defecto. */
export function parseSnapshotInterval(raw: unknown): number {
  const value = durationMs(raw, 's')
  return value && value >= 10000 ? value : DEFAULT_SNAPSHOT_INTERVAL_MS
}
