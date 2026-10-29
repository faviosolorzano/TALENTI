import { act, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { es } from '../../../i18n/es'
import { clearCsrfCookie } from '../../../test/fakeIdentity'
import {
  FAKE_ANSWER,
  FAKE_OFFER,
  FakePeer,
  installFakeRealtimeBrowser,
} from '../../../test/fakeRealtime'
import { openStudentApp } from '../../../test/studentApp'

const r = es.doraRealtime
const c = es.doraCall
const t = es.workflows

const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })

type Handler = (path: string, init?: RequestInit) => Response | Promise<Response> | undefined

function mount(path: string, handler: Handler) {
  const app = openStudentApp(path)
  const fallback = globalThis.fetch
  const requests: { path: string; method: string; body: unknown; keepalive: boolean }[] = []
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    const apiPath = String(input).split('/api/v1')[1] ?? ''
    if (init?.method && init.method !== 'GET' && !apiPath.startsWith('/auth'))
      requests.push({
        path: apiPath,
        method: init.method,
        body: init.body ? JSON.parse(String(init.body)) : null,
        keepalive: Boolean(init.keepalive),
      })
    return Promise.resolve(handler(apiPath, init) ?? fallback(input, init))
  })
  return { ...app, requests }
}

function options(dora: unknown) {
  return {
    bases: [{ basis: 'PROFILE', available: true, vacancies: [] }],
    topics: [],
    maxQuestions: 3,
    quota: { used: 0, limit: 5, resetsAt: '2026-11-01T00:00:00Z' },
    voiceAvailable: true,
    interviewNotice: { version: 'v2', text: 'Aviso de prueba' },
    dora,
  }
}

const AVAILABLE = { provider: 'OPENAI', realtime: { available: true } }

function session(overrides: Record<string, unknown> = {}) {
  return {
    id: 'rt',
    mode: 'PRACTICE',
    basis: 'PROFILE',
    topic: null,
    questionCount: 2,
    voice: true,
    status: 'CREATED',
    channel: 'TURN_BASED',
    nextPosition: 1,
    answered: 0,
    degraded: [],
    createdAt: '2026-10-28T15:00:00Z',
    questions: [
      {
        position: 1,
        text: 'Explica tu proyecto',
        source: 'BANK',
        generatedBy: 'BANK',
        answer: null,
      },
      { position: 2, text: '¿Qué aprendiste?', source: 'BANK', generatedBy: 'BANK', answer: null },
    ],
    ...overrides,
  }
}

function realtimeBackend(state: { session: Record<string, unknown>; dora?: unknown }): Handler {
  return (path) => {
    if (path === '/practice/options') return json(options(state.dora ?? AVAILABLE))
    if (path === '/practice/sessions/rt') return json(state.session)
    if (path === '/dora/sessions/rt/realtime')
      return json(
        {
          callId: 'call-1',
          sdp: FAKE_ANSWER,
          endsAt: new Date(Date.now() + 15 * 60_000).toISOString(),
          warnAt: new Date(Date.now() + 13 * 60_000).toISOString(),
          startPhase: 'GREETING',
          startPosition: null,
          events: { channel: 'oai-events' },
        },
        201,
      )
    if (path === '/dora/sessions/rt/realtime/events')
      return json({ accepted: 1, duplicates: 0, rejected: 0 })
    if (path === '/dora/sessions/rt/realtime/hangup') {
      state.session = { ...state.session, channel: 'TURN_BASED', status: 'IN_PROGRESS' }
      return json({ session: state.session, nextPosition: 1 })
    }
    return undefined
  }
}

let restore: (() => void) | null = null
afterEach(() => {
  restore?.()
  restore = null
  clearCsrfCookie()
})

async function startCall(app: ReturnType<typeof mount>) {
  await app.user.click(await screen.findByRole('button', { name: r.startCall }))
  await waitFor(() => expect(FakePeer.instances).toHaveLength(1))
  await waitFor(() => expect(FakePeer.last.remote).not.toBeNull())
  act(() => FakePeer.last.channel.open())
}

