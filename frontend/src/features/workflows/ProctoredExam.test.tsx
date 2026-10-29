import { act, fireEvent, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { openStudentApp } from '../../test/studentApp'
import { clearCsrfCookie } from '../../test/fakeIdentity'
import { es } from '../../i18n/es'
import type { ExamAttempt } from '../../api/workflows'

const t = es.workflows
const p = t.proctoring

/** Lo que "ve" el detector simulado (014-45): se cambia durante la prueba. */
const sim = vi.hoisted(() => ({ yaw: 0, faces: 1, fail: false, loads: 0, closed: 0 }))
vi.mock('./proctoring/detector', async (importOriginal) => {
  const actual = await importOriginal<typeof import('./proctoring/detector')>()
  return {
    ...actual,
    createMediaPipeDetector: () => {
      const detector = actual.createSimulatedDetector(
        () => ({
          faces: Array.from({ length: sim.faces }, () => ({
            yawDeg: sim.yaw,
            pitchDeg: 0,
            score: 0.95,
          })),
        }),
        { fail: sim.fail },
      )
      const load = detector.load.bind(detector)
      detector.load = async () => {
        sim.loads++
        await load()
      }
      detector.close = () => {
        sim.closed++
      }
      return detector
    },
  }
})
// En jsdom no hay imagen real: el cuadro de análisis y la foto son sustitutos.
vi.mock('./proctoring/capture', () => ({
  analysisFrame: () => ({}),
  captureSnapshot: async () => new Blob(['jpeg'], { type: 'image/jpeg' }),
}))

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

/** Umbrales rápidos para que la prueba no espere segundos reales. */
const thresholds = {
  faceFps: 20,
  objectFps: 2,
  calibrationMs: 1500,
  calibrationMinFrames: 2,
  sustainMs: 300,
  noticeCooldownMs: 30000,
}

function attempt(overrides: Partial<ExamAttempt> = {}): ExamAttempt {
  return {
    id: 'attempt-test',
    applicationId: 'app-test',
    status: 'NOT_STARTED',
    gradingStatus: 'NOT_STARTED',
    noticeVersion: 'v2',
    notice: 'Aviso v2 de prueba',
    startedAt: null,
    deadlineAt: null,
    remainingSeconds: 3600,
    events: [],
    answers: [],
    proctoring: { required: true, thresholds, snapshotInterval: 60 },
    watermark: { name: 'Ana Prueba', code: 'T-APP123' },
    questions: [
      {
        id: 'q1',
        type: 'OPEN',
        prompt: 'Explica tu decisión de diseño',
        generatedBy: 'BANK',
        options: [],
        rubric: [],
        language: null,
        kind: null,
        initialCode: null,
        cases: [],
      },
    ],
    ...overrides,
  }
}

interface Recorded {
  calls: string[]
  events: { kind: string; clientEventId: string; data?: Record<string, unknown> }[]
  snapshots: { reason: string; eventClientId: string | null }[]
  start: unknown[]
  notice: unknown[]
}

function mount(initial: ExamAttempt, options: { startStatus?: number } = {}) {
  const rec: Recorded = { calls: [], events: [], snapshots: [], start: [], notice: [] }
  const app = openStudentApp('/postulaciones/app-test/examen')
  const fallback = globalThis.fetch
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input).split('/api/v1')[1] ?? ''
    if (path === '/applications/app-test/exam') return Promise.resolve(json(initial))
    if (path === '/exam-attempts/attempt-test/notice') {
      rec.notice.push(JSON.parse(String(init?.body)))
      return Promise.resolve(json({ ...initial, noticeVersion: 'v2' }))
    }
    if (path === '/exam-attempts/attempt-test/start') {
      rec.calls.push('start')
      rec.start.push(JSON.parse(String(init?.body)))
      if (options.startStatus)
        return Promise.resolve(json({ code: 'proctoring-required' }, options.startStatus))
      return Promise.resolve(json({ ...initial, status: 'IN_PROGRESS' }))
    }
    if (path === '/exam-attempts/attempt-test/events') {
      rec.events.push(JSON.parse(String(init?.body)))
      return Promise.resolve(json({ rule: 'NONE', message: '', status: 'IN_PROGRESS' }))
    }
    if (path === '/exam-attempts/attempt-test/snapshots') {
      const form = init?.body as FormData
      rec.snapshots.push({
        reason: String(form.get('reason')),
        eventClientId: form.get('eventClientId') as string | null,
      })
      expect(form.get('image')).toBeInstanceOf(Blob)
      expect(String(form.get('clientTakenAt'))).toMatch(/^\d{4}-\d{2}-\d{2}T/)
      return Promise.resolve(json({ id: 'snap', takenAt: '2026-10-29T15:00:00Z' }, 201))
    }
    return fallback(input, init)
  })
  return { ...app, rec }
}

