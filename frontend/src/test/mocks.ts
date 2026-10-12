import { act } from '@testing-library/react'
import { vi } from 'vitest'

/**
 * Fake `matchMedia` for "prefers-reduced-motion". `setReduced` changes the preference while the
 * page is open and notifies the listeners.
 */
export function mockReducedMotion(initial: boolean) {
  let reduced = initial
  const listeners = new Set<() => void>()
  vi.stubGlobal(
    'matchMedia',
    vi.fn((query: string) => ({
      get matches() {
        return query.includes('prefers-reduced-motion') ? reduced : false
      },
      media: query,
      addEventListener: (_type: string, listener: () => void) => listeners.add(listener),
      removeEventListener: (_type: string, listener: () => void) => listeners.delete(listener),
    })),
  )
  return {
    setReduced(value: boolean) {
      reduced = value
      act(() => listeners.forEach((listener) => listener()))
    },
  }
}

/** Fake `IntersectionObserver` that tests drive by hand. */
export function mockIntersectionObserver() {
  const observers: Array<{
    callback: IntersectionObserverCallback
    targets: Set<Element>
    disconnected: boolean
  }> = []

  class FakeIntersectionObserver {
    private readonly record
    constructor(callback: IntersectionObserverCallback) {
      this.record = { callback, targets: new Set<Element>(), disconnected: false }
      observers.push(this.record)
    }
    observe = (target: Element) => {
      this.record.targets.add(target)
    }
    unobserve = (target: Element) => {
      this.record.targets.delete(target)
    }
    disconnect = () => {
      this.record.disconnected = true
      this.record.targets.clear()
    }
    takeRecords = () => []
  }
  vi.stubGlobal('IntersectionObserver', FakeIntersectionObserver)

  return {
    /** Tells the observers watching `target` that it entered or left the screen. */
    setIntersecting(target: Element, isIntersecting: boolean) {
      act(() => {
        for (const observer of observers) {
          if (observer.targets.has(target)) {
            observer.callback(
              [{ target, isIntersecting } as IntersectionObserverEntry],
              observer as unknown as IntersectionObserver,
            )
          }
        }
      })
    },
  }
}

/** Minimal 2D context: jsdom has no canvas. Tests that need to look at it replace it. */
export function createFakeCanvasContext() {
  const gradient = { addColorStop: vi.fn() }
  return {
    clearRect: vi.fn(),
    beginPath: vi.fn(),
    closePath: vi.fn(),
    moveTo: vi.fn(),
    quadraticCurveTo: vi.fn(),
    arc: vi.fn(),
    stroke: vi.fn(),
    fill: vi.fn(),
    createRadialGradient: vi.fn(() => gradient),
    shadowColor: '',
    shadowBlur: 0,
    strokeStyle: '',
    fillStyle: '',
    lineWidth: 1,
  }
}
