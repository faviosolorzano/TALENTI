import { useEffect, useEffectEvent, useState } from 'react'

export type ResourceState<T> =
  { status: 'loading' } | { status: 'success'; data: T } | { status: 'error'; error: unknown }

type Settled<T> =
  | { key: string; attempt: number; ok: true; data: T }
  | { key: string; attempt: number; ok: false; error: unknown }

/**
 * Loads one resource and exposes its state: loading, success or error. When `key` changes the
 * previous request is aborted and its answer is ignored, so the LAST request always wins.
 * `retry` repeats the request. Nothing is cached or stored here.
 *
 * `key` must identify everything `load` depends on (for example the query string).
 */
export function useApiResource<T>(
  key: string,
  load: (signal: AbortSignal) => Promise<T>,
): { state: ResourceState<T>; retry: () => void } {
  const [attempt, setAttempt] = useState(0)
  const [settled, setSettled] = useState<Settled<T> | null>(null)
  const run = useEffectEvent((signal: AbortSignal) => load(signal))

  useEffect(() => {
    const controller = new AbortController()
    run(controller.signal).then(
      (data) => {
        if (!controller.signal.aborted) setSettled({ key, attempt, ok: true, data })
      },
      (error: unknown) => {
        if (!controller.signal.aborted) setSettled({ key, attempt, ok: false, error })
      },
    )
    return () => controller.abort()
  }, [key, attempt])

  const current = settled && settled.key === key && settled.attempt === attempt ? settled : null
  const state: ResourceState<T> = !current
    ? { status: 'loading' }
    : current.ok
      ? { status: 'success', data: current.data }
      : { status: 'error', error: current.error }

  return { state, retry: () => setAttempt((value) => value + 1) }
}