let fullscreenElement: Element | null = null
let media: ReturnType<typeof vi.fn>
let tracks: { stop: ReturnType<typeof vi.fn>; fire: (type: string) => void }[] = []

function fakeStream(deviceId = 'cam-1') {
  const listeners: Record<string, (() => void)[]> = {}
  const track = {
    kind: 'video',
    stop: vi.fn(),
    getSettings: () => ({ deviceId }),
    addEventListener: (type: string, listener: () => void) =>
      (listeners[type] ??= []).push(listener),
    removeEventListener: (type: string, listener: () => void) => {
      listeners[type] = (listeners[type] ?? []).filter((l) => l !== listener)
    },
    fire: (type: string) => listeners[type]?.forEach((l) => l()),
  }
  tracks.push(track)
  return { id: 'stream-' + deviceId, getTracks: () => [track], getVideoTracks: () => [track] }
}

function setFullscreen(element: Element | null) {
  fullscreenElement = element
  document.dispatchEvent(new Event('fullscreenchange'))
}

beforeEach(() => {
  Object.assign(sim, { yaw: 0, faces: 1, fail: false, loads: 0, closed: 0 })
  tracks = []
  fullscreenElement = null
  window.localStorage.setItem('talenti.media.camera', 'cam-guardada')
  media = vi.fn(async () => fakeStream())
  Object.defineProperty(navigator, 'mediaDevices', {
    configurable: true,
    value: {
      getUserMedia: media,
      enumerateDevices: async () => [{ kind: 'videoinput', deviceId: 'cam-1', label: '' }],
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    },
  })
  Object.defineProperty(document, 'fullscreenEnabled', { configurable: true, value: true })
  Object.defineProperty(document, 'fullscreenElement', {
    configurable: true,
    get: () => fullscreenElement,
  })
  document.documentElement.requestFullscreen = vi.fn(async () => {
    setFullscreen(document.documentElement)
  })
  document.exitFullscreen = vi.fn(async () => setFullscreen(null))
  vi.spyOn(HTMLMediaElement.prototype, 'play').mockResolvedValue(undefined)
})
afterEach(() => {
  clearCsrfCookie()
  window.localStorage.removeItem('talenti.media.camera')
  Reflect.deleteProperty(navigator, 'mediaDevices')
  Reflect.deleteProperty(document, 'fullscreenEnabled')
  Reflect.deleteProperty(document, 'fullscreenElement')
  vi.restoreAllMocks()
})

/** Recorre "Comenzar" hasta el examen en curso. */
async function startExam(user: ReturnType<typeof mount>['user']) {
  await user.click(await screen.findByRole('button', { name: p.begin }))
  await user.click(await screen.findByRole('button', { name: p.enterAndStart }, { timeout: 15000 }))
  await screen.findByText('Explica tu decisión de diseño')
}

it('014-38: "Comenzar" sigue el orden cámara guardada, modelos, calibración y pantalla completa antes de start', async () => {
  const { user, rec } = mount(attempt())
  await user.click(await screen.findByRole('button', { name: p.begin }))
  expect(media).toHaveBeenCalledWith({
    video: {
      facingMode: 'user',
      width: { ideal: 640 },
      height: { ideal: 480 },
      deviceId: { ideal: 'cam-guardada' },
    },
    audio: false,
  })
  // El texto de calibración es pasajero (1,5 s aquí); se comprueba el resultado: base tomada y botón final.
  const enter = await screen.findByRole('button', { name: p.enterAndStart }, { timeout: 15000 })
  expect(sim.loads).toBe(1)
  expect(rec.calls).toEqual([])
  // La cámara que quedó en uso se recuerda para la próxima vez.
  expect(window.localStorage.getItem('talenti.media.camera')).toBe('cam-1')
  await user.click(enter)
  await screen.findByText('Explica tu decisión de diseño')
  expect(document.documentElement.requestFullscreen).toHaveBeenCalledTimes(1)
  expect(rec.start).toEqual([
    {
      cameraReady: true,
      fullscreen: true,
      detection: 'AVAILABLE',
      calibration: { baseYawDeg: 0, basePitchDeg: 0 },
    },
  ])
})

