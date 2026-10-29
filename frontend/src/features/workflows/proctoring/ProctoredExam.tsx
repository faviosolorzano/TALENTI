import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Button } from '../../../components/Button'
import { listDevices } from '../mediaPreferences'
import { t } from '../workflowLabels'
import { ActionError } from '../shared'
import type { Pose } from './policy'
import {
  CameraError,
  type CameraFailure,
  type ProctoringSession,
  type ProctoringView,
} from './session'
import { examDeviceSupported } from './useProctoring'
import './proctoring.css'

const p = t.proctoring

/** Vista de la cámara (propia o de comprobación). La imagen se queda en el equipo. */
function CameraVideo({ stream, label }: { stream: MediaStream | null; label?: string }) {
  const video = useRef<HTMLVideoElement>(null)
  useEffect(() => {
    const node = video.current
    if (node) node.srcObject = stream
    return () => {
      if (node) node.srcObject = null
    }
  }, [stream])
  return stream ? (
    <video ref={video} autoPlay muted playsInline aria-label={label} />
  ) : (
    <div className="exam-camera__empty" aria-hidden="true" />
  )
}

/** Vista pequeña propia en la esquina, con el rótulo "Tu cámara" (014-41). */
export function ExamCamera({ stream }: { stream: MediaStream | null }) {
  return (
    <figure className="exam-camera">
      <CameraVideo stream={stream} label={p.cameraLabel} />
      <figcaption className="exam-camera__label">{p.cameraLabel}</figcaption>
    </figure>
  )
}

/** Marca de agua repetida en diagonal, sin capturar clics ni ser leída (014-43). */
export function Watermark({ name, code }: { name: string; code: string }) {
  const text = `${name} · ${code}`
  return (
    <div className="exam-watermark" aria-hidden="true" data-testid="exam-watermark">
      <div className="exam-watermark__grid">
        {Array.from({ length: 60 }, (_, i) => (
          <span key={i}>{text}</span>
        ))}
      </div>
    </div>
  )
}

export type BlockedKind = 'COPY_ATTEMPT' | 'PASTE_ATTEMPT' | 'CONTEXT_MENU' | 'PRINT_ATTEMPT'

const editable = (target: EventTarget | null) =>
  target instanceof HTMLElement && Boolean(target.closest('textarea, input, [contenteditable]'))

/**
 * Área del examen con copiar, cortar, pegar, clic derecho, selección del enunciado e impresión bloqueados y
 * registrados (014-44). Escribir, borrar y moverse con el teclado en las respuestas sigue igual.
 */
export function SecureArea({
  children,
  watermark,
  onBlocked,
}: {
  children: ReactNode
  watermark?: { name: string; code: string } | null
  onBlocked: (kind: BlockedKind) => void
}) {
  const area = useRef<HTMLDivElement>(null)
  const latest = useRef(onBlocked)
  useEffect(() => {
    latest.current = onBlocked
  })
  useEffect(() => {
    const node = area.current
    let printedByKey = 0
    const select = (event: Event) => {
      if (!editable(event.target)) event.preventDefault()
    }
    const key = (event: KeyboardEvent) => {
      if ((event.ctrlKey || event.metaKey) && event.key.toLowerCase() === 'p') {
        event.preventDefault()
        printedByKey = Date.now()
        latest.current('PRINT_ATTEMPT')
      }
    }
    const beforePrint = () => {
      // Imprimir desde el menú no se puede impedir: el contenido se oculta con @media print.
      if (Date.now() - printedByKey > 1000) latest.current('PRINT_ATTEMPT')
    }
    node?.addEventListener('selectstart', select)
    document.addEventListener('keydown', key, true)
    window.addEventListener('beforeprint', beforePrint)
    return () => {
      node?.removeEventListener('selectstart', select)
      document.removeEventListener('keydown', key, true)
      window.removeEventListener('beforeprint', beforePrint)
    }
  }, [])
  const block = (kind: BlockedKind) => (event: { preventDefault: () => void }) => {
    event.preventDefault()
    onBlocked(kind)
  }
  return (
    <>
      <div
        ref={area}
        className="exam-secure"
        onCopy={block('COPY_ATTEMPT')}
        onCut={block('COPY_ATTEMPT')}
        onPaste={block('PASTE_ATTEMPT')}
        onContextMenu={block('CONTEXT_MENU')}
      >
        {children}
        {watermark && <Watermark name={watermark.name} code={watermark.code} />}
      </div>
      <p className="exam-print-note">{p.printHidden}</p>
    </>
  )
}

