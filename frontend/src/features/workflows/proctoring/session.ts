import { deviceConstraint, savePreferredDevice } from '../mediaPreferences'
import { analysisFrame, captureSnapshot } from './capture'
import { createMediaPipeDetector, type DetectorFactory, type ProctoringDetector } from './detector'
import {
  calibrate,
  createPolicyState,
  step,
  suspendPolicy,
  type CalibrationResult,
  type EpisodeEvent,
  type EpisodeKind,
  type PolicyState,
  type Pose,
  type ProctoringFrame,
} from './policy'
import type { ProctoringThresholds } from './thresholds'

/** Tipos de evento que manda el navegador por la supervisión (014-41, 014-44, 014-49, 014-51). */
export type ProctoringEventKind =
  | EpisodeKind
  | 'CAMERA_LOST'
  | 'CAMERA_RESTORED'
  | 'COPY_ATTEMPT'
  | 'PASTE_ATTEMPT'
  | 'CONTEXT_MENU'
  | 'PRINT_ATTEMPT'
  | 'DETECTION_UNAVAILABLE'

export interface EpisodeData {
  episodeId: string
  phase: 'START' | 'END'
  sinceMs?: number
  durationMs?: number
  faces?: number
}

export type SnapshotReason = 'START' | 'PERIODIC' | 'EVENT'

export interface ProctoringPorts {
  /** Envía un evento del intento; el `clientEventId` lo decide la sesión. */
  report: (kind: ProctoringEventKind, clientEventId: string, data?: EpisodeData) => void
  /** Sube una foto; debe rechazar si falla, para reintentar una vez. */
  upload: (
    image: Blob,
    reason: SnapshotReason,
    clientTakenAt: string,
    eventClientId?: string,
  ) => Promise<void>
}

export type CameraFailure = 'unsupported' | 'denied' | 'missing' | 'busy'

export class CameraError extends Error {
  readonly reason: CameraFailure
  constructor(reason: CameraFailure) {
    super(reason)
    this.reason = reason
  }
}

export interface ProctoringView {
  stream: MediaStream | null
  cameraLost: boolean
  detection: 'PENDING' | 'AVAILABLE' | 'UNAVAILABLE'
  /** Tipo del aviso pasajero en pantalla, si hay. */
  notice: EpisodeKind | null
  /** El ritmo bajó a 3 y 1 cuadros por segundo por lentitud del equipo. */
  degraded: boolean
}

const NOTICE_MS = 8000
const MUTE_LOST_MS = 3000
const COST_SAMPLES = 10

function cameraFailure(error: unknown): CameraFailure {
  if (error instanceof CameraError) return error.reason
  const name = error instanceof DOMException || error instanceof Error ? error.name : ''
  if (name === 'NotAllowedError' || name === 'SecurityError') return 'denied'
  if (name === 'NotFoundError' || name === 'OverconstrainedError') return 'missing'
  return 'busy'
}

/**
 * Supervisión de un intento de examen en el navegador: cámara, detección, decisión, avisos y fotos. No conoce React;
 * la pantalla se suscribe con `subscribe`/`view`. Todo lo que arranca se detiene en `dispose()`.
 */
export class ProctoringSession {
  private viewState: ProctoringView = {
    stream: null,
    cameraLost: false,
    detection: 'PENDING',
    notice: null,
    degraded: false,
  }
  private listeners = new Set<() => void>()
  private detector: ProctoringDetector | null = null
  private video: HTMLVideoElement | null = null
  private canvas: HTMLCanvasElement | null = null
  private policy: PolicyState | null = null
  private episodes = new Map<number, string>()
  private timers = new Set<ReturnType<typeof setTimeout>>()
  private faceTimer: ReturnType<typeof setTimeout> | null = null
  private objectTimer: ReturnType<typeof setTimeout> | null = null
  private snapshotTimer: ReturnType<typeof setInterval> | null = null
  private noticeTimer: ReturnType<typeof setTimeout> | null = null
  private muteTimer: ReturnType<typeof setTimeout> | null = null
  private costs: number[] = []
  private monitoring = false
  private disposed = false
  private detachTrack: (() => void) | null = null
  private readonly config: ProctoringThresholds
  private readonly snapshotIntervalMs: number
  private ports: ProctoringPorts
  private readonly detectorFactory: DetectorFactory
  private readonly onDeviceChange = () => this.checkDevice()
  private readonly onVisibility = () => {
    // Al volver de una pestaña suspendida el detector empieza de cero (E1.2); el hueco lo cubre SCREEN_LEFT.
    if (document.visibilityState === 'visible') this.resetPolicy()
  }

