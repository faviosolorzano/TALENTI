import { act, render } from '@testing-library/react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { ConversationOrb } from './ConversationOrb'

let frames: Map<number, FrameRequestCallback>
let id: number
let reduced: boolean
let media: EventTarget
beforeEach(() => {
  frames = new Map()
  id = 0
  reduced = false
  media = new EventTarget()
  vi.stubGlobal(
    'requestAnimationFrame',
    vi.fn((callback: FrameRequestCallback) => {
      frames.set(++id, callback)
      return id
    }),
  )
  vi.stubGlobal(
    'cancelAnimationFrame',
    vi.fn((key: number) => {
      frames.delete(key)
    }),
  )
  vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  vi.stubGlobal('matchMedia', () => ({
    get matches() {
      return reduced
    },
    addEventListener: media.addEventListener.bind(media),
    removeEventListener: media.removeEventListener.bind(media),
  }))
})
afterEach(() => {
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})
function step(time: number) {
  const pending = [...frames.values()]
  frames.clear()
  act(() => pending.forEach((callback) => callback(time)))
}

it('morphs the silhouette instead of only scaling a static picture and keeps the mode real', () => {
  const app = render(<ConversationOrb mode="listening" />)
  const path = app.container.querySelector('.conversation-orb__core')!
  step(40)
  const before = path.getAttribute('d')
  step(80)
  step(120)
  expect(path.getAttribute('d')).not.toBe(before)
  expect(path.getAttribute('d')).not.toMatch(/NaN|Infinity/)
  app.rerender(<ConversationOrb mode="thinking" />)
  expect(app.container.querySelector('.conversation-orb')).toHaveAttribute('data-mode', 'thinking')
  step(160)
  expect(app.container.querySelector('.conversation-orb__orbit')).toHaveAttribute(
    'transform',
    expect.stringContaining('rotate('),
  )
})

it('uses the actual level provided by the call without opening a microphone', () => {
  const capture = vi.fn()
  const original = Object.getOwnPropertyDescriptor(navigator, 'mediaDevices')
  Object.defineProperty(navigator, 'mediaDevices', {
    configurable: true,
    value: { getUserMedia: capture },
  })
  const app = render(
    <>
      <div className="dora-call__actors" data-testid="quiet">
        <ConversationOrb mode="speaking" levelDriven />
      </div>
      <div className="dora-call__actors" data-testid="loud">
        <ConversationOrb mode="speaking" levelDriven />
      </div>
    </>,
  )
  const actors = app.container.querySelectorAll<HTMLElement>('.dora-call__actors')
  actors[0].style.setProperty('--dora-level', '0')
  actors[1].style.setProperty('--dora-level', '1')
  step(40)
  step(80)
  step(120)
  expect(actors[0].querySelector('.conversation-orb__core')!.getAttribute('d')).not.toBe(
    actors[1].querySelector('.conversation-orb__core')!.getAttribute('d'),
  )
  expect(capture).not.toHaveBeenCalled()
  if (original) Object.defineProperty(navigator, 'mediaDevices', original)
  else Reflect.deleteProperty(navigator, 'mediaDevices')
})

it('stays still with reduced motion and cancels a running animation when preference changes', () => {
  reduced = true
  const app = render(<ConversationOrb mode="speaking" />)
  expect(frames.size).toBe(0)
  app.unmount()
  reduced = false
  const active = render(<ConversationOrb mode="listening" />)
  expect(frames.size).toBe(1)
  act(() => {
    reduced = true
    media.dispatchEvent(new Event('change'))
  })
  expect(frames.size).toBe(0)
  active.unmount()
})

it('pauses when the tab is hidden and releases frames and listeners on unmount', () => {
  const hidden = vi.spyOn(document, 'hidden', 'get').mockReturnValue(false)
  const app = render(<ConversationOrb mode="idle" />)
  expect(frames.size).toBe(1)
  hidden.mockReturnValue(true)
  act(() => document.dispatchEvent(new Event('visibilitychange')))
  expect(frames.size).toBe(0)
  hidden.mockReturnValue(false)
  act(() => document.dispatchEvent(new Event('visibilitychange')))
  expect(frames.size).toBe(1)
  app.unmount()
  expect(frames.size).toBe(0)
  act(() => document.dispatchEvent(new Event('visibilitychange')))
  expect(frames.size).toBe(0)
})