it('012-58: with DORA simulated the screen says so plainly and never asks for the microphone', async () => {
  const browser = installFakeRealtimeBrowser()
  restore = browser.restore
  mount(
    '/practicar/rt',
    realtimeBackend({
      session: session(),
      dora: { provider: 'SIMULATED', realtime: { available: false } },
    }),
  )
  expect(await screen.findByText(r.reasons.simulated)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: r.startCall })).toBeNull()
  expect(screen.getByRole('button', { name: t.start })).toBeInTheDocument()
  expect(browser.getUserMedia).not.toHaveBeenCalled()
})

it('012-58: a browser without WebRTC goes by turns and says why', async () => {
  mount('/practicar/rt', realtimeBackend({ session: session() }))
  expect(await screen.findByText(r.reasons.unsupported)).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: r.startCall })).toBeNull()
})

it('012-58/59/60/61/66: the call asks for the microphone only on start, shows real states and a labelled live transcript', async () => {
  const browser = installFakeRealtimeBrowser()
  restore = browser.restore
  const app = mount('/practicar/rt', realtimeBackend({ session: session() }))
  await screen.findByRole('button', { name: r.startCall })
  expect(browser.getUserMedia).not.toHaveBeenCalled()
  await startCall(app)
  expect(browser.getUserMedia).toHaveBeenCalledTimes(1)
  expect(app.requests.find((q) => q.path === '/dora/sessions/rt/realtime')?.body).toEqual({
    sdp: FAKE_OFFER,
  })
  const status = screen.getByText(r.statuses.listening)
  expect(status).toHaveAttribute('role', 'status')
  expect(screen.getByText(new RegExp(r.timeLeft))).toBeInTheDocument()

  act(() => FakePeer.last.channel.emit({ type: 'output_audio_buffer.started' }))
  expect(status).toHaveTextContent(r.statuses.speaking)
  // The person interrupts: the screen stops showing DORA speaking at once.
  act(() => FakePeer.last.channel.emit({ type: 'input_audio_buffer.speech_started' }))
  expect(status).toHaveTextContent(r.statuses.listening)

  act(() =>
    FakePeer.last.channel.emit({
      type: 'response.output_audio_transcript.done',
      item_id: 'd1',
      transcript: 'Hola, Ana. Soy DORA, una asistente de inteligencia artificial.',
    }),
  )
  act(() =>
    FakePeer.last.channel.emit({
      type: 'conversation.item.input_audio_transcription.completed',
      item_id: 'p1',
      transcript: 'Hola DORA',
    }),
  )
  await app.user.click(screen.getByRole('button', { name: c.transcript }))
  const dialog = screen.getByRole('dialog')
  const doraLine = within(dialog)
    .getByText(/Soy DORA/)
    .closest('li')!
  expect(doraLine).toHaveTextContent(c.assistant)
  expect(doraLine).toHaveTextContent(t.ai)
  expect(within(dialog).getByText('Hola DORA').closest('li')).toHaveTextContent(c.you)
  await app.user.keyboard('{Escape}')
  expect(screen.getByRole('button', { name: c.transcript })).toHaveFocus()

  await waitFor(() =>
    expect(app.requests.filter((q) => q.path === '/dora/sessions/rt/realtime/events')).toHaveLength(
      1,
    ),
  )

  // Mute by keyboard: the control has a name and a pressed state.
  const mute = screen.getByRole('button', { name: r.mute })
  mute.focus()
  await app.user.keyboard('{Enter}')
  expect(screen.getByRole('button', { name: r.unmute })).toHaveAttribute('aria-pressed', 'true')
  expect(browser.mic.track.enabled).toBe(false)
  expect(status).toHaveTextContent(r.muted)

  // Writing goes over the data channel.
  await app.user.click(screen.getByRole('button', { name: c.write }))
  await app.user.type(screen.getByRole('textbox', { name: r.textLabel }), 'Uso React')
  await app.user.click(screen.getByRole('button', { name: r.sendText }))
  expect(FakePeer.last.channel.sent.at(-2)).toMatchObject({
    type: 'conversation.item.create',
    item: { content: [{ type: 'input_text', text: 'Uso React' }] },
  })
})

