import { useCallback, useEffect, useRef, useState } from 'react'
import { Button } from '../../components/Button'
import { Dialog } from '../../components/Dialog'
import { es } from '../../i18n/es'
import './doraCall.css'
import { deviceConstraint, listDevices, savePreferredDevice } from './mediaPreferences'
const c = es.doraCall
function useDevice(kind: 'camera' | 'microphone') {
  const [stream, setStream] = useState<MediaStream | null>(null)
  const [pending, setPending] = useState(false)
  const [error, setError] = useState('')
  const [devices, setDevices] = useState<{ id: string; label: string }[]>([])
  const [selected, setSelected] = useState('')
  const current = useRef<MediaStream | null>(null)
  const generation = useRef(0)
  const mounted = useRef(false)
  const waiting = useRef(false)
  const release = useCallback(() => {
    generation.current++
    current.current?.getTracks().forEach((track) => track.stop())
  }, [])
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
      release()
    }
  }, [release])
  function stop() {
    generation.current++
    waiting.current = false
    current.current?.getTracks().forEach((t) => t.stop())
    current.current = null
    setStream(null)
    setPending(false)
  }
  async function start(chosen?: string) {
    if (waiting.current || current.current) return
    const request = ++generation.current
    waiting.current = true
    setPending(true)
    setError('')
    try {
      // Una elección explícita usa ese dispositivo; si no, el guardado (o el predeterminado del sistema).
      const pick = chosen ? { deviceId: { exact: chosen } } : deviceConstraint(kind)
      const value = await navigator.mediaDevices.getUserMedia(
        kind === 'camera'
          ? { video: chosen ? pick : { facingMode: 'user', ...pick }, audio: false }
          : { audio: Object.keys(pick).length ? pick : true, video: false },
      )
      if (!mounted.current || request !== generation.current) {
        value.getTracks().forEach((t) => t.stop())
        return
      }
      current.current = value
      setStream(value)
      const inUse = value.getTracks()[0]?.getSettings?.().deviceId ?? ''
      setSelected(inUse)
      void listDevices(kind).then((list) => {
        if (mounted.current && request === generation.current) setDevices(list)
      })
    } catch (error) {
      if (mounted.current && request === generation.current)
        setError(
          error instanceof DOMException && ['NotAllowedError', 'SecurityError'].includes(error.name)
            ? c.mediaDenied
            : error instanceof DOMException && error.name === 'NotFoundError'
              ? c.mediaMissing
              : c.mediaError,
        )
    } finally {
      if (mounted.current && request === generation.current) {
        waiting.current = false
        setPending(false)
      }
    }
  }
  /** Cambia a otro dispositivo y lo recuerda para DORA y el examen. */
  function choose(deviceId: string) {
    savePreferredDevice(kind, deviceId)
    stop()
    void start(deviceId)
  }
  return { stream, pending, error, start, stop, devices, selected, choose }
}
export function DeviceCheck({ disabled = false }: { disabled?: boolean }) {
  const [open, setOpen] = useState(false)
  return (
    <>
      <Button variant="quiet" disabled={disabled} onClick={() => setOpen(true)}>
        {c.deviceCheck}
      </Button>
      {open && (
        <Dialog title={c.deviceCheck} onClose={() => setOpen(false)}>
          <DeviceCheckBody />
          <div className="device-check__done">
            <Button onClick={() => setOpen(false)}>{c.close}</Button>
          </div>
        </Dialog>
      )}
    </>
  )
}
export function DeviceCheckBody() {
  const camera = useDevice('camera')
  const mic = useDevice('microphone')
  const video = useRef<HTMLVideoElement>(null)
  const [tonePlayed, setTonePlayed] = useState(false)
  const [heard, setHeard] = useState(false)
  const [soundError, setSoundError] = useState(false)
  const sound = useRef<AudioContext | null>(null)
  const alive = useRef(false)
  useEffect(() => {
    alive.current = true
    return () => {
      alive.current = false
      if (sound.current?.state !== 'closed') void sound.current?.close().catch(() => {})
    }
  }, [])
  useEffect(() => {
    const node = video.current
    if (node) node.srcObject = camera.stream
    return () => {
      if (node) node.srcObject = null
    }
  }, [camera.stream])
  async function playTone() {
    setHeard(false)
    setTonePlayed(false)
    setSoundError(false)
    try {
      if (sound.current && sound.current.state !== 'closed') await sound.current.close()
      const context = new AudioContext()
      sound.current = context
      await context.resume()
      if (!alive.current) {
        if (context.state !== 'closed') await context.close()
        return
      }
      const oscillator = context.createOscillator()
      const gain = context.createGain()
      oscillator.frequency.value = 440
      gain.gain.value = 0.08
      oscillator.connect(gain)
      gain.connect(context.destination)
      oscillator.onended = () => {
        if (context.state !== 'closed') void context.close().catch(() => {})
      }
      oscillator.start()
      oscillator.stop(context.currentTime + 0.4)
      setTonePlayed(true)
    } catch {
      if (alive.current) setSoundError(true)
    }
  }
  return (
    <div className="device-check">
      <p>{c.devicePrivacy}</p>
      <section>
        <h3>{c.camera}</h3>
        <div className="device-check__preview">
          <video ref={video} autoPlay muted playsInline hidden={!camera.stream} />
          {!camera.stream && <span>{c.cameraOff}</span>}
        </div>
        {camera.stream && <p role="status">{c.cameraOn}</p>}
        <Button
          disabled={camera.pending}
          variant="quiet"
          onClick={() => (camera.stream ? camera.stop() : void camera.start())}
        >
          {camera.pending ? c.pending : camera.stream ? c.cameraStop : c.cameraStart}
        </Button>
        <DevicePicker label={c.cameraPick} device={camera} />
        {camera.pending && (
          <Button variant="quiet" onClick={camera.stop}>
            {c.stop}
          </Button>
        )}
        {camera.error && <p role="alert">{camera.error}</p>}
      </section>
      <section>
        <h3>{c.microphone}</h3>
        <p>{c.micPrompt}</p>
        <MicMeter key={mic.stream?.id ?? (mic.stream ? 'active' : 'off')} stream={mic.stream} />
        <Button
          disabled={mic.pending}
          variant="quiet"
          onClick={() => (mic.stream ? mic.stop() : void mic.start())}
        >
          {mic.pending ? c.pending : mic.stream ? c.micStop : c.micStart}
        </Button>
        <DevicePicker label={c.micPick} device={mic} />
        {mic.pending && (
          <Button variant="quiet" onClick={mic.stop}>
            {c.stop}
          </Button>
        )}
        {mic.error && <p role="alert">{mic.error}</p>}
      </section>
      <section>
        <h3>{c.speaker}</h3>
        <Button variant="quiet" onClick={() => void playTone()}>
          {c.tone}
        </Button>
        {tonePlayed && (
          <Button variant="quiet" onClick={() => setHeard(true)}>
            {c.heard}
          </Button>
        )}
        <p role="status">{heard ? c.soundConfirmed : c.soundHelp}</p>
        {soundError && <p role="alert">{c.mediaError}</p>}
      </section>
    </div>
  )
}

