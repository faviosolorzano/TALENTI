import type { FaceLandmarker, ObjectDetector } from '@mediapipe/tasks-vision'
import type { FaceObservation } from './policy'
import type { ProctoringThresholds } from './thresholds'

/**
 * Detector de la supervisión del examen (014-45). El análisis ocurre en el equipo: la imagen nunca sale de aquí.
 * Solo devuelve presencia, número de rostros con su orientación y la confianza de "cell phone".
 */
export interface ProctoringDetector {
  /** Carga los modelos. Falla con `DetectionUnavailableError` si no se pueden usar (red, GPU y CPU). */
  load(): Promise<void>
  /** Rostros del cuadro actual de `source`, con `at` en ms monótonos (`performance.now()`). */
  detectFaces(source: TexImageSource, at: number): FaceObservation[]
  /** Mayor confianza de "cell phone" en el cuadro (0 si no hay). */
  detectPhone(source: TexImageSource, at: number): number
  /** Libera los modelos. */
  close(): void
}

export class DetectionUnavailableError extends Error {
  constructor(cause?: unknown) {
    super('DETECTION_UNAVAILABLE', { cause })
    this.name = 'DetectionUnavailableError'
  }
}

export type DetectorFactory = (config: ProctoringThresholds) => ProctoringDetector

/** Rutas propias del frontend: sin CDN en tiempo de ejecución (014-45). */
export const MEDIAPIPE_WASM_PATH = '/mediapipe'
export const FACE_MODEL_PATH = '/models/face_landmarker.task'
export const OBJECT_MODEL_PATH = '/models/efficientdet_lite0.tflite'

/**
 * Signo de pitch: con la matriz de MediaPipe, mirar hacia arriba da un ángulo negativo; se invierte para que
 * "arriba" sea positivo como en la spec. Si la prueba manual muestra lo contrario, se cambia solo esta constante.
 */
const PITCH_SIGN = -1

/**
 * Yaw y pitch (grados) desde la matriz de transformación facial 4×4 de MediaPipe (orden por columnas). Solo la
 * rotación: nada de forma, expresión ni identidad.
 */
export function poseFromMatrix(data: ArrayLike<number>): { yawDeg: number; pitchDeg: number } {
  // Elemento (fila r, columna c) = data[c * 4 + r].
  const r02 = data[8]!
  const r12 = data[9]!
  const r22 = data[10]!
  const toDeg = 180 / Math.PI
  const yaw = Math.atan2(r02, r22) * toDeg
  const pitch = Math.asin(Math.max(-1, Math.min(1, -r12))) * toDeg
  return { yawDeg: yaw, pitchDeg: PITCH_SIGN * pitch }
}

/**
 * Adaptador MediaPipe Tasks Vision: Face Landmarker (2 rostros, matrices de transformación, **sin blendshapes**) y
 * Object Detector EfficientDet-Lite0 solo con "cell phone". Se carga con `import()` para no pesar en el resto de la
 * web. Si la GPU falla, reintenta con CPU; si también falla, `DetectionUnavailableError` (014-40).
 */
export function createMediaPipeDetector(config: ProctoringThresholds): ProctoringDetector {
  let face: FaceLandmarker | null = null
  let objects: ObjectDetector | null = null
  let lastFaceAt = -1
  let lastObjectAt = -1
  // MediaPipe exige marcas de tiempo estrictamente crecientes por tarea.
  const stamp = (at: number, last: number) => Math.max(Math.round(at), last + 1)
  return {
    async load() {
      try {
        const vision = await import('@mediapipe/tasks-vision')
        const files = await vision.FilesetResolver.forVisionTasks(MEDIAPIPE_WASM_PATH)
        const build = async (delegate: 'GPU' | 'CPU') => {
          const landmarker = await vision.FaceLandmarker.createFromOptions(files, {
            baseOptions: { modelAssetPath: FACE_MODEL_PATH, delegate },
            runningMode: 'VIDEO',
            numFaces: 2,
            outputFaceBlendshapes: false,
            outputFacialTransformationMatrixes: true,
            minFaceDetectionConfidence: config.faceScore,
            minFacePresenceConfidence: config.faceScore,
          })
          const detector = await vision.ObjectDetector.createFromOptions(files, {
            baseOptions: { modelAssetPath: OBJECT_MODEL_PATH, delegate },
            runningMode: 'VIDEO',
            categoryAllowlist: ['cell phone'],
            scoreThreshold: Math.min(0.3, config.phoneScore),
            maxResults: 3,
          })
          return { landmarker, detector }
        }
        let built
        try {
          built = await build('GPU')
        } catch {
          built = await build('CPU')
        }
        face = built.landmarker
        objects = built.detector
      } catch (error) {
        this.close()
        throw new DetectionUnavailableError(error)
      }
    },
    detectFaces(source, at) {
      if (!face) return []
      lastFaceAt = stamp(at, lastFaceAt)
      const result = face.detectForVideo(source, lastFaceAt)
      // Face Landmarker no entrega un puntaje por rostro: solo devuelve los que superan `faceScore`.
      return (result.facialTransformationMatrixes ?? []).map((matrix) => ({
        ...poseFromMatrix(matrix.data),
        score: 1,
      }))
    },
    detectPhone(source, at) {
      if (!objects) return 0
      lastObjectAt = stamp(at, lastObjectAt)
      const result = objects.detectForVideo(source, lastObjectAt)
      return result.detections.reduce(
        (best, detection) =>
          Math.max(
            best,
            ...detection.categories
              .filter((c) => c.categoryName === 'cell phone')
              .map((c) => c.score),
          ),
        0,
      )
    },
    close() {
      face?.close()
      objects?.close()
      face = null
      objects = null
    },
  }
}

/** Un cuadro programado para el detector simulado, según el tiempo desde que se cargó. */
export interface SimulatedFrame {
  faces: FaceObservation[]
  phoneScore?: number
}

/**
 * Detector simulado (014-45): reproduce una secuencia de cuadros (pose, rostros, objetos) para pruebas. `script`
 * recibe los ms transcurridos desde `load()` y devuelve lo que "ve" la cámara.
 */
export function createSimulatedDetector(
  script: (elapsedMs: number) => SimulatedFrame = () => ({
    faces: [{ yawDeg: 0, pitchDeg: 0, score: 0.95 }],
  }),
  options: { fail?: boolean; costMs?: number } = {},
): ProctoringDetector & { loaded: boolean; closed: boolean } {
  let origin = 0
  const busy = () => {
    if (!options.costMs) return
    const until = performance.now() + options.costMs
    while (performance.now() < until) {
      // Simula un equipo lento para probar la bajada de ritmo.
    }
  }
  return {
    loaded: false,
    closed: false,
    async load() {
      if (options.fail) throw new DetectionUnavailableError()
      origin = performance.now()
      this.loaded = true
    },
    detectFaces(_source, at) {
      busy()
      return script(at - origin).faces
    },
    detectPhone(_source, at) {
      return script(at - origin).phoneScore ?? 0
    },
    close() {
      this.closed = true
    },
  }
}