/** Lo que tapa las preguntas: cámara perdida o fuera de pantalla completa (014-41, 014-42). */
export function ProctoredCover({
  view,
  fullscreen,
  session,
  onReconnected,
}: {
  view: ProctoringView
  fullscreen: boolean
  session: ProctoringSession
  onReconnected: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState<CameraFailure | null>(null)
  const [devices, setDevices] = useState<{ id: string; label: string }[]>([])
  const [chosen, setChosen] = useState('')
  const [error, setError] = useState('')
  const cameraMissing = !view.stream
  if (!cameraMissing && fullscreen) return null
  async function reconnect() {
    setBusy(true)
    setFailure(null)
    try {
      await session.openCamera(chosen || undefined)
      onReconnected()
    } catch (err) {
      setFailure(err instanceof CameraError ? err.reason : 'busy')
      // "Reconectar" ofrece la lista de cámaras (E1.2).
      const list = await listDevices('camera')
      setDevices(list)
      setChosen((current) => current || list[0]?.id || '')
    } finally {
      setBusy(false)
    }
  }
  async function returnFullscreen() {
    setError('')
    try {
      await document.documentElement.requestFullscreen()
    } catch {
      setError(p.fullscreenFailed)
    }
  }
  return (
    <div className="exam-cover" role="alert">
      {cameraMissing ? (
        <>
          <p className="exam-cover__title">{view.cameraLost ? p.cameraLost : p.cameraNeeded}</p>
          <p>{p.timeContinues}</p>
          {failure && <CameraFailureMessage failure={failure} />}
          <DevicePick devices={devices} value={chosen} onChange={setChosen} disabled={busy} />
          <Button disabled={busy} onClick={() => void reconnect()}>
            {busy ? t.working : p.reconnect}
          </Button>
        </>
      ) : (
        <>
          <p className="exam-cover__title">{p.leftFullscreen}</p>
          <p>{p.timeContinues}</p>
          <ActionError message={error} />
          <Button onClick={() => void returnFullscreen()}>{p.returnFullscreen}</Button>
        </>
      )}
    </div>
  )
}

function CameraFailureMessage({ failure }: { failure: CameraFailure }) {
  if (failure === 'busy') return <ActionError message={p.cameraBusy} />
  return (
    <>
      <ActionError message={p.unsupported} />
      {failure === 'denied' && <p>{p.cameraDenied}</p>}
    </>
  )
}

function DevicePick({
  devices,
  value,
  onChange,
  disabled,
}: {
  devices: { id: string; label: string }[]
  value: string
  onChange: (id: string) => void
  disabled: boolean
}) {
  if (!devices.length) return null
  return (
    <label className="device-check__picker">
      <span>{p.cameraPick}</span>
      <select value={value} disabled={disabled} onChange={(e) => onChange(e.target.value)}>
        {devices.map((d) => (
          <option key={d.id} value={d.id}>
            {d.label}
          </option>
        ))}
      </select>
    </label>
  )
}

type Stage = 'idle' | 'camera' | 'loading' | 'calibrating' | 'ready' | 'starting'

/**
 * "Comenzar" con supervisión (014-38): cámara → modelos → calibración → pantalla completa → `start`. La pantalla
 * completa pide otro clic porque el navegador solo la concede justo después de una acción de la persona.
 */
export function ProctoredStart({
  session,
  view,
  onStart,
}: {
  session: ProctoringSession
  view: ProctoringView
  onStart: (request: {
    detection: 'AVAILABLE' | 'UNAVAILABLE'
    calibration: Pose | null
  }) => Promise<void>
}) {
  const [stage, setStage] = useState<Stage>('idle')
  const [failure, setFailure] = useState<CameraFailure | 'frames' | 'angle' | null>(null)
  const [error, setError] = useState('')
  const [devices, setDevices] = useState<{ id: string; label: string }[]>([])
  const [chosen, setChosen] = useState('')
  const [base, setBase] = useState<Pose | null>(null)
  const alive = useRef(true)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
    }
  }, [])
  async function runCalibration() {
    setFailure(null)
    setStage('calibrating')
    const result = await session.calibrate()
    if (!alive.current) return
    if (result.ok) {
      setBase(result.base)
      setStage('ready')
    } else {
      setFailure(result.reason)
      setStage('idle')
    }
  }
  async function begin() {
    setFailure(null)
    setError('')
    if (!examDeviceSupported()) {
      setFailure('unsupported')
      return
    }
    if (!session.hasCamera) {
      setStage('camera')
      try {
        await session.openCamera(chosen || undefined)
      } catch (err) {
        if (!alive.current) return
        const reason = err instanceof CameraError ? err.reason : 'busy'
        setFailure(reason)
        setStage('idle')
        if (reason === 'busy') {
          const list = await listDevices('camera')
          setDevices(list)
          setChosen((current) => current || list[0]?.id || '')
        }
        return
      }
    }
    if (!alive.current) return
    setStage('loading')
    const detection = await session.loadDetection()
    if (!alive.current) return
    if (detection === 'AVAILABLE') await runCalibration()
    else setStage('ready')
  }
  async function enter() {
    setError('')
    try {
      await document.documentElement.requestFullscreen()
    } catch {
      setError(p.fullscreenFailed)
      return
    }
    setStage('starting')
    try {
      await onStart({
        detection: view.detection === 'AVAILABLE' ? 'AVAILABLE' : 'UNAVAILABLE',
        calibration: base,
      })
    } finally {
      if (alive.current) setStage('ready')
    }
  }
  const working =
    stage === 'camera' || stage === 'loading' || stage === 'calibrating' || stage === 'starting'
  const status =
    stage === 'camera'
      ? p.stepCamera
      : stage === 'loading'
        ? p.stepLoading
        : stage === 'calibrating'
          ? p.stepCalibrating
          : stage === 'starting'
            ? p.stepStarting
            : stage === 'ready'
              ? view.detection === 'UNAVAILABLE'
                ? p.detectionUnavailable
                : p.ready
              : ''
  return (
    <section className="workflow__row" aria-busy={working}>
      <p>{p.beforeStart}</p>
      <p>{p.limits}</p>
      {view.stream && (
        <div className="exam-preview">
          <CameraVideo stream={view.stream} label={p.cameraLabel} />
        </div>
      )}
      <p role="status" className={stage === 'calibrating' ? 'exam-cover__title' : undefined}>
        {status}
      </p>
      {failure === 'frames' && <ActionError message={p.calibrationFrames} />}
      {failure === 'angle' && <ActionError message={p.calibrationAngle} />}
      {failure && failure !== 'frames' && failure !== 'angle' && (
        <CameraFailureMessage failure={failure} />
      )}
      <ActionError message={error} />
      <DevicePick devices={devices} value={chosen} onChange={setChosen} disabled={working} />
      {stage === 'ready' ? (
        <Button onClick={() => void enter()}>{p.enterAndStart}</Button>
      ) : failure === 'frames' || failure === 'angle' ? (
        <Button onClick={() => void runCalibration()}>{p.repeatCalibration}</Button>
      ) : (
        <Button disabled={working} onClick={() => void begin()}>
          {working ? t.working : failure ? p.retry : p.begin}
        </Button>
      )}
    </section>
  )
}
