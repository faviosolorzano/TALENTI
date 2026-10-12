import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { es } from '../../i18n/es'
import {
  createFakeCanvasContext,
  mockIntersectionObserver,
  mockReducedMotion,
} from '../../test/mocks'
import { DoraOrb } from './DoraOrb'

/** Controls requestAnimationFrame by hand so the tests can count the frames drawn. */
function mockAnimationFrames() {
  let nextId = 1
  const pending = new Map<number, FrameRequestCallback>()
  let clock = 1000
  vi.stubGlobal(
    'requestAnimationFrame',
    vi.fn((callback: FrameRequestCallback) => {
      const id = nextId++
      pending.set(id, callback)
      return id
    }),
  )
  vi.stubGlobal(
    'cancelAnimationFrame',
    vi.fn((id: number) => {
      pending.delete(id)
    }),
  )
  vi.spyOn(performance, 'now').mockImplementation(() => clock)
  return {
    get pendingCount() {
      return pending.size
    },
    /** Runs every pending callback once, `ms` milliseconds later. */
    tick(ms = 16) {
      clock += ms
      const callbacks = [...pending.values()]
      pending.clear()
      act(() => callbacks.forEach((callback) => callback(clock)))
    },
  }
}

function useCanvasSpy() {
  const context = createFakeCanvasContext()
  HTMLCanvasElement.prototype.getContext = vi.fn(
    () => context as unknown as CanvasRenderingContext2D,
  ) as unknown as typeof HTMLCanvasElement.prototype.getContext
  return context
}

describe('DoraOrb', () => {
  beforeEach(() => {
    vi.spyOn(Math, 'random').mockReturnValue(0.5)
  })
  afterEach(() => {
    vi.restoreAllMocks()
  })

  it('draws frames on a canvas labeled as DORA while it is on screen', () => {
    const frames = mockAnimationFrames()
    const ctx = useCanvasSpy()
    render(<DoraOrb mode="idle" seed={1} />)

    expect(screen.getByRole('img', { name: es.dora.orbLabel }).tagName).toBe('CANVAS')
    frames.tick()
    frames.tick()
    expect(ctx.clearRect).toHaveBeenCalledTimes(2)
  })

  // 003-23
  it('cancels the loop when the orb leaves the screen and resumes when it comes back', () => {
    const observer = mockIntersectionObserver()
    const frames = mockAnimationFrames()
    const ctx = useCanvasSpy()
    render(<DoraOrb mode="idle" seed={1} />)
    const canvas = screen.getByRole('img', { name: es.dora.orbLabel })

    // Not on screen yet: nothing is scheduled or drawn.
    expect(frames.pendingCount).toBe(0)
    observer.setIntersecting(canvas, true)
    expect(frames.pendingCount).toBe(1)
    frames.tick()
    expect(ctx.clearRect).toHaveBeenCalledTimes(1)

    observer.setIntersecting(canvas, false)
    expect(frames.pendingCount).toBe(0)
    frames.tick(5000)
    expect(ctx.clearRect).toHaveBeenCalledTimes(1)

    observer.setIntersecting(canvas, true)
    expect(frames.pendingCount).toBe(1)
    frames.tick()
    expect(ctx.clearRect).toHaveBeenCalledTimes(2)
  })

  it('stops drawing when the tab is hidden and resumes when it is visible again', () => {
    const frames = mockAnimationFrames()
    const ctx = useCanvasSpy()
    render(<DoraOrb mode="idle" seed={1} />)
    frames.tick()
    expect(ctx.clearRect).toHaveBeenCalledTimes(1)

    const setVisibility = (state: 'hidden' | 'visible') => {
      Object.defineProperty(document, 'visibilityState', { value: state, configurable: true })
      act(() => {
        document.dispatchEvent(new Event('visibilitychange'))
      })
    }
    setVisibility('hidden')
    expect(frames.pendingCount).toBe(0)
    setVisibility('visible')
    expect(frames.pendingCount).toBe(1)
  })

  it('asks the level source on each frame (the drawing does not know the provider)', () => {
    const frames = mockAnimationFrames()
    useCanvasSpy()
    const getLevel = vi.fn(() => 0.5)
    render(<DoraOrb mode="listening" getLevel={getLevel} seed={1} />)
    frames.tick()
    frames.tick()
    expect(getLevel).toHaveBeenCalledTimes(2)
  })

  it('cancels its loop when it is removed', () => {
    const frames = mockAnimationFrames()
    useCanvasSpy()
    const { unmount } = render(<DoraOrb mode="idle" seed={1} />)
    expect(frames.pendingCount).toBe(1)
    unmount()
    expect(frames.pendingCount).toBe(0)
  })

  // 003-24
  it('with reduced motion draws one still image and never starts a loop', () => {
    mockReducedMotion(true)
    const frames = mockAnimationFrames()
    const ctx = useCanvasSpy()
    const { rerender } = render(<DoraOrb mode="idle" seed={1} />)

    expect(frames.pendingCount).toBe(0)
    expect(ctx.clearRect).toHaveBeenCalledTimes(1)
    // No rings and no dots in the still image.
    expect(ctx.arc).not.toHaveBeenCalled()

    rerender(<DoraOrb mode="listening" seed={1} />)
    expect(ctx.clearRect).toHaveBeenCalledTimes(2)
    expect(ctx.arc).not.toHaveBeenCalled()
    rerender(<DoraOrb mode="thinking" seed={1} />)
    expect(ctx.arc).not.toHaveBeenCalled()
    expect(frames.pendingCount).toBe(0)
  })

  it('reacts when the reduced motion preference changes with the page open', () => {
    const media = mockReducedMotion(false)
    const frames = mockAnimationFrames()
    useCanvasSpy()
    render(<DoraOrb mode="idle" seed={1} />)
    expect(frames.pendingCount).toBe(1)

    media.setReduced(true)
    expect(frames.pendingCount).toBe(0)

    media.setReduced(false)
    expect(frames.pendingCount).toBe(1)
  })

  it('draws rings while listening and dots while thinking when motion is allowed', () => {
    const frames = mockAnimationFrames()
    const ctx = useCanvasSpy()
    const { rerender } = render(<DoraOrb mode="listening" seed={1} />)
    for (let i = 0; i < 40; i++) frames.tick()
    expect(ctx.arc).toHaveBeenCalled()
    rerender(<DoraOrb mode="idle" seed={1} />)
    // The rings fade out as the orb blends to the new state (about 1 s).
    for (let i = 0; i < 120; i++) frames.tick()
    ctx.arc.mockClear()
    for (let i = 0; i < 10; i++) frames.tick()
    expect(ctx.arc).not.toHaveBeenCalled()
  })

  // 003-25
  it('shows a simple CSS circle when the browser has no canvas', () => {
    HTMLCanvasElement.prototype.getContext = vi.fn(
      () => null,
    ) as unknown as typeof HTMLCanvasElement.prototype.getContext
    const frames = mockAnimationFrames()
    const { container } = render(<DoraOrb mode="idle" seed={1} />)

    expect(container.querySelector('canvas')).toBeNull()
    expect(screen.getByRole('img', { name: es.dora.orbLabel })).toBeInTheDocument()
    expect(container.querySelector('.dora-orb__circle')).toBeInTheDocument()
    expect(frames.pendingCount).toBe(0)
  })
})