  constructor(options: {
    config: ProctoringThresholds
    snapshotIntervalMs: number
    ports?: ProctoringPorts
    detectorFactory?: DetectorFactory
  }) {
    this.config = options.config
    this.snapshotIntervalMs = options.snapshotIntervalMs
    this.ports = options.ports ?? { report: () => {}, upload: async () => {} }
    this.detectorFactory = options.detectorFactory ?? createMediaPipeDetector
  }

  /** Cambia las funciones de envío (la pantalla las renueva en cada render). */
  setPorts(ports: ProctoringPorts) {
    this.ports = ports
  }

  subscribe = (listener: () => void) => {
    this.listeners.add(listener)
    return () => this.listeners.delete(listener)
  }

  view = () => this.viewState

  private set(patch: Partial<ProctoringView>) {
    if (this.disposed) return
    this.viewState = { ...this.viewState, ...patch }
    this.listeners.forEach((listener) => listener())
  }

  get hasCamera() {
    return Boolean(this.viewState.stream) && !this.viewState.cameraLost
  }

  get isMonitoring() {
    return this.monitoring
  }

  get calibrated() {
    return this.policy !== null
  }

  /**
   * Enciende la cámara guardada en `talenti.media.camera` (o la predeterminada si falta o falla) y guarda la que
   * quedó en uso (014-38). Con `deviceId`, usa exactamente esa.
   */
  async openCamera(deviceId?: string): Promise<MediaStream> {
    if (!navigator.mediaDevices?.getUserMedia) throw new CameraError('unsupported')
    let stream: MediaStream
    try {
      const pick = deviceId ? { deviceId: { exact: deviceId } } : deviceConstraint('camera')
      const video = { width: { ideal: 640 }, height: { ideal: 480 }, ...pick }
      stream = await navigator.mediaDevices.getUserMedia({
        video: deviceId ? video : { facingMode: 'user', ...video },
        audio: false,
      })
    } catch (error) {
      throw new CameraError(cameraFailure(error))
    }
    if (this.disposed) {
      stream.getTracks().forEach((track) => track.stop())
      throw new CameraError('busy')
    }
    this.releaseCamera()
    const track = stream.getVideoTracks?.()[0] ?? stream.getTracks()[0]
    const inUse = track?.getSettings?.().deviceId
    if (inUse) savePreferredDevice('camera', inUse)
    this.attachTrack(track)
    this.video = document.createElement('video')
    this.video.muted = true
    this.video.playsInline = true
    this.video.srcObject = stream
    try {
      await this.video.play()
    } catch {
      // El video interno solo alimenta el análisis y las fotos; si no reproduce, no hay cuadros.
    }
    const wasLost = this.viewState.cameraLost
    this.set({ stream, cameraLost: false })
    if (wasLost && this.monitoring) {
      this.ports.report('CAMERA_RESTORED', crypto.randomUUID())
      this.resetPolicy()
    }
    return stream
  }

  private attachTrack(track: MediaStreamTrack | undefined) {
    if (!track) return
    const ended = () => this.loseCamera()
    const mute = () => {
      this.clearMute()
      this.muteTimer = setTimeout(() => this.loseCamera(), MUTE_LOST_MS)
    }
    const unmute = () => this.clearMute()
    track.addEventListener?.('ended', ended)
    track.addEventListener?.('mute', mute)
    track.addEventListener?.('unmute', unmute)
    navigator.mediaDevices?.addEventListener?.('devicechange', this.onDeviceChange)
    this.detachTrack = () => {
      track.removeEventListener?.('ended', ended)
      track.removeEventListener?.('mute', mute)
      track.removeEventListener?.('unmute', unmute)
      navigator.mediaDevices?.removeEventListener?.('devicechange', this.onDeviceChange)
    }
  }

  private clearMute() {
    if (this.muteTimer) clearTimeout(this.muteTimer)
    this.muteTimer = null
  }

  /**
   * Al cambiar los dispositivos, la cámara se da por perdida solo si su pista terminó. No se compara con la lista de
   * `enumerateDevices`: Chrome cambia los identificadores tras dar permiso y eso cerraba una cámara sana.
   */
  private checkDevice() {
    const track = this.viewState.stream?.getVideoTracks?.()[0]
    if (track?.readyState === 'ended') this.loseCamera()
  }

