import { useEffect, useState, useSyncExternalStore } from 'react'
import type { ExamAttempt } from '../../../api/workflows'
import { createMediaPipeDetector } from './detector'
import { ProctoringSession, type ProctoringPorts, type ProctoringView } from './session'
import { parseSnapshotInterval, parseThresholds } from './thresholds'

/**
 * El examen solo se rinde en computadora: hace falta cámara, pantalla completa de página y WebAssembly (014-39,
 * duda E8). Un celular no se admite aunque su navegador tenga algunas de estas funciones.
 */
export function examDeviceSupported(): boolean {
  const nav = navigator as Navigator & { userAgentData?: { mobile?: boolean } }
  const phone =
    nav.userAgentData?.mobile === true ||
    /iPhone|iPod|Android.+Mobile|Windows Phone/i.test(nav.userAgent)
  return (
    !phone &&
    typeof nav.mediaDevices?.getUserMedia === 'function' &&
    document.fullscreenEnabled === true &&
    typeof WebAssembly === 'object'
  )
}

/** Sesión de supervisión del intento; se crea una vez y se apaga (cámara, modelos, temporizadores) al salir. */
export function useProctoringSession(
  attempt: ExamAttempt,
  enabled: boolean,
  ports: ProctoringPorts,
): { session: ProctoringSession | null; view: ProctoringView | null } {
  const [session] = useState(() =>
    enabled
      ? new ProctoringSession({
          config: parseThresholds(attempt.proctoring?.thresholds),
          snapshotIntervalMs: parseSnapshotInterval(attempt.proctoring?.snapshotInterval),
          detectorFactory: createMediaPipeDetector,
        })
      : null,
  )
  useEffect(() => {
    session?.setPorts(ports)
  })
  useEffect(() => {
    if (!session) return
    session.activate()
    return () => session.dispose()
  }, [session])
  const view = useSyncExternalStore(
    session?.subscribe ?? noopSubscribe,
    session?.view ?? noView,
    session?.view ?? noView,
  )
  return { session, view }
}
const noopSubscribe = () => () => {}
const noView = () => null

function subscribeFullscreen(listener: () => void) {
  document.addEventListener('fullscreenchange', listener)
  return () => document.removeEventListener('fullscreenchange', listener)
}

/** Si la página está en pantalla completa. */
export function useFullscreen(): boolean {
  return useSyncExternalStore(
    subscribeFullscreen,
    () => Boolean(document.fullscreenElement),
    () => false,
  )
}
