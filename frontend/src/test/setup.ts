import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach, beforeEach, vi } from 'vitest'
import { createFakeCanvasContext } from './mocks'

beforeEach(() => {
  // Each test starts as a first visit: no cookie choice from a previous test.
  document.cookie = 'talenti_cookies=; Max-Age=0; Path=/'
  HTMLCanvasElement.prototype.getContext = vi.fn(
    () => createFakeCanvasContext() as unknown as CanvasRenderingContext2D,
  ) as unknown as typeof HTMLCanvasElement.prototype.getContext
  window.scrollTo = vi.fn() as unknown as typeof window.scrollTo
  Element.prototype.scrollIntoView = vi.fn()
  // No test reaches a real server: a test that needs the API installs `mockBackend()`.
  vi.stubGlobal(
    'fetch',
    vi.fn(() => Promise.reject(new TypeError('fetch is not mocked in this test'))),
  )
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})
