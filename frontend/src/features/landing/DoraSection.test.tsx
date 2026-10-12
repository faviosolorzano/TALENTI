import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { es } from '../../i18n/es'
import { mockReducedMotion } from '../../test/mocks'
import { DoraSection } from './DoraSection'

interface FakeAudio {
  stopTrack: ReturnType<typeof vi.fn>
  closeContext: ReturnType<typeof vi.fn>
  analyser: { fftSize: number; getByteTimeDomainData: ReturnType<typeof vi.fn> }
  getUserMedia: ReturnType<typeof vi.fn>
  connect: ReturnType<typeof vi.fn>
  mediaRecorder: ReturnType<typeof vi.fn>
}

/** Fake getUserMedia and Web Audio. `fail` makes the permission be denied. */
function mockAudio({ fail = false }: { fail?: boolean } = {}): FakeAudio {
  const stopTrack = vi.fn()
  const closeContext = vi.fn(() => Promise.resolve())
  const connect = vi.fn()
  const analyser = { fftSize: 0, getByteTimeDomainData: vi.fn() }
  const getUserMedia = vi.fn(() =>
    fail
      ? Promise.reject(new DOMException('denied', 'NotAllowedError'))
      : Promise.resolve({ getTracks: () => [{ stop: stopTrack }] }),
  )
  const mediaRecorder = vi.fn()

  class FakeAudioContext {
    createAnalyser = () => analyser
    createMediaStreamSource = () => ({ connect })
    close = closeContext
    destination = {}
  }
  vi.stubGlobal('AudioContext', FakeAudioContext)
  vi.stubGlobal('MediaRecorder', mediaRecorder)
  Object.defineProperty(navigator, 'mediaDevices', {
    value: { getUserMedia },
    configurable: true,
  })
  return { stopTrack, closeContext, analyser, getUserMedia, connect, mediaRecorder }
}

