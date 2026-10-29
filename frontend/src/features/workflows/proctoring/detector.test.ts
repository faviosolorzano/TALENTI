import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  createMediaPipeDetector,
  DetectionUnavailableError,
  FACE_MODEL_PATH,
  MEDIAPIPE_WASM_PATH,
  OBJECT_MODEL_PATH,
  poseFromMatrix,
} from './detector'
import { DEFAULT_THRESHOLDS } from './thresholds'

const vision = vi.hoisted(() => ({
  faceOptions: [] as Record<string, unknown>[],
  objectOptions: [] as Record<string, unknown>[],
  failDelegates: [] as string[],
  filesetPath: '',
}))
vi.mock('@mediapipe/tasks-vision', () => {
  const failing = (options: { baseOptions: { delegate: string } }) =>
    vision.failDelegates.includes(options.baseOptions.delegate)
  return {
    FilesetResolver: {
      forVisionTasks: async (path: string) => {
        vision.filesetPath = path
        return {}
      },
    },
    FaceLandmarker: {
      createFromOptions: async (_files: unknown, options: Record<string, unknown>) => {
        vision.faceOptions.push(options)
        if (failing(options as never)) throw new Error('gpu')
        return {
          close: vi.fn(),
          detectForVideo: () => ({
            facialTransformationMatrixes: [{ rows: 4, columns: 4, data: rotation(20, 0) }],
          }),
        }
      },
    },
    ObjectDetector: {
      createFromOptions: async (_files: unknown, options: Record<string, unknown>) => {
        vision.objectOptions.push(options)
        if (failing(options as never)) throw new Error('gpu')
        return {
          close: vi.fn(),
          detectForVideo: () => ({
            detections: [{ categories: [{ categoryName: 'cell phone', score: 0.8 }] }],
          }),
        }
      },
    },
  }
})

/** Matriz 4×4 por columnas con giro de yaw y luego pitch (grados), como la de MediaPipe. */
function rotation(yawDeg: number, pitchDeg: number): number[] {
  const y = (yawDeg * Math.PI) / 180
  const x = (pitchDeg * Math.PI) / 180
  // R = Ry(y) · Rx(x)
  const r = [
    [Math.cos(y), Math.sin(y) * Math.sin(x), Math.sin(y) * Math.cos(x)],
    [0, Math.cos(x), -Math.sin(x)],
    [-Math.sin(y), Math.cos(y) * Math.sin(x), Math.cos(y) * Math.cos(x)],
  ]
  const data = new Array(16).fill(0)
  for (let row = 0; row < 3; row++)
    for (let col = 0; col < 3; col++) data[col * 4 + row] = r[row]![col]
  data[15] = 1
  return data
}

beforeEach(() => {
  vision.faceOptions = []
  vision.objectOptions = []
  vision.failDelegates = []
})

describe('014-45: adaptador MediaPipe', () => {
  it('usa Face Landmarker con 2 rostros, matrices y sin blendshapes, y solo "cell phone"', async () => {
    const detector = createMediaPipeDetector(DEFAULT_THRESHOLDS)
    await detector.load()
    expect(vision.filesetPath).toBe(MEDIAPIPE_WASM_PATH)
    expect(vision.faceOptions[0]).toMatchObject({
      numFaces: 2,
      outputFaceBlendshapes: false,
      outputFacialTransformationMatrixes: true,
      runningMode: 'VIDEO',
      baseOptions: { modelAssetPath: FACE_MODEL_PATH, delegate: 'GPU' },
    })
    expect(vision.objectOptions[0]).toMatchObject({
      categoryAllowlist: ['cell phone'],
      baseOptions: { modelAssetPath: OBJECT_MODEL_PATH },
    })
    const source = {} as TexImageSource
    const [face] = detector.detectFaces(source, 100)
    expect(face!.yawDeg).toBeCloseTo(20, 5)
    expect(detector.detectPhone(source, 100)).toBe(0.8)
  })

  it('los modelos y el WASM se sirven desde el propio frontend, sin CDN', () => {
    for (const path of [MEDIAPIPE_WASM_PATH, FACE_MODEL_PATH, OBJECT_MODEL_PATH])
      expect(path).toMatch(/^\/(mediapipe|models)/)
  })

  it('014-40: si la GPU falla reintenta con CPU', async () => {
    vision.failDelegates = ['GPU']
    await createMediaPipeDetector(DEFAULT_THRESHOLDS).load()
    expect(vision.faceOptions.map((o) => (o.baseOptions as { delegate: string }).delegate)).toEqual(
      ['GPU', 'CPU'],
    )
  })

  it('014-40: si GPU y CPU fallan, la carga termina en DetectionUnavailableError', async () => {
    vision.failDelegates = ['GPU', 'CPU']
    await expect(createMediaPipeDetector(DEFAULT_THRESHOLDS).load()).rejects.toBeInstanceOf(
      DetectionUnavailableError,
    )
  })
})

describe('014-45: orientación desde la matriz', () => {
  it('yaw y pitch salen de la rotación; pitch positivo es mirar hacia arriba', () => {
    const turned = poseFromMatrix(rotation(-25, 0))
    expect(turned.yawDeg).toBeCloseTo(-25, 5)
    expect(turned.pitchDeg).toBeCloseTo(0, 5)
    // Con Rx positivo el frente baja; la convención de la spec lo da como pitch negativo.
    expect(poseFromMatrix(rotation(0, 15)).pitchDeg).toBeCloseTo(-15, 5)
  })
})