/** Lista para elegir el dispositivo; aparece cuando ya hay permiso y más de una opción. */
function DevicePicker({
  label,
  device,
}: {
  label: string
  device: {
    devices: { id: string; label: string }[]
    selected: string
    pending: boolean
    choose: (id: string) => void
  }
}) {
  if (device.devices.length < 2) return null
  return (
    <label className="device-check__picker">
      <span>{label}</span>
      <select
        value={device.selected}
        disabled={device.pending}
        onChange={(event) => device.choose(event.target.value)}
      >
        {device.devices.map((d) => (
          <option key={d.id} value={d.id}>
            {d.label}
          </option>
        ))}
      </select>
    </label>
  )
}

function MicMeter({ stream }: { stream: MediaStream | null }) {
  const [level, setLevel] = useState(0)
  const [detected, setDetected] = useState(false)
  const [meterError, setMeterError] = useState(false)
  useEffect(() => {
    if (!stream) return
    let context: AudioContext | undefined
    let source: MediaStreamAudioSourceNode | undefined
    let frame = 0
    let active = true
    void Promise.resolve()
      .then(() => {
        if (!active) return
        context = new AudioContext()
        source = context.createMediaStreamSource(stream)
        const analyser = context.createAnalyser()
        analyser.fftSize = 256
        source.connect(analyser)
        const samples = new Uint8Array(analyser.fftSize)
        const read = () => {
          if (!active) return
          analyser.getByteTimeDomainData(samples)
          const volume = Math.min(
            1,
            Math.sqrt(
              samples.reduce((sum, v) => sum + ((v - 128) / 128) ** 2, 0) / samples.length,
            ) * 4,
          )
          setLevel(volume)
          if (volume > 0.04) setDetected(true)
          frame = requestAnimationFrame(read)
        }
        void context
          .resume()
          .then(() => {
            if (active) read()
          })
          .catch(() => {
            if (active) setMeterError(true)
          })
      })
      .catch(() => {
        if (active) setMeterError(true)
      })
    return () => {
      active = false
      cancelAnimationFrame(frame)
      source?.disconnect()
      if (context && context.state !== 'closed') void context.close().catch(() => {})
    }
  }, [stream])
  return (
    <>
      <meter min={0} max={1} value={level} aria-label={c.micLevel} />
      <p role="status">
        {stream ? (meterError ? c.meterUnavailable : detected ? c.signal : c.micReady) : ''}
      </p>
    </>
  )
}
