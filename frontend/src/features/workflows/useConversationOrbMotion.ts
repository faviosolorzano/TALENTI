import { useEffect, useRef, type RefObject } from 'react'
import { usePrefersReducedMotion } from '../../hooks/usePrefersReducedMotion'
import type { DoraMode } from '../dora/modes'

const AMPLITUDE: Record<DoraMode, number> = {
  idle: 0.28,
  listening: 0.65,
  thinking: 0.5,
  speaking: 0.85,
}

/** Organic contour, not an analysis or a simulated microphone reading. */
function outline(time: number, offset: number, strength: number, radius = 112, arc = false) {
  const start = arc ? 3.7 : 0
  const end = arc ? 5.2 : Math.PI * 2
  const count = arc ? 30 : 80
  let path = ''
  for (let i = 0; i <= count; i++) {
    const a = start + ((end - start) * i) / count
    const r =
      radius +
      strength *
        (6 * Math.sin(a * 3 + time * 0.68 + offset) +
          5 * Math.sin(a * 2 - time * 0.47 + offset * 0.7) +
          2 * Math.cos(a * 5 + time * 0.37))
    path += `${i === 0 ? 'M' : 'L'}${(160 + Math.cos(a) * r).toFixed(2)} ${(160 + Math.sin(a) * r).toFixed(2)} `
  }
  return path + (arc ? '' : 'Z')
}

/** Mutate decorative SVG paths only, keeping React and the conversation off the frame loop. */
export function useConversationOrbMotion(
  rootRef: RefObject<HTMLDivElement | null>,
  mode: DoraMode,
  levelDriven: boolean,
) {
  const reduced = usePrefersReducedMotion()
  const motion = useRef({ phase: 0, amplitude: AMPLITUDE.idle, energy: 0 })
  useEffect(() => {
    const root = rootRef.current
    if (!root || reduced) return
    const ribbon = root.querySelector(
      '.conversation-orb__ribbon:not(.conversation-orb__ribbon--back)',
    )
    const back = root.querySelector('.conversation-orb__ribbon--back')
    const core = root.querySelector('.conversation-orb__core')
    const glow = root.querySelector('.conversation-orb__glow')
    const shine = root.querySelector('.conversation-orb__shine')
    const orbit = root.querySelector('.conversation-orb__orbit')
    const actor = root.closest<HTMLElement>('.dora-call__actors') ?? root
    let frame = 0,
      last = 0,
      lastDraw = 0
    const tick = (now: number) => {
      const delta = last ? Math.min((now - last) / 1000, 0.05) : 0
      last = now
      const current = motion.current
      current.phase += delta * (mode === 'thinking' ? 0.7 : 1)
      if (now - lastDraw >= 1000 / 30) {
        const raw = levelDriven ? Number(actor.style.getPropertyValue('--dora-level')) : 0
        const level = Number.isFinite(raw) ? Math.max(0, Math.min(1, raw)) : 0
        current.energy += (level - current.energy) * 0.15
        current.amplitude += (AMPLITUDE[mode] - current.amplitude) * 0.1
        const t = current.phase
        const strength = current.amplitude * (1 + current.energy * 0.85)
        const radius = 112 + Math.sin(t * 0.9) * 1.2 + current.energy * 5
        ribbon?.setAttribute('d', outline(t, 0.3, strength, radius))
        back?.setAttribute('d', outline(t, 2.8, strength * 1.15, radius + 4))
        core?.setAttribute('d', outline(t, 1.2, strength * 0.62, radius))
        glow?.setAttribute('d', outline(t, 0.6, strength, radius + 8))
        shine?.setAttribute('d', outline(t, 1.2, strength * 0.62, radius - 4, true))
        orbit?.setAttribute(
          'transform',
          `rotate(${mode === 'thinking' ? t * 12 : Math.sin(t * 0.3) * 5} 320 220)`,
        )
        lastDraw = now
      }
      frame = requestAnimationFrame(tick)
    }
    const visibility = () => {
      cancelAnimationFrame(frame)
      last = 0
      if (!document.hidden) frame = requestAnimationFrame(tick)
    }
    document.addEventListener('visibilitychange', visibility)
    visibility()
    return () => {
      cancelAnimationFrame(frame)
      document.removeEventListener('visibilitychange', visibility)
    }
  }, [rootRef, mode, levelDriven, reduced])
}
