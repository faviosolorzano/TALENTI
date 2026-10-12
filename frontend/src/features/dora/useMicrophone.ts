import { useCallback, useEffect, useRef, useState } from 'react'
import { levelFromTimeDomain } from './micLevel'

export type MicrophoneStatus = 'off' | 'on' | 'failed'

interface AudioResources {
  stream: MediaStream
  context: AudioContext
  analyser: AnalyserNode
  bytes: Uint8Array<ArrayBuffer>
}

type AudioContextConstructor = typeof AudioContext

function audioContextConstructor(): AudioContextConstructor | undefined {
  const legacy = window as unknown as { webkitAudioContext?: AudioContextConstructor }
  return window.AudioContext ?? legacy.webkitAudioContext
}

function release(resources: AudioResources | null): void {
  if (!resources) return
  resources.stream.getTracks().forEach((track) => track.stop())
  void resources.context.close().catch(() => {})
}

/**
 * Optional microphone for the orb demo (003-21, 003-22). The browser asks for permission only
 * when `start` is called. The audio only feeds an `AnalyserNode` to measure its volume: it is
 * never recorded, sent or stored. The tracks and the audio context are released on `stop`,
 * and when the component unmounts.
 */
export function useMicrophone() {
  const [status, setStatus] = useState<MicrophoneStatus>('off')
  const resources = useRef<AudioResources | null>(null)
  const requestId = useRef(0)

  const stop = useCallback(() => {
    requestId.current += 1
    release(resources.current)
    resources.current = null
    setStatus('off')
  }, [])

  const start = useCallback(async (): Promise<boolean> => {
    if (resources.current) return true
    const id = ++requestId.current
    let stream: MediaStream | null = null
    try {
      if (!navigator.mediaDevices?.getUserMedia) throw new Error('No microphone API')
      stream = await navigator.mediaDevices.getUserMedia({ audio: true })

      const Context = audioContextConstructor()
      if (!Context) throw new Error('No Web Audio API')
      const context = new Context()
      const analyser = context.createAnalyser()
      analyser.fftSize = 512
      context.createMediaStreamSource(stream).connect(analyser)
      const next: AudioResources = {
        stream,
        context,
        analyser,
        bytes: new Uint8Array(new ArrayBuffer(analyser.fftSize)),
      }

      // The user stopped, started again or left the page while the permission was pending.
      if (id !== requestId.current) {
        release(next)
        return false
      }
      resources.current = next
      setStatus('on')
      return true
    } catch {
      stream?.getTracks().forEach((track) => track.stop())
      if (id === requestId.current) setStatus('failed')
      return false
    }
  }, [])

  /** Current volume, from 0 to 1. Zero when the microphone is off. */
  const getLevel = useCallback((): number => {
    const current = resources.current
    if (!current) return 0
    current.analyser.getByteTimeDomainData(current.bytes)
    return levelFromTimeDomain(current.bytes)
  }, [])

  useEffect(
    () => () => {
      requestId.current += 1
      release(resources.current)
      resources.current = null
    },
    [],
  )

  return { status, start, stop, getLevel }
}
