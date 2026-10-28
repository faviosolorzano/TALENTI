import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { DeviceCheck } from './DeviceCheck'
import { es } from '../../i18n/es'
const c = es.doraCall
let original: PropertyDescriptor | undefined
let sample = 128
const contexts: { close: ReturnType<typeof vi.fn>; disconnect: ReturnType<typeof vi.fn> }[] = []
class FakeAudio {
  state = 'running'
  currentTime = 0
  destination = {}
  disconnect = vi.fn()
  close = vi.fn(async () => {
    this.state = 'closed'
  })
  resume = vi.fn(async () => {})
  constructor() {
    contexts.push(this)
  }
  createMediaStreamSource() {
    return { connect: vi.fn(), disconnect: this.disconnect }
  }
  createAnalyser() {
    return { fftSize: 256, getByteTimeDomainData: (data: Uint8Array) => data.fill(sample) }
  }
  createOscillator() {
    return {
      frequency: { value: 0 },
      connect: vi.fn(),
      start: vi.fn(),
      stop: vi.fn(),
      onended: null,
    }
  }
  createGain() {
    return { gain: { value: 0 }, connect: vi.fn() }
  }
}
beforeEach(() => {
  original = Object.getOwnPropertyDescriptor(navigator, 'mediaDevices')
  sample = 128
  contexts.length = 0
  vi.stubGlobal('AudioContext', FakeAudio)
})
afterEach(() => {
  if (original) Object.defineProperty(navigator, 'mediaDevices', original)
  else Reflect.deleteProperty(navigator, 'mediaDevices')
})
function devices(getUserMedia: ReturnType<typeof vi.fn>) {
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia } })
}
function stream(id: string) {
  const stop = vi.fn()
  return { value: { id, getTracks: () => [{ stop }] } as unknown as MediaStream, stop }
}
async function open() {
  const user = userEvent.setup()
  const app = render(<DeviceCheck />)
  await user.click(screen.getByRole('button', { name: c.deviceCheck }))
  return { ...app, user }
}
it('012-F01: device check opens without permissions or network calls', async () => {
  const media = vi.fn()
  devices(media)
  await open()
  expect(media).not.toHaveBeenCalled()
  expect(fetch).not.toHaveBeenCalled()
  expect(screen.getByText(c.devicePrivacy)).toBeVisible()
  expect(screen.queryByText(c.signal)).toBeNull()
})
it('012-F01/F03: camera is video-only and closing stops all tracks', async () => {
  const camera = stream('camera')
  const media = vi.fn(async () => camera.value)
  devices(media)
  const { user } = await open()
  await user.click(screen.getByRole('button', { name: c.cameraStart }))
  await screen.findByText(c.cameraOn)
  expect(media).toHaveBeenCalledWith({ video: { facingMode: 'user' }, audio: false })
  await user.click(screen.getByRole('button', { name: c.close }))
  expect(camera.stop).toHaveBeenCalledTimes(1)
  expect(fetch).not.toHaveBeenCalled()
})
it('012-F03: a late camera permission is stopped after closing, with one request', async () => {
  let release!: (s: MediaStream) => void
  const pending = new Promise<MediaStream>((resolve) => {
    release = resolve
  })
  const media = vi.fn(() => pending)
  devices(media)
  const { user } = await open()
  await user.click(screen.getByRole('button', { name: c.cameraStart }))
  await user.click(screen.getByRole('button', { name: c.pending }))
  expect(media).toHaveBeenCalledTimes(1)
  await user.click(screen.getByRole('button', { name: c.close }))
  const camera = stream('late')
  await act(async () => {
    release(camera.value)
    await pending
  })
  expect(camera.stop).toHaveBeenCalledTimes(1)
})
it('012-F02: denied permission has a short recovery message and no success claim', async () => {
  devices(
    vi.fn(async () => {
      throw new DOMException('denied', 'NotAllowedError')
    }),
  )
  const { user } = await open()
  await user.click(screen.getByRole('button', { name: c.cameraStart }))
  expect(await screen.findByRole('alert')).toHaveTextContent(c.mediaDenied)
  expect(screen.queryByText(c.cameraOn)).toBeNull()
  expect(screen.getByRole('button', { name: c.cameraStart })).toBeEnabled()
})
it('012-F02/F03: microphone measures actual signal without claiming silent input worked, then releases audio', async () => {
  const mic = stream('mic')
  const media = vi.fn(async () => mic.value)
  devices(media)
  const { user } = await open()
  await user.click(screen.getByRole('button', { name: c.micStart }))
  await screen.findByText(c.micReady)
  expect(media).toHaveBeenCalledWith({ audio: true, video: false })
  expect(screen.queryByText(c.signal)).toBeNull()
  sample = 144
  await screen.findByText(c.signal)
  await user.click(screen.getByRole('button', { name: c.close }))
  expect(mic.stop).toHaveBeenCalledTimes(1)
  await waitFor(() => expect(contexts[0]!.close).toHaveBeenCalled())
  expect(contexts[0]!.disconnect).toHaveBeenCalled()
})
it('012-F02: playing the speaker tone requires personal confirmation before success', async () => {
  devices(vi.fn())
  const { user } = await open()
  expect(screen.queryByRole('button', { name: c.heard })).toBeNull()
  await user.click(screen.getByRole('button', { name: c.tone }))
  expect(screen.queryByText(c.soundConfirmed)).toBeNull()
  await user.click(await screen.findByRole('button', { name: c.heard }))
  expect(screen.getByText(c.soundConfirmed)).toBeVisible()
  await user.click(screen.getByRole('button', { name: c.close }))
  expect(contexts[0]!.close).toHaveBeenCalled()
})