  /** Cámara perdida (014-41): se registra, se tapan las preguntas y nunca cierra el examen. */
  loseCamera() {
    if (this.disposed || this.viewState.cameraLost || !this.viewState.stream) return
    this.releaseCamera()
    this.set({ cameraLost: true, stream: null })
    if (this.monitoring) {
      this.ports.report('CAMERA_LOST', crypto.randomUUID())
      this.flush(this.policy ? suspendPolicy(this.policy, performance.now()) : null)
    }
  }

  private releaseCamera() {
    this.clearMute()
    this.detachTrack?.()
    this.detachTrack = null
    this.viewState.stream?.getTracks().forEach((track) => track.stop())
    if (this.video) this.video.srcObject = null
    this.video = null
  }

  /** Carga los modelos; si no se puede, la detección queda `UNAVAILABLE` y el examen sigue con cámara y fotos. */
  async loadDetection(): Promise<'AVAILABLE' | 'UNAVAILABLE'> {
    if (this.viewState.detection === 'AVAILABLE' && this.detector) return 'AVAILABLE'
    const detector = this.detectorFactory(this.config)
    try {
      await detector.load()
    } catch {
      detector.close()
      this.set({ detection: 'UNAVAILABLE' })
      return 'UNAVAILABLE'
    }
    if (this.disposed) {
      detector.close()
      return 'UNAVAILABLE'
    }
    this.detector = detector
    this.set({ detection: 'AVAILABLE' })
    return 'AVAILABLE'
  }

  private analysisSource(): TexImageSource | null {
    if (!this.video) return null
    this.canvas ??= document.createElement('canvas')
    return analysisFrame(this.video, this.canvas)
  }

  /** Calibración de `calibrationMs` a `faceFps` (014-47). Si sale bien, fija la base de la decisión. */
  async calibrate(): Promise<CalibrationResult> {
    const frames: ProctoringFrame[] = []
    const interval = 1000 / this.config.faceFps
    const until = performance.now() + this.config.calibrationMs
    while (performance.now() < until && !this.disposed) {
      await new Promise<void>((resolve) => this.later(resolve, interval))
      const source = this.analysisSource()
      if (source && this.detector) {
        try {
          frames.push({ faces: this.detector.detectFaces(source, performance.now()) })
        } catch {
          // Un cuadro que falla no cuenta.
        }
      }
    }
    const result = calibrate(frames, this.config)
    if (result.ok) this.useBase(result.base)
    return result
  }

  /** Fija la base sin calibrar (p. ej. la guardada en el intento al reanudar). */
  useBase(base: Pose) {
    this.policy = createPolicyState(base)
  }

  /**
   * Empieza la supervisión de un intento ya iniciado: foto inicial, fotos periódicas, análisis y, si no hay
   * detección, el hecho `DETECTION_UNAVAILABLE` (014-40).
   */
  beginMonitoring() {
    if (this.monitoring || this.disposed) return
    this.monitoring = true
    if (this.viewState.detection === 'UNAVAILABLE')
      this.ports.report('DETECTION_UNAVAILABLE', crypto.randomUUID())
    void this.snapshot('START')
    this.snapshotTimer = setInterval(() => void this.snapshot('PERIODIC'), this.snapshotIntervalMs)
    document.addEventListener('visibilitychange', this.onVisibility)
    this.scheduleFaces()
    this.scheduleObjects()
  }

  private get fps() {
    return this.viewState.degraded
      ? { face: this.config.degradedFaceFps, object: this.config.degradedObjectFps }
      : { face: this.config.faceFps, object: this.config.objectFps }
  }

  private scheduleFaces() {
    if (!this.monitoring) return
    this.faceTimer = setTimeout(() => {
      this.analyze('faces')
      this.scheduleFaces()
    }, 1000 / this.fps.face)
  }

  private scheduleObjects() {
    if (!this.monitoring) return
    this.objectTimer = setTimeout(() => {
      this.analyze('objects')
      this.scheduleObjects()
    }, 1000 / this.fps.object)
  }

  private analyze(what: 'faces' | 'objects') {
    if (!this.detector || !this.policy || this.viewState.cameraLost) return
    if (document.visibilityState === 'hidden') return
    const source = this.analysisSource()
    if (!source) return
    const started = performance.now()
    let frame: ProctoringFrame
    try {
      frame =
        what === 'faces'
          ? { faces: this.detector.detectFaces(source, started) }
          : { phoneScore: this.detector.detectPhone(source, started) }
    } catch {
      return
    }
    const cost = performance.now() - started
    if (what === 'faces') this.trackCost(cost)
    this.flush(step(this.policy, frame, this.config, started))
  }

