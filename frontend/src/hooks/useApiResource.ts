import { useEffect, useEffectEvent, useState } from 'react'

export type ResourceState<T> =
  { status: 'loading' } | { status: 'success'; data: T } | { status: 'error'; error: unknown }

type Settled<T> =
  | { key: string; attempt: number; ok: true; data: T }
  | { key: string; attempt: number; ok: false; error: unknown }

interface Options {
  /**
   * While a new request is in flight, keep showing the data of the last successful one and
   * report `refreshing: true`, instead of going back to `loading`. Use it only when the old data
   * is still a sensible thing to show (a list being filtered), never when `key` is another item.
   */
  keepPrevious?: boolean
}

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
  { keepPrevious = false }: Options = {},
): { state: ResourceState<T>; retry: () => void; refreshing: boolean } {
  const [attempt, setAttempt] = useState(0)
  const [settled, setSettled] = useState<Settled<T> | null>(null)
  // Last successful answer. It is dropped when a request fails, so a retry starts clean.
  const [previous, setPrevious] = useState<{ data: T } | null>(null)
  const run = useEffectEvent((signal: AbortSignal) => load(signal))

  useEffect(() => {
    const controller = new AbortController()
    run(controller.signal).then(
      (data) => {
        if (controller.signal.aborted) return
        setSettled({ key, attempt, ok: true, data })
        setPrevious({ data })
      },
      (error: unknown) => {
        if (controller.signal.aborted) return
        setSettled({ key, attempt, ok: false, error })
        setPrevious(null)
      },
    )
    return () => controller.abort()
  }, [key, attempt])

  const current = settled && settled.key === key && settled.attempt === attempt ? settled : null
  let state: ResourceState<T> = { status: 'loading' }
  let refreshing = false
  if (current) {
    state = current.ok
      ? { status: 'success', data: current.data }
      : { status: 'error', error: current.error }
  } else if (keepPrevious && previous) {
    state = { status: 'success', data: previous.data }
    refreshing = true
  }

  return { state, retry: () => setAttempt((value) => value + 1), refreshing }
}
