import { useEffect, useRef, useState } from 'react'
import { es } from '../../i18n/es'
import { usePrefersReducedMotion } from '../../hooks/usePrefersReducedMotion'
import { drawOrb, ORB_CANVAS_SIZE } from './drawOrb'
import { frameSeconds } from './dynamics'
import type { DoraMode } from './modes'
import { createOrbEngine } from './orbEngine'
import './DoraOrb.css'

interface DoraOrbProps {
  /** State of DORA. The orb blends to it without jumps. */
  mode: DoraMode
  /**
   * Real sound level from 0 to 1 (microphone now, DORA's voice in S8). The drawing does not
   * know where it comes from. Without it, the orb uses a simulated voice.
   */
  getLevel?: () => number
  /** Fixed noise seed, for tests. Random when omitted. */
  seed?: number
}

function canvasIsSupported(): boolean {
  try {
    return document.createElement('canvas').getContext('2d') !== null
  } catch {
    return false
  }
}

/**
 * Orb of DORA, drawn on a canvas. It moves with gradient noise, not with sine waves.
 * - It stops drawing (and cancels the animation loop) while it is off screen or the tab is hidden.
 * - With reduced motion it is a still image of the current state: no loop, rings or dots.
 * - Without canvas support it is a simple CSS circle.
 */
export function DoraOrb({ mode, getLevel, seed }: DoraOrbProps) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const reducedMotion = usePrefersReducedMotion()
  const [supported] = useState(canvasIsSupported)
  const [engine] = useState(() => createOrbEngine({ seed }))

  // The loop reads the latest state and level source without being restarted.
  const modeRef = useRef(mode)
  const getLevelRef = useRef(getLevel)
  useEffect(() => {
    modeRef.current = mode
    getLevelRef.current = getLevel
  })

  // Still image: reduced motion.
  useEffect(() => {
    if (!supported || !reducedMotion) return
    const ctx = canvasRef.current?.getContext('2d')
    if (!ctx) return
    drawOrb(ctx, engine.still(mode), ORB_CANVAS_SIZE)
  }, [engine, mode, reducedMotion, supported])

  // Animation loop.
  useEffect(() => {
    if (!supported || reducedMotion) return
    const canvas = canvasRef.current
    const ctx = canvas?.getContext('2d')
    if (!canvas || !ctx) return

    let frameId = 0
    let last = 0
    let onScreen = typeof IntersectionObserver === 'undefined'
    let tabVisible = document.visibilityState !== 'hidden'

    const tick = (now: number) => {
      const dt = frameSeconds(now - last)
      last = now
      drawOrb(ctx, engine.step(dt, modeRef.current, getLevelRef.current?.()), ORB_CANVAS_SIZE)
      frameId = requestAnimationFrame(tick)
    }

    // Starts or cancels the loop: no frames are drawn when nobody can see the orb.
    const sync = () => {
      const shouldRun = onScreen && tabVisible
      if (shouldRun && frameId === 0) {
        last = performance.now()
        frameId = requestAnimationFrame(tick)
      } else if (!shouldRun && frameId !== 0) {
        cancelAnimationFrame(frameId)
        frameId = 0
      }
    }

    const onVisibility = () => {
      tabVisible = document.visibilityState !== 'hidden'
      sync()
    }
    document.addEventListener('visibilitychange', onVisibility)

    let observer: IntersectionObserver | undefined
    if (typeof IntersectionObserver !== 'undefined') {
      observer = new IntersectionObserver((entries) => {
        for (const entry of entries) onScreen = entry.isIntersecting
        sync()
      })
      observer.observe(canvas)
    }

    sync()

    return () => {
      document.removeEventListener('visibilitychange', onVisibility)
      observer?.disconnect()
      if (frameId !== 0) cancelAnimationFrame(frameId)
    }
  }, [engine, reducedMotion, supported])

  if (!supported) {
    return (
      <div className="dora-orb dora-orb--fallback" role="img" aria-label={es.dora.orbLabel}>
        <span className="dora-orb__circle" data-mode={mode} />
      </div>
    )
  }

  return (
    <div className="dora-orb">
      <canvas
        ref={canvasRef}
        className="dora-orb__canvas"
        width={ORB_CANVAS_SIZE}
        height={ORB_CANVAS_SIZE}
        role="img"
        aria-label={es.dora.orbLabel}
      />
    </div>
  )
}
