import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import { es } from '../../i18n/es'
import { InterviewCamera } from './InterviewCamera'
import { useInterviewCamera } from './useInterviewCamera'

const t = es.interviewCamera
const originalMedia = Object.getOwnPropertyDescriptor(navigator, 'mediaDevices')
const capture = vi.fn()
const network = vi.fn()

function media() {
  const track = new EventTarget() as EventTarget & {
    readyState: string
    stop: ReturnType<typeof vi.fn>
  }
  track.readyState = 'live'
  track.stop = vi.fn(() => {
    track.readyState = 'ended'
  })
  return {
    track,
    stream: { getTracks: () => [track], getVideoTracks: () => [track] } as unknown as MediaStream,
  }
}
function Preview({ enabled = true }: { enabled?: boolean }) {
  return <InterviewCamera camera={useInterviewCamera(enabled)} />
}
beforeEach(() => {
  capture.mockReset()
  network.mockReset()
  localStorage.clear()
  Object.defineProperty(navigator, 'mediaDevices', {
    configurable: true,
    value: { getUserMedia: capture },
  })
  vi.stubGlobal('fetch', network)
})
afterEach(() => {
  if (originalMedia) Object.defineProperty(navigator, 'mediaDevices', originalMedia)
  else Reflect.deleteProperty(navigator, 'mediaDevices')
  vi.unstubAllGlobals()
})

it('does not open camera or contact a service until an explicit action; privacy is visible', () => {
  render(<Preview />)
  expect(capture).not.toHaveBeenCalled()
  expect(network).not.toHaveBeenCalled()
  expect(screen.getByText(t.privacy)).toBeVisible()
  expect(screen.getByText(t.off)).toBeVisible()
})

it('asks only for local video, attaches the stream and releases it on switching off', async () => {
  const sample = media()
  capture.mockResolvedValue(sample.stream)
  render(<Preview />)
  await userEvent.click(screen.getByRole('button', { name: t.start }))
  await screen.findByRole('button', { name: t.stop })
  expect(capture).toHaveBeenCalledWith({ video: { facingMode: 'user' }, audio: false })
  expect((screen.getByLabelText(t.video) as HTMLVideoElement).srcObject).toBe(sample.stream)
  expect(network).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: t.stop }))
  expect(sample.track.stop).toHaveBeenCalledTimes(1)
  expect(screen.getByText(t.off)).toBeVisible()
  expect((screen.getByLabelText(t.video) as HTMLVideoElement).srcObject).toBeNull()
})

it.each([
  ['NotAllowedError', 'denied'],
  ['NotFoundError', 'missing'],
  ['NotReadableError', 'unavailable'],
] as const)('shows a recoverable %s without changing interview eligibility', async (name, key) => {
  capture.mockRejectedValue(new DOMException('test', name))
  render(<Preview />)
  await userEvent.click(screen.getByRole('button', { name: t.start }))
  expect(await screen.findByRole('alert')).toHaveTextContent(t.errors[key])
  expect(screen.getByRole('button', { name: t.retry })).toBeEnabled()
  expect(capture).toHaveBeenCalledTimes(1)
  expect(network).not.toHaveBeenCalled()
})

it('reports a lost track and explicitly retries with a new local stream', async () => {
  const first = media(),
    second = media()
  capture.mockResolvedValueOnce(first.stream).mockResolvedValueOnce(second.stream)
  render(<Preview />)
  await userEvent.click(screen.getByRole('button', { name: t.start }))
  await screen.findByRole('button', { name: t.stop })
  act(() => first.track.dispatchEvent(new Event('ended')))
  expect(screen.getByRole('alert')).toHaveTextContent(t.errors.lost)
  await userEvent.click(screen.getByRole('button', { name: t.retry }))
  await screen.findByRole('button', { name: t.stop })
  expect((screen.getByLabelText(t.video) as HTMLVideoElement).srcObject).toBe(second.stream)
  expect(first.track.stop).toHaveBeenCalledTimes(1)
})

it('releases a stream when leaving and also closes a permission granted after unmount', async () => {
  const sample = media()
  let resolve!: (stream: MediaStream) => void
  capture.mockReturnValue(
    new Promise<MediaStream>((done) => {
      resolve = done
    }),
  )
  const app = render(<Preview />)
  await userEvent.click(screen.getByRole('button', { name: t.start }))
  expect(screen.getByRole('button', { name: t.waiting })).toBeDisabled()
  app.unmount()
  await act(async () => resolve(sample.stream))
  expect(sample.track.stop).toHaveBeenCalledTimes(1)
})

it('releases an active preview when the session ends or the page is hidden by navigation', async () => {
  const first = media(),
    second = media()
  capture.mockResolvedValueOnce(first.stream).mockResolvedValueOnce(second.stream)
  const app = render(<Preview />)
  await userEvent.click(screen.getByRole('button', { name: t.start }))
  await screen.findByRole('button', { name: t.stop })
  app.rerender(<Preview enabled={false} />)
  await waitFor(() => expect(first.track.stop).toHaveBeenCalledTimes(1))
  app.rerender(<Preview />)
  await userEvent.click(screen.getByRole('button', { name: t.start }))
  await screen.findByRole('button', { name: t.stop })
  fireEvent(window, new Event('pagehide'))
  expect(second.track.stop).toHaveBeenCalledTimes(1)
})

it('does not let an old pending permission replace a newer preview', async () => {
  const old = media(),
    current = media()
  let resolve!: (stream: MediaStream) => void
  capture
    .mockReturnValueOnce(
      new Promise<MediaStream>((done) => {
        resolve = done
      }),
    )
    .mockResolvedValueOnce(current.stream)
  const app = render(<Preview />)
  await userEvent.click(screen.getByRole('button', { name: t.start }))
  app.rerender(<Preview enabled={false} />)
  app.rerender(<Preview />)
  await userEvent.click(screen.getByRole('button', { name: t.start }))
  await screen.findByRole('button', { name: t.stop })
  await act(async () => resolve(old.stream))
  expect(old.track.stop).toHaveBeenCalledTimes(1)
  expect((screen.getByLabelText(t.video) as HTMLVideoElement).srcObject).toBe(current.stream)
})