describe('DORA section: states (003-19)', () => {
  it('has the group "Estado de DORA" with Reposo, Escuchando, Pensando and Hablando using aria-pressed', () => {
    render(<DoraSection />)
    const group = screen.getByRole('group', { name: es.dora.statesLabel })
    const buttons = within(group).getAllByRole('button')
    expect(buttons.map((b) => b.textContent)).toEqual([
      'Reposo',
      'Escuchando',
      'Pensando',
      'Hablando',
    ])
    expect(buttons.map((b) => b.getAttribute('aria-pressed'))).toEqual([
      'true',
      'false',
      'false',
      'false',
    ])
  })

  it('changes the caption, labeled as AI, and the pressed state when another one is chosen', async () => {
    render(<DoraSection />)
    expect(screen.getByText(es.dora.captions.idle)).toBeInTheDocument()
    expect(screen.getByText('DORA · inteligencia artificial')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Hablando' }))
    expect(screen.getByRole('button', { name: 'Hablando' })).toHaveAttribute('aria-pressed', 'true')
    expect(screen.getByRole('button', { name: 'Reposo' })).toHaveAttribute('aria-pressed', 'false')
    expect(screen.getByText(es.dora.captions.speaking)).toBeInTheDocument()
    expect(screen.getByText('DORA · inteligencia artificial')).toBeInTheDocument()
  })

  it('puts the caption in a polite live region', () => {
    render(<DoraSection />)
    expect(screen.getByText(es.dora.captions.idle).closest('[aria-live]')).toHaveAttribute(
      'aria-live',
      'polite',
    )
  })

  it('does not play any sound: the voice is only visual', async () => {
    const play = vi.fn()
    vi.stubGlobal(
      'Audio',
      vi.fn(() => ({ play })),
    )
    render(<DoraSection />)
    await userEvent.click(screen.getByRole('button', { name: 'Hablando' }))
    expect(play).not.toHaveBeenCalled()
    expect(document.querySelector('audio')).toBeNull()
  })
})

describe('DORA section: microphone (003-21, 003-22)', () => {
  beforeEach(() => {
    vi.spyOn(Math, 'random').mockReturnValue(0.5)
  })
  afterEach(() => {
    vi.restoreAllMocks()
    Reflect.deleteProperty(navigator, 'mediaDevices')
  })

  it('does not ask for the microphone until the person asks for it', () => {
    const audio = mockAudio()
    render(<DoraSection />)
    expect(audio.getUserMedia).not.toHaveBeenCalled()
  })

  it('asks for permission only when activated, tells the audio stays in the browser and changes the button', async () => {
    const audio = mockAudio()
    render(<DoraSection />)

    await userEvent.click(screen.getByRole('button', { name: es.dora.mic.start }))

    expect(audio.getUserMedia).toHaveBeenCalledTimes(1)
    expect(audio.getUserMedia).toHaveBeenCalledWith({ audio: true })
    expect(await screen.findByRole('button', { name: es.dora.mic.stop })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveTextContent(
      'El audio se queda en tu navegador y no se guarda.',
    )
    expect(screen.getByRole('button', { name: 'Escuchando' })).toHaveAttribute(
      'aria-pressed',
      'true',
    )
  })

  it('only feeds an AnalyserNode: no MediaRecorder, no network, no storage, no output', async () => {
    const audio = mockAudio()
    const fetchSpy = vi.fn()
    vi.stubGlobal('fetch', fetchSpy)
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    render(<DoraSection />)

    await userEvent.click(screen.getByRole('button', { name: es.dora.mic.start }))
    await screen.findByRole('button', { name: es.dora.mic.stop })

    expect(audio.analyser.fftSize).toBe(512)
    expect(audio.connect).toHaveBeenCalledWith(audio.analyser)
    expect(audio.mediaRecorder).not.toHaveBeenCalled()
    expect(fetchSpy).not.toHaveBeenCalled()
    expect(setItem).not.toHaveBeenCalled()
  })

  it('releases the tracks and the audio context when stopped', async () => {
    const audio = mockAudio()
    render(<DoraSection />)
    await userEvent.click(screen.getByRole('button', { name: es.dora.mic.start }))
    await userEvent.click(await screen.findByRole('button', { name: es.dora.mic.stop }))

    expect(audio.stopTrack).toHaveBeenCalledTimes(1)
    expect(audio.closeContext).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: es.dora.mic.start })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reposo' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('releases them when another state is chosen', async () => {
    const audio = mockAudio()
    render(<DoraSection />)
    await userEvent.click(screen.getByRole('button', { name: es.dora.mic.start }))
    await screen.findByRole('button', { name: es.dora.mic.stop })

    await userEvent.click(screen.getByRole('button', { name: 'Pensando' }))

    expect(audio.stopTrack).toHaveBeenCalledTimes(1)
    expect(audio.closeContext).toHaveBeenCalledTimes(1)
    expect(screen.getByRole('button', { name: es.dora.mic.start })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Pensando' })).toHaveAttribute('aria-pressed', 'true')
  })

  it('releases them when leaving the page', async () => {
    const audio = mockAudio()
    const { unmount } = render(<DoraSection />)
    await userEvent.click(screen.getByRole('button', { name: es.dora.mic.start }))
    await screen.findByRole('button', { name: es.dora.mic.stop })

    unmount()

    expect(audio.stopTrack).toHaveBeenCalledTimes(1)
    expect(audio.closeContext).toHaveBeenCalledTimes(1)
  })

  it('shows the message and keeps everything else working when the permission is denied', async () => {
    mockAudio({ fail: true })
    render(<DoraSection />)
    await userEvent.click(screen.getByRole('button', { name: es.dora.mic.start }))

    expect(await screen.findByText(es.dora.mic.failed)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: es.dora.mic.start })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Hablando' }))
    expect(screen.getByText(es.dora.captions.speaking)).toBeInTheDocument()
  })

  it('shows the same message when the browser has no microphone API (for example without HTTPS)', async () => {
    Object.defineProperty(navigator, 'mediaDevices', { value: undefined, configurable: true })
    render(<DoraSection />)
    await userEvent.click(screen.getByRole('button', { name: es.dora.mic.start }))
    expect(await screen.findByText(es.dora.mic.failed)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Escuchando' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  })

  it('releases the stream if the page is left while the permission is still pending', async () => {
    const audio = mockAudio()
    let resolve: (stream: unknown) => void = () => {}
    audio.getUserMedia.mockImplementationOnce(
      () =>
        new Promise((r) => {
          resolve = r
        }),
    )
    const { unmount } = render(<DoraSection />)
    await userEvent.click(screen.getByRole('button', { name: es.dora.mic.start }))
    unmount()
    await act(async () => {
      resolve({ getTracks: () => [{ stop: audio.stopTrack }] })
    })
    expect(audio.stopTrack).toHaveBeenCalledTimes(1)
  })
})

describe('DORA section: reduced motion (003-24)', () => {
  it('says that the orb is not animated, and the microphone stays available', () => {
    mockReducedMotion(true)
    render(<DoraSection />)
    expect(screen.getByText(es.dora.reducedMotion)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: es.dora.mic.start })).toBeEnabled()
  })

  it('does not show the note when motion is allowed', () => {
    mockReducedMotion(false)
    render(<DoraSection />)
    expect(screen.queryByText(es.dora.reducedMotion)).not.toBeInTheDocument()
  })
})

describe('DORA section: no canvas (003-25)', () => {
  it('keeps the states and the caption working with a simple circle', async () => {
    HTMLCanvasElement.prototype.getContext = vi.fn(
      () => null,
    ) as unknown as typeof HTMLCanvasElement.prototype.getContext
    const { container } = render(<DoraSection />)
    expect(container.querySelector('canvas')).toBeNull()
    expect(container.querySelector('.dora-orb__circle')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Pensando' }))
    expect(screen.getByText(es.dora.captions.thinking)).toBeInTheDocument()
  })
})
