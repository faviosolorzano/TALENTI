/// <reference types="node" />
import { existsSync, readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { createSimulatedDetector } from './detector'
import { EventBudget } from './eventBudget'
import { ProctoringSession } from './session'
import { DEFAULT_THRESHOLDS } from './thresholds'

vi.mock('./capture', () => ({
  analysisFrame: () => ({}),
  captureSnapshot: async () => new Blob(['jpeg'], { type: 'image/jpeg' }),
}))

function camera() {
  const track = {
    stop: vi.fn(),
    getSettings: () => ({ deviceId: 'cam' }),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  }
  Object.defineProperty(navigator, 'mediaDevices', {
    configurable: true,
    value: {
      getUserMedia: vi.fn(async () => ({
        getTracks: () => [track],
        getVideoTracks: () => [track],
      })),
    },
  })
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
  return track
}

afterEach(() => {
  Reflect.deleteProperty(navigator, 'mediaDevices')
  vi.restoreAllMocks()
  vi.useRealTimers()
})

describe('014-46: ritmo del análisis', () => {
  it('si el tiempo medio por cuadro pasa de 60 ms, baja a 3 y 1 cuadros por segundo', async () => {
    camera()
    const session = new ProctoringSession({
      config: { ...DEFAULT_THRESHOLDS, faceFps: 50 },
      snapshotIntervalMs: 60000,
      detectorFactory: () => createSimulatedDetector(undefined, { costMs: 70 }),
      ports: { report: vi.fn(), upload: vi.fn(async () => {}) },
    })
    await session.openCamera()
    await session.loadDetection()
    session.useBase({ yawDeg: 0, pitchDeg: 0 })
    session.beginMonitoring()
    await vi.waitFor(() => expect(session.view().degraded).toBe(true), { timeout: 5000 })
    session.dispose()
  })

  it('un equipo rápido mantiene el ritmo normal', async () => {
    camera()
    const session = new ProctoringSession({
      config: { ...DEFAULT_THRESHOLDS, faceFps: 50 },
      snapshotIntervalMs: 60000,
      detectorFactory: () => createSimulatedDetector(),
      ports: { report: vi.fn(), upload: vi.fn(async () => {}) },
    })
    await session.openCamera()
    await session.loadDetection()
    session.useBase({ yawDeg: 0, pitchDeg: 0 })
    session.beginMonitoring()
    await new Promise((r) => setTimeout(r, 400))
    expect(session.view().degraded).toBe(false)
    session.dispose()
  })
})

describe('014-53: fotos', () => {
  it('toma una foto por minuto y, si la subida falla, reintenta una sola vez', async () => {
    camera()
    const upload = vi.fn(async () => {
      throw new Error('red')
    })
    const session = new ProctoringSession({
      config: DEFAULT_THRESHOLDS,
      snapshotIntervalMs: 60000,
      detectorFactory: () => createSimulatedDetector(undefined, { fail: true }),
      ports: { report: vi.fn(), upload },
    })
    await session.openCamera()
    await session.loadDetection()
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout'] })
    session.beginMonitoring()
    await vi.waitFor(() => expect(upload).toHaveBeenCalledTimes(2))
    expect(upload.mock.calls.map((c) => (c as unknown[])[1])).toEqual(['START', 'START'])
    await vi.advanceTimersByTimeAsync(60000)
    await vi.waitFor(() => expect(upload).toHaveBeenCalledTimes(4))
    expect((upload.mock.calls[2] as unknown[])[1]).toBe('PERIODIC')
    session.dispose()
  })

  it('al apagar la sesión se detienen la cámara, los temporizadores y el detector', async () => {
    const track = camera()
    const detector = createSimulatedDetector()
    const upload = vi.fn(async () => {})
    const session = new ProctoringSession({
      config: DEFAULT_THRESHOLDS,
      snapshotIntervalMs: 60000,
      detectorFactory: () => detector,
      ports: { report: vi.fn(), upload },
    })
    await session.openCamera()
    await session.loadDetection()
    session.useBase({ yawDeg: 0, pitchDeg: 0 })
    vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval', 'setTimeout', 'clearTimeout'] })
    session.beginMonitoring()
    session.dispose()
    expect(track.stop).toHaveBeenCalled()
    expect(detector.closed).toBe(true)
    const calls = upload.mock.calls.length
    await vi.advanceTimersByTimeAsync(180000)
    expect(upload.mock.calls.length).toBe(calls)
  })
})

describe('014-51: presupuesto de eventos del navegador', () => {
  it('SCREEN_LEFT siempre sale aunque se hayan gastado los demás', () => {
    const budget = new EventBudget()
    let allowed = 0
    for (let i = 0; i < 100; i++) if (budget.allow('COPY_ATTEMPT', i * 2500)) allowed++
    expect(allowed).toBeLessThanOrEqual(16 * 5)
    const full = new EventBudget()
    for (let i = 0; i < 40; i++) full.allow('GAZE_AWAY', i)
    expect(full.allow('SCREEN_LEFT', 50)).toBe(true)
  })

  it('copiar repetido sale una vez cada 2 s y no gasta el cupo de los episodios', () => {
    const budget = new EventBudget()
    expect(budget.allow('COPY_ATTEMPT', 0)).toBe(true)
    expect(budget.allow('COPY_ATTEMPT', 500)).toBe(false)
    expect(budget.allow('COPY_ATTEMPT', 2100)).toBe(true)
    for (let i = 0; i < 30; i++) budget.allow('PASTE_ATTEMPT', 3000 + i * 2100)
    expect(budget.allow('GAZE_AWAY', 4000)).toBe(true)
  })
})

describe('014-37: aviso v2', () => {
  const normalize = (text: string) => text.replace(/\r\n/g, '\n').trim()
  const client = readFileSync(
    resolve(import.meta.dirname, '../../../content/exam-notice-v2.md'),
    'utf-8',
  )
  const backend = resolve(
    import.meta.dirname,
    '../../../../../backend/src/main/resources/exam/notice-v2.md',
  )

  it('informa cámara, qué se detecta en el equipo, fotos, quién las ve, 90 días, sin IA y que ningún aviso reprueba', () => {
    for (const fragment of [
      'Cámara obligatoria',
      'sin enviar la imagen',
      'una cada 60 segundos',
      'solo la empresa de esta vacante',
      'nunca se envían a una inteligencia artificial',
      '90 días',
      'marca de agua',
      'pantalla completa',
      'tecla de captura',
      'Ningún aviso de cámara reprueba',
    ])
      expect(client).toContain(fragment)
  })

  it.skipIf(!existsSync(backend))('es exactamente el texto del backend', () => {
    expect(normalize(client)).toBe(normalize(readFileSync(backend, 'utf-8')))
  })
})
