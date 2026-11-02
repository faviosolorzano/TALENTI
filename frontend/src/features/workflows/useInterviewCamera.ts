import { useCallback, useEffect, useRef, useState } from 'react'
import { deviceConstraint } from './mediaPreferences'

export type CameraProblem = 'denied' | 'missing' | 'unavailable' | 'lost'

/** Local preview only. No recorder, audio input, snapshots or network transport. */
export function useInterviewCamera(enabled: boolean) {
  const [stream, setStream] = useState<MediaStream | null>(null)
  const [pending, setPending] = useState(false)
  const [problem, setProblem] = useState<CameraProblem | null>(null)
  const held = useRef<MediaStream | null>(null)
  const mounted = useRef(false)
  const waiting = useRef(false)
  const generation = useRef(0)
  const detach = useRef<(() => void) | null>(null)
  const release = useCallback(() => {
    generation.current++
    waiting.current = false
    detach.current?.()
    detach.current = null
    held.current?.getTracks().forEach((track) => track.stop())
    held.current = null
  }, [])
  const stop = useCallback(() => {
    release()
    if (mounted.current) {
      setStream(null)
      setPending(false)
      setProblem(null)
    }
  }, [release])
  useEffect(() => {
    mounted.current = true
    window.addEventListener('pagehide', stop)
    return () => {
      mounted.current = false
      window.removeEventListener('pagehide', stop)
      release()
    }
  }, [release, stop])
  useEffect(() => {
    if (!enabled) stop()
  }, [enabled, stop])
  const start = useCallback(async () => {
    if (!enabled || waiting.current || held.current) return
    const request = ++generation.current
    waiting.current = true
    setPending(true)
    setProblem(null)
    try {
      if (!navigator.mediaDevices?.getUserMedia) throw new Error('camera-unavailable')
      const value = await navigator.mediaDevices.getUserMedia({
        video: { facingMode: 'user', ...deviceConstraint('camera') },
        audio: false,
      })
      if (!mounted.current || request !== generation.current) {
        value.getTracks().forEach((track) => track.stop())
        return
      }
      if (!value.getVideoTracks().some((track) => track.readyState === 'live')) {
        value.getTracks().forEach((track) => track.stop())
        throw new Error('camera-unavailable')
      }
      const lost = () => {
        if (!mounted.current || request !== generation.current) return
        release()
        setStream(null)
        setPending(false)
        setProblem('lost')
      }
      held.current = value
      value.getVideoTracks().forEach((track) => track.addEventListener('ended', lost))
      detach.current = () =>
        value.getVideoTracks().forEach((track) => track.removeEventListener('ended', lost))
      setStream(value)
    } catch (error) {
      if (!mounted.current || request !== generation.current) return
      setProblem(
        error instanceof DOMException && ['NotAllowedError', 'SecurityError'].includes(error.name)
          ? 'denied'
          : error instanceof DOMException && error.name === 'NotFoundError'
            ? 'missing'
            : 'unavailable',
      )
    } finally {
      if (mounted.current && request === generation.current) {
        waiting.current = false
        setPending(false)
      }
    }
  }, [enabled, release])
  return { stream, pending, problem, start, stop }
}

export type InterviewCameraState = ReturnType<typeof useInterviewCamera>