  /** Si el tiempo medio por cuadro pasa del presupuesto, baja a 3 y 1 cuadros por segundo (014-46). */
  private trackCost(cost: number) {
    this.costs.push(cost)
    if (this.costs.length > COST_SAMPLES) this.costs.shift()
    if (this.viewState.degraded || this.costs.length < COST_SAMPLES) return
    const average = this.costs.reduce((sum, value) => sum + value, 0) / this.costs.length
    if (average > this.config.frameBudgetMs) this.set({ degraded: true })
  }

  private resetPolicy() {
    if (!this.policy) return
    this.flush(suspendPolicy(this.policy, performance.now()))
  }

  private flush(result: { state: PolicyState; events: EpisodeEvent[] } | null) {
    if (!result) return
    this.policy = result.state
    for (const event of result.events) this.emit(event)
  }

  private emit(event: EpisodeEvent) {
    if (!this.monitoring) return
    let episodeId = this.episodes.get(event.seq)
    if (!episodeId) {
      episodeId = crypto.randomUUID()
      this.episodes.set(event.seq, episodeId)
    }
    if (event.phase === 'END') this.episodes.delete(event.seq)
    const clientEventId = crypto.randomUUID()
    const data: EpisodeData = { episodeId, phase: event.phase }
    if (event.sinceMs !== undefined) data.sinceMs = Math.min(3_600_000, event.sinceMs)
    if (event.durationMs !== undefined) data.durationMs = Math.min(3_600_000, event.durationMs)
    if (event.faces !== undefined) data.faces = event.faces
    this.ports.report(event.kind, clientEventId, data)
    if (event.phase === 'START' && event.notify) {
      // El aviso y la foto de evento solo salen con la espera entre avisos cumplida (014-50).
      this.showNotice(event.kind)
      void this.snapshot('EVENT', clientEventId)
    }
  }

  private showNotice(kind: EpisodeKind) {
    if (this.noticeTimer) clearTimeout(this.noticeTimer)
    this.set({ notice: kind })
    this.noticeTimer = setTimeout(() => this.set({ notice: null }), NOTICE_MS)
  }

  dismissNotice() {
    this.set({ notice: null })
  }

  /** Foto del flujo de la cámara (014-53). Si la subida falla, reintenta una vez y la descarta. */
  async snapshot(reason: SnapshotReason, eventClientId?: string) {
    if (!this.monitoring || !this.video || this.viewState.cameraLost) return
    const takenAt = new Date().toISOString()
    let image: Blob | null
    try {
      image = await captureSnapshot(this.video)
    } catch {
      image = null
    }
    if (!image || this.disposed) return
    for (let attempt = 0; attempt < 2; attempt++) {
      try {
        await this.ports.upload(image, reason, takenAt, eventClientId)
        return
      } catch {
        if (this.disposed) return
      }
    }
  }

  private later(callback: () => void, ms: number) {
    const timer = setTimeout(() => {
      this.timers.delete(timer)
      callback()
    }, ms)
    this.timers.add(timer)
  }

  /** Detiene análisis y fotos (al cerrar el intento); la cámara sigue hasta `dispose()`. */
  stopMonitoring() {
    this.monitoring = false
    if (this.faceTimer) clearTimeout(this.faceTimer)
    if (this.objectTimer) clearTimeout(this.objectTimer)
    if (this.snapshotTimer) clearInterval(this.snapshotTimer)
    this.faceTimer = this.objectTimer = this.snapshotTimer = null
    document.removeEventListener('visibilitychange', this.onVisibility)
  }

  /** Apaga todo: cámara, modelos y temporizadores. */
  dispose() {
    this.stopMonitoring()
    this.releaseCamera()
    if (this.noticeTimer) clearTimeout(this.noticeTimer)
    this.timers.forEach((timer) => clearTimeout(timer))
    this.timers.clear()
    this.detector?.close()
    this.detector = null
    this.disposed = true
    this.viewState = { ...this.viewState, stream: null }
  }

  /** Vuelve a dejar la sesión utilizable tras `dispose()` (el doble montaje del modo estricto de React). */
  activate() {
    this.disposed = false
  }
}