it('012-58/63: with the microphone denied it offers turns and opens the written answer', async () => {
  const browser = installFakeRealtimeBrowser({ deny: true })
  restore = browser.restore
  const app = mount('/practicar/rt', realtimeBackend({ session: session() }))
  await app.user.click(await screen.findByRole('button', { name: r.startCall }))
  expect(await screen.findByRole('alert')).toHaveTextContent(r.problems['mic-denied'])
  expect(screen.queryByRole('button', { name: r.retry })).toBeNull()
  await app.user.click(screen.getByRole('button', { name: r.turns }))
  expect(await screen.findByText(r.reasons['mic-denied'])).toBeInTheDocument()
  expect(app.requests.some((q) => q.path.endsWith('/hangup'))).toBe(false)
})

it('012-63: a dropped call offers retry or turns; turns hangs up FALLBACK and keeps what was said', async () => {
  const browser = installFakeRealtimeBrowser()
  restore = browser.restore
  const state = { session: session() }
  const app = mount('/practicar/rt', realtimeBackend(state))
  await startCall(app)
  act(() =>
    FakePeer.last.channel.emit({
      type: 'conversation.item.input_audio_transcription.completed',
      item_id: 'p1',
      transcript: 'Mi proyecto es una tienda',
    }),
  )
  act(() => FakePeer.last.channel.drop())
  expect(await screen.findByRole('alert')).toHaveTextContent(r.problems.connection)
  expect(screen.getByRole('button', { name: r.retry })).toBeInTheDocument()
  await app.user.click(screen.getByRole('button', { name: r.turns }))
  await screen.findByText('Explica tu proyecto')
  const hangup = app.requests.find((q) => q.path === '/dora/sessions/rt/realtime/hangup')
  expect(hangup?.body).toEqual({ callId: 'call-1', reason: 'FALLBACK' })
  const events = app.requests.filter((q) => q.path.endsWith('/realtime/events'))
  expect(events.length).toBeGreaterThan(0)
  expect(app.requests.indexOf(events.at(-1)!)).toBeLessThan(app.requests.indexOf(hangup!))
  await app.user.click(screen.getByRole('button', { name: c.transcript }))
  expect(within(screen.getByRole('dialog')).getByText('Mi proyecto es una tienda')).toBeVisible()
})

it('012-65: leaving during the call asks first, then closes everything and hangs up with keepalive', async () => {
  const browser = installFakeRealtimeBrowser()
  restore = browser.restore
  const app = mount('/practicar/rt', realtimeBackend({ session: session() }))
  await startCall(app)
  const peer = FakePeer.last
  await act(async () => {
    await app.router.navigate('/practicar')
  })
  const dialog = await screen.findByRole('dialog', { name: r.leaveTitle })
  await app.user.click(within(dialog).getByRole('button', { name: r.stay }))
  expect(peer.closed).toBe(false)
  await act(async () => {
    await app.router.navigate('/practicar')
  })
  await app.user.click(await screen.findByRole('button', { name: r.leave }))
  expect(peer.closed).toBe(true)
  expect(peer.channel.closed).toBe(true)
  expect(browser.mic.track.stop).toHaveBeenCalled()
  await waitFor(() =>
    expect(app.requests.find((q) => q.path.endsWith('/realtime/hangup'))).toMatchObject({
      body: { callId: 'call-1', reason: 'USER_LEFT' },
      keepalive: true,
    }),
  )
})

