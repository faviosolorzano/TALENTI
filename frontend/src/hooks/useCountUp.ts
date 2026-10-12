import { useEffect, useRef, useState } from 'react'

export function easeOutCubic(progress: number): number {
  return 1 - Math.pow(1 - progress, 3)
}

/**
 * Counts from the number currently shown to `target` in `duration` ms. If the target changes
 * mid-way, the count continues from the value shown, never from a jump. When `enabled` is
 * false it returns the target at once (reduced motion).
 */
export function useCountUp(target: number, duration: number, enabled: boolean): number {
  const [value, setValue] = useState(0)
  const shown = useRef(0)

  useEffect(() => {
    if (!enabled) {
      shown.current = target
      return
    }
    const from = shown.current
    if (from === target) return

    const start = performance.now()
    let frame = 0
    const tick = (now: number) => {
      const progress = Math.min(1, Math.max(0, (now - start) / duration))
      const next = Math.round(from + (target - from) * easeOutCubic(progress))
      shown.current = next
      setValue(next)
      if (progress < 1) frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    return () => cancelAnimationFrame(frame)
  }, [target, duration, enabled])

  return enabled ? value : target
}
