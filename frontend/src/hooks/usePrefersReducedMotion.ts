import { useSyncExternalStore } from 'react'

const QUERY = '(prefers-reduced-motion: reduce)'

function subscribe(onChange: () => void): () => void {
  if (typeof window.matchMedia !== 'function') return () => {}
  const media = window.matchMedia(QUERY)
  media.addEventListener?.('change', onChange)
  return () => media.removeEventListener?.('change', onChange)
}

function getSnapshot(): boolean {
  return typeof window.matchMedia === 'function' ? window.matchMedia(QUERY).matches : false
}

/**
 * True when the user asked the system to reduce motion. It follows changes made while the
 * page is open (003-24). Without `matchMedia` it returns false.
 */
export function usePrefersReducedMotion(): boolean {
  return useSyncExternalStore(subscribe, getSnapshot, () => false)
}