it('014-53: al empezar se sube la foto inicial como multipart con motivo START', async () => {
  const { user, rec } = mount(attempt())
  await startExam(user)
  await waitFor(() => expect(rec.snapshots).toEqual([{ reason: 'START', eventClientId: null }]))
})

it('014-47: si la calibración no ve un rostro, pide repetir y no llama a start', async () => {
  sim.faces = 0
  const { user, rec } = mount(attempt())
  await user.click(await screen.findByRole('button', { name: p.begin }))
  expect(await screen.findByText(p.calibrationFrames)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: p.enterAndStart })).toBeNull()
  sim.faces = 1
  await user.click(screen.getByRole('button', { name: p.repeatCalibration }))
  expect(await screen.findByRole('button', { name: p.enterAndStart })).toBeInTheDocument()
  expect(rec.start).toEqual([])
})

it('014-39: un navegador sin pantalla completa no puede empezar y ve el mensaje claro', async () => {
  Object.defineProperty(document, 'fullscreenEnabled', { configurable: true, value: false })
  const { user, rec } = mount(attempt())
  await user.click(await screen.findByRole('button', { name: p.begin }))
  expect(await screen.findByRole('alert')).toHaveTextContent(p.unsupported)
  expect(media).not.toHaveBeenCalled()
  expect(rec.start).toEqual([])
})

it('014-39: en un celular no se puede empezar', async () => {
  vi.spyOn(navigator, 'userAgent', 'get').mockReturnValue(
    'Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/130.0 Mobile Safari/537.36',
  )
  const { user, rec } = mount(attempt())
  await user.click(await screen.findByRole('button', { name: p.begin }))
  expect(await screen.findByRole('alert')).toHaveTextContent(p.unsupported)
  expect(rec.start).toEqual([])
})

it('014-39: sin permiso de cámara se ve el mensaje y el intento sigue sin empezar', async () => {
  media.mockRejectedValue(new DOMException('denied', 'NotAllowedError'))
  const { user, rec } = mount(attempt())
  await user.click(await screen.findByRole('button', { name: p.begin }))
  expect(await screen.findByRole('alert')).toHaveTextContent(p.unsupported)
  expect(screen.getByText(p.cameraDenied)).toBeInTheDocument()
  expect(screen.getByRole('button', { name: p.retry })).toBeEnabled()
  expect(rec.start).toEqual([])
})

it('014-40: si los modelos no cargan se empieza con cámara y fotos, y se registra DETECTION_UNAVAILABLE', async () => {
  sim.fail = true
  const { user, rec } = mount(attempt())
  await user.click(await screen.findByRole('button', { name: p.begin }))
  expect(await screen.findByText(p.detectionUnavailable)).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: p.enterAndStart }))
  await screen.findByText('Explica tu decisión de diseño')
  expect(rec.start[0]).toMatchObject({ detection: 'UNAVAILABLE', calibration: null })
  await waitFor(() => expect(rec.events.map((e) => e.kind)).toContain('DETECTION_UNAVAILABLE'))
})

it('014-37: un intento sin empezar con aviso v1 debe aceptar el aviso v2 antes de comenzar', async () => {
  const { user, rec } = mount(attempt({ noticeVersion: 'v1' }))
  expect(await screen.findByText(p.newNotice)).toBeInTheDocument()
  const accept = screen.getByRole('button', { name: p.acceptNotice })
  expect(accept).toBeDisabled()
  expect(screen.queryByRole('button', { name: p.begin })).toBeNull()
  await user.click(screen.getByRole('checkbox', { name: t.acceptNotice }))
  await user.click(accept)
  expect(await screen.findByRole('button', { name: p.begin })).toBeInTheDocument()
  expect(rec.notice).toEqual([{ noticeAccepted: true, noticeVersion: 'v2' }])
})