it('012-64: an open practice offers continue or close-and-start, with a single new attempt', async () => {
  let creates = 0
  const app = mount('/practicar', (path, init) => {
    if (path === '/practice/options') return json(options(AVAILABLE))
    if (path.startsWith('/practice/sessions?'))
      return json({ items: [], page: 0, size: 20, total: 0, totalPages: 0 })
    if (path === '/practice/sessions' && init?.method === 'POST') {
      creates++
      return creates === 1
        ? json(
            {
              code: 'session-open',
              openSession: {
                id: 'abierta',
                mode: 'PRACTICE',
                status: 'IN_PROGRESS',
                channel: 'REALTIME',
              },
            },
            409,
          )
        : json(session({ id: 'nueva' }), 201)
    }
    if (path === '/dora/sessions/abierta/finish')
      return json(session({ id: 'abierta', status: 'COMPLETED' }))
    if (path === '/practice/sessions/nueva') return json(session({ id: 'nueva' }))
    return undefined
  })
  await app.user.click(await screen.findByRole('button', { name: t.start }))
  const dialog = await screen.findByRole('dialog', { name: r.openTitle })
  expect(within(dialog).getByRole('link', { name: r.continue })).toHaveAttribute(
    'href',
    '/practicar/abierta',
  )
  expect(screen.queryByText(/historial/)).toBeNull()
  await app.user.click(within(dialog).getByRole('button', { name: r.closeAndStart }))
  await waitFor(() => expect(app.router.state.location.pathname).toBe('/practicar/nueva'))
  expect(creates).toBe(2)
  expect(app.requests.map((q) => q.path)).toEqual([
    '/practice/sessions',
    '/dora/sessions/abierta/finish',
    '/practice/sessions',
  ])
})

it('012-64: an open interview only offers to go to it, and an older 409 asks for the open session once', async () => {
  let opens = 0
  const app = mount('/practicar', (path, init) => {
    if (path === '/practice/options') return json(options(AVAILABLE))
    if (path.startsWith('/practice/sessions?'))
      return json({ items: [], page: 0, size: 20, total: 0, totalPages: 0 })
    if (path === '/practice/sessions' && init?.method === 'POST')
      return json({ code: 'session-open' }, 409)
    if (path === '/practice/sessions/open') {
      opens++
      return json({ id: 'ent', mode: 'INTERVIEW', status: 'IN_PROGRESS', applicationId: 'app-7' })
    }
    return undefined
  })
  await app.user.click(await screen.findByRole('button', { name: t.start }))
  const dialog = await screen.findByRole('dialog', { name: r.openTitle })
  expect(within(dialog).getByRole('link', { name: r.goInterview })).toHaveAttribute(
    'href',
    '/postulaciones/app-7/entrevista',
  )
  expect(within(dialog).queryByRole('button', { name: r.closeAndStart })).toBeNull()
  expect(opens).toBe(1)
})

it('012-58: the evaluated interview also uses the real-time call and keeps its own label', async () => {
  const browser = installFakeRealtimeBrowser()
  restore = browser.restore
  mount('/postulaciones/app-7/entrevista', (path) => {
    if (path === '/practice/options') return json(options(AVAILABLE))
    if (path === '/applications/app-7/interview')
      return json({
        applicationId: 'app-7',
        sessionId: 'ent-1',
        status: 'CREATED',
        channel: 'TURN_BASED',
        attempt: 1,
        maxAttempts: 2,
        canRetry: false,
        nextPosition: 1,
        questions: [
          {
            position: 1,
            text: 'Pregunta de la vacante',
            generatedBy: 'BANK',
            transcriptRaw: null,
            transcriptFinal: null,
          },
        ],
      })
    return undefined
  })
  expect(await screen.findByRole('button', { name: r.startCall })).toBeInTheDocument()
  expect(screen.getByRole('region', { name: t.interview })).toHaveTextContent(c.interviewPrivacy)
  expect(browser.getUserMedia).not.toHaveBeenCalled()
})
