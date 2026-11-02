import { screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'
import { openStudentApp } from '../../test/studentApp'
import { clearCsrfCookie } from '../../test/fakeIdentity'
import { es } from '../../i18n/es'

vi.mock('./PracticePage', () => ({
  PracticePage: () => null,
  PracticeSessionPage: () => null,
  DoraSessionView: ({ camera, refresh }: { camera?: ReactNode; refresh: () => void }) => (
    <>
      {camera}
      <button onClick={refresh}>Actualizar estado del servidor</button>
    </>
  ),
}))

const originalMedia = Object.getOwnPropertyDescriptor(navigator, 'mediaDevices')
afterEach(() => {
  clearCsrfCookie()
  if (originalMedia) Object.defineProperty(navigator, 'mediaDevices', originalMedia)
  else Reflect.deleteProperty(navigator, 'mediaDevices')
})

it('releases camera on server completion even with retry allowed, then permits explicit new preview', async () => {
  const track = new EventTarget() as EventTarget & { readyState: string; stop: () => void }
  track.readyState = 'live'
  const stop = vi.fn(() => {
    track.readyState = 'ended'
  })
  track.stop = stop
  const stream = { getTracks: () => [track], getVideoTracks: () => [track] }
  const capture = vi.fn(async () => {
    track.readyState = 'live'
    return stream
  })
  Object.defineProperty(navigator, 'mediaDevices', {
    configurable: true,
    value: { getUserMedia: capture },
  })
  const { user } = openStudentApp('/postulaciones/app-camera/entrevista')
  const fallback = globalThis.fetch
  let completed = false
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input).split('/api/v1')[1]
    const body =
      path === '/applications/app-camera/interview'
        ? {
            applicationId: 'app-camera',
            sessionId: 'session-camera',
            status: completed ? 'COMPLETED' : 'IN_PROGRESS',
            attempt: 1,
            maxAttempts: 2,
            canRetry: completed,
            nextPosition: null,
            questions: [],
          }
        : path === '/practice/options'
          ? { interviewNotice: { version: 'v1', text: 'Aviso de prueba' } }
          : null
    return body
      ? Promise.resolve(
          new Response(JSON.stringify(body), { headers: { 'Content-Type': 'application/json' } }),
        )
      : fallback(input, init)
  })
  await user.click(await screen.findByRole('button', { name: es.interviewCamera.start }))
  await screen.findByRole('button', { name: es.interviewCamera.stop })
  completed = true
  await user.click(screen.getByRole('button', { name: 'Actualizar estado del servidor' }))
  await waitFor(() => expect(stop).toHaveBeenCalledTimes(1))
  await user.click(await screen.findByRole('button', { name: es.interviewCamera.start }))
  await screen.findByRole('button', { name: es.interviewCamera.stop })
  expect(capture).toHaveBeenCalledTimes(2)
  expect(stop).toHaveBeenCalledTimes(1)
})