it('014-41: la vista propia de la cámara se ve con su rótulo durante el examen', async () => {
  const { user } = mount(attempt())
  await startExam(user)
  expect(screen.getByText(p.cameraLabel, { selector: 'figcaption' })).toBeVisible()
  expect(screen.getByLabelText(p.cameraLabel, { selector: 'video' })).toBeInTheDocument()
})

it('014-42: al salir de pantalla completa se envía SCREEN_LEFT y las preguntas se tapan sin perder lo escrito', async () => {
  const { user, rec } = mount(attempt())
  await startExam(user)
  await user.type(screen.getByRole('textbox'), 'Borrador sin guardar')
  act(() => setFullscreen(null))
  expect(await screen.findByText(p.leftFullscreen)).toBeInTheDocument()
  expect(screen.getByTestId('exam-questions')).not.toBeVisible()
  expect(screen.queryByRole('textbox')).toBeNull()
  await waitFor(() => expect(rec.events.map((e) => e.kind)).toContain('SCREEN_LEFT'))
  await user.click(screen.getByRole('button', { name: p.returnFullscreen }))
  expect(await screen.findByRole('textbox')).toHaveValue('Borrador sin guardar')
  await waitFor(() => expect(rec.events.map((e) => e.kind)).toContain('SCREEN_RETURNED'))
  expect(rec.events.filter((e) => e.kind === 'SCREEN_LEFT')).toHaveLength(1)
})

it('014-38: perder cámara mientras se prepara permite reintentar sin enviar un inicio inválido', async () => {
  const { user, rec } = mount(attempt())
  await user.click(await screen.findByRole('button', { name: p.begin }))
  const enter = await screen.findByRole('button', { name: p.enterAndStart }, { timeout: 15000 })
  act(() => tracks[0]!.fire('ended'))
  await user.click(enter)
  expect(await screen.findByText(p.cameraNeeded)).toBeVisible()
  expect(rec.start).toHaveLength(0)
  await user.click(screen.getByRole('button', { name: p.begin }))
  await user.click(await screen.findByRole('button', { name: p.enterAndStart }, { timeout: 15000 }))
  expect(await screen.findByText('Explica tu decisión de diseño')).toBeVisible()
  expect(rec.start).toHaveLength(1)
})

it('014-41: si la cámara se corta se registra CAMERA_LOST, se tapan las preguntas y "Reconectar" las devuelve', async () => {
  const { user, rec } = mount(attempt())
  await startExam(user)
  await user.type(screen.getByRole('textbox'), 'Respuesta a medias')
  act(() => tracks[0]!.fire('ended'))
  expect(await screen.findByText(p.cameraLost)).toBeInTheDocument()
  expect(screen.queryByRole('textbox')).toBeNull()
  expect(screen.getByText(p.timeContinues)).toBeInTheDocument()
  await waitFor(() => expect(rec.events.map((e) => e.kind)).toContain('CAMERA_LOST'))
  await user.click(screen.getByRole('button', { name: p.reconnect }))
  expect(await screen.findByRole('textbox')).toHaveValue('Respuesta a medias')
  await waitFor(() => expect(rec.events.map((e) => e.kind)).toContain('CAMERA_RESTORED'))
  // Perder la cámara nunca cierra el examen (014-52).
  expect(screen.queryByText(t.examDone)).toBeNull()
})

it('014-41: una pista silenciada más de 3 s cuenta como cámara perdida; menos, no', async () => {
  const { user, rec } = mount(attempt())
  await startExam(user)
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
  try {
    act(() => tracks[0]!.fire('mute'))
    act(() => vi.advanceTimersByTime(2500))
    act(() => tracks[0]!.fire('unmute'))
    act(() => vi.advanceTimersByTime(2000))
    expect(rec.events.map((e) => e.kind)).not.toContain('CAMERA_LOST')
    act(() => tracks[0]!.fire('mute'))
    act(() => vi.advanceTimersByTime(3100))
  } finally {
    vi.useRealTimers()
  }
  await waitFor(() => expect(rec.events.map((e) => e.kind)).toContain('CAMERA_LOST'))
})

it('014-43: la marca de agua lleva nombre y código, no se lee ni captura clics', async () => {
  const { user } = mount(attempt())
  await startExam(user)
  const mark = screen.getByTestId('exam-watermark')
  expect(mark).toHaveAttribute('aria-hidden', 'true')
  expect(mark).toHaveTextContent('Ana Prueba · T-APP123')
  expect(mark.closest('.exam-secure')).toContainElement(screen.getByRole('textbox'))
})

it('014-44: copiar, cortar, pegar y el clic derecho se bloquean y registran; escribir sigue igual', async () => {
  const { user, rec } = mount(attempt())
  await startExam(user)
  const prompt = screen.getByText('Explica tu decisión de diseño')
  const editor = screen.getByRole('textbox')
  expect(fireEvent.copy(prompt)).toBe(false)
  expect(await screen.findByText(p.noCopy)).toBeInTheDocument()
  expect(fireEvent.paste(editor)).toBe(false)
  expect(fireEvent.contextMenu(prompt)).toBe(false)
  expect(await screen.findByText(p.noContextMenu)).toBeInTheDocument()
  await user.type(editor, 'Sigo escribiendo{backspace}')
  expect(editor).toHaveValue('Sigo escribiend')
  await waitFor(() =>
    expect(rec.events.map((e) => e.kind)).toEqual(
      expect.arrayContaining(['COPY_ATTEMPT', 'PASTE_ATTEMPT', 'CONTEXT_MENU']),
    ),
  )
})

it('014-44: Ctrl+P se bloquea y se registra PRINT_ATTEMPT; seleccionar el enunciado no está permitido', async () => {
  const { user, rec } = mount(attempt())
  await startExam(user)
  expect(fireEvent.keyDown(document, { key: 'p', ctrlKey: true })).toBe(false)
  expect(await screen.findByText(p.noPrint)).toBeInTheDocument()
  await waitFor(() => expect(rec.events.map((e) => e.kind)).toContain('PRINT_ATTEMPT'))
  const prompt = screen.getByText('Explica tu decisión de diseño')
  expect(fireEvent(prompt, new Event('selectstart', { bubbles: true, cancelable: true }))).toBe(
    false,
  )
  expect(
    fireEvent(
      screen.getByRole('textbox'),
      new Event('selectstart', { bubbles: true, cancelable: true }),
    ),
  ).toBe(true)
})

it('014-50: mirar a otro lado sostenido da un aviso amable, el evento START con su episodio y una foto de evento', async () => {
  const { user, rec } = mount(attempt())
  await startExam(user)
  sim.yaw = 60
  expect(await screen.findByText(p.notices.GAZE_AWAY, {}, { timeout: 5000 })).toBeInTheDocument()
  const start = rec.events.find((e) => e.kind === 'GAZE_AWAY')!
  expect(start.data).toMatchObject({ phase: 'START' })
  expect(start.data!.episodeId).toMatch(/^[0-9a-f-]{36}$/)
  expect(Object.keys(start.data!).sort()).toEqual(['episodeId', 'phase', 'sinceMs'])
  await waitFor(() =>
    expect(rec.snapshots).toContainEqual({ reason: 'EVENT', eventClientId: start.clientEventId }),
  )
  sim.yaw = 0
  await waitFor(
    () =>
      expect(rec.events).toContainEqual(
        expect.objectContaining({
          kind: 'GAZE_AWAY',
          data: expect.objectContaining({ phase: 'END', episodeId: start.data!.episodeId }),
        }),
      ),
    { timeout: 5000 },
  )
  // Los avisos de la cámara no cierran el examen (014-52).
  expect(screen.getByRole('textbox')).toBeInTheDocument()
})

it('014-41: al salir de la pantalla del examen se apaga la cámara y se liberan los modelos', async () => {
  const { user, router } = mount(attempt())
  await startExam(user)
  await act(() => router.navigate('/postulaciones/app-test'))
  await waitFor(() => expect(tracks[0]!.stop).toHaveBeenCalled())
  expect(sim.closed).toBeGreaterThan(0)
})

it('014-38: si start responde proctoring-required, se explica y el examen no se muestra', async () => {
  const { user } = mount(attempt(), { startStatus: 422 })
  await user.click(await screen.findByRole('button', { name: p.begin }))
  await user.click(await screen.findByRole('button', { name: p.enterAndStart }))
  expect(await screen.findByText(t.errors['proctoring-required'])).toBeInTheDocument()
  expect(screen.queryByText('Explica tu decisión de diseño')).toBeNull()
})
