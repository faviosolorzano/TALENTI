import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { es } from '../src/i18n/es'

/**
 * 012-68: the real-time screen in real Chrome, with the real image (nginx and its CSP), the
 * fake microphone of Chrome and a simulated backend (`page.route`). WebRTC itself is replaced
 * in the page by a stand-in that opens the data channel and plays a tone as "DORA": this does
 * NOT prove a real connection with the provider (that is the manual test 012-69).
 */

const r = es.doraRealtime
const c = es.doraCall
const output = process.env.E2E_REDESIGN_OUTPUT ?? 'test-results/dora-realtime'
const ANSWER = 'v=0\r\no=- 3 4 IN IP4 127.0.0.1\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n'

test.use({
  permissions: ['microphone'],
  launchOptions: {
    args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'],
  },
})
test.describe.configure({ timeout: 90000 })

/** Replaces RTCPeerConnection before the app loads; `window.__dora` drives the fake provider. */
function fakeWebRtc() {
  type Sent = Record<string, unknown>
  class FakeChannel extends EventTarget {
    readyState: RTCDataChannelState = 'connecting'
    sent: Sent[] = []
    onopen: (() => void) | null = null
    onmessage: ((event: { data: string }) => void) | null = null
    onclose: (() => void) | null = null
    constructor(public label: string) {
      super()
    }
    send(data: string) {
      this.sent.push(JSON.parse(data) as Sent)
    }
    close() {
      this.readyState = 'closed'
    }
  }
  const state: { peers: FakePeer[]; closed: number } = { peers: [], closed: 0 }
  class FakePeer {
    channels: FakeChannel[] = []
    connectionState = 'new'
    ontrack: ((event: { streams: MediaStream[]; track: MediaStreamTrack }) => void) | null = null
    onconnectionstatechange: (() => void) | null = null
    tracks: MediaStreamTrack[] = []
    constructor() {
      state.peers.push(this)
    }
    addTrack(track: MediaStreamTrack) {
      this.tracks.push(track)
    }
    createDataChannel(label: string) {
      const channel = new FakeChannel(label)
      this.channels.push(channel)
      return channel
    }
    async createOffer() {
      return { type: 'offer', sdp: 'v=0\r\nm=audio 9 UDP/TLS/RTP/SAVPF 111\r\n' }
    }
    async setLocalDescription() {}
    async setRemoteDescription() {
      setTimeout(() => {
        const channel = this.channels.at(-1)!
        channel.readyState = 'open'
        channel.onopen?.()
        // DORA's voice: a quiet tone, as a real remote audio stream.
        const context = new AudioContext()
        const tone = context.createOscillator()
        const gain = context.createGain()
        gain.gain.value = 0.05
        const destination = context.createMediaStreamDestination()
        tone.connect(gain).connect(destination)
        tone.start()
        this.ontrack?.({
          streams: [destination.stream],
          track: destination.stream.getAudioTracks()[0]!,
        })
      }, 50)
    }
    close() {
      state.closed++
      this.connectionState = 'closed'
    }
  }
  Object.assign(window, {
    RTCPeerConnection: FakePeer,
    __dora: {
      state,
      emit(event: Record<string, unknown>) {
        const channel = state.peers.at(-1)?.channels.at(-1)
        channel?.onmessage?.({ data: JSON.stringify(event) })
      },
      sent: () => state.peers.at(-1)?.channels.at(-1)?.sent ?? [],
    },
  })
}

interface Backend {
  requests: { path: string; method: string; body: unknown }[]
  session: Record<string, unknown>
}

async function simulatedBackend(page: Page): Promise<Backend> {
  const backend: Backend = {
    requests: [],
    session: {
      id: 'rt',
      mode: 'PRACTICE',
      basis: 'PROFILE',
      topic: null,
      questionCount: 3,
      voice: true,
      status: 'CREATED',
      channel: 'TURN_BASED',
      nextPosition: 1,
      answered: 0,
      degraded: [],
      createdAt: '2026-10-28T15:00:00Z',
      questions: [1, 2, 3].map((position) => ({
        position,
        text: 'Pregunta de prueba ' + position,
        source: 'BANK',
        generatedBy: 'BANK',
        answer: null,
      })),
    },
  }
  await page.context().addCookies([
    {
      name: 'XSRF-TOKEN',
      value: 'token-de-prueba',
      url: page.context().pages()[0]?.url() || 'http://localhost',
    },
  ])
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace('/api/v1', '')
    const method = request.method()
    if (method !== 'GET')
      backend.requests.push({ path, method, body: request.postDataJSON() as unknown })
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })
    if (path === '/auth/me')
      return json({
        id: '11111111-1111-4111-8111-111111111111',
        email: 'ana.prueba@example.test',
        role: 'STUDENT',
        legalAcceptancePending: false,
      })
    if (path === '/auth/csrf') return route.fulfill({ status: 204 })
    if (path === '/practice/options')
      return json({
        bases: [{ basis: 'PROFILE', available: true, vacancies: [] }],
        topics: [],
        maxQuestions: 3,
        quota: { used: 0, limit: 5, resetsAt: '2026-11-01T00:00:00Z' },
        voiceAvailable: true,
        interviewNotice: { version: 'v2', text: 'Aviso' },
        dora: { provider: 'OPENAI', realtime: { available: true } },
      })
    if (path === '/practice/sessions/rt') return json(backend.session)
    if (path === '/dora/sessions/rt/realtime')
      return json(
        {
          callId: 'call-1',
          sdp: ANSWER,
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
    if (path === '/dora/sessions/rt/realtime/hangup')
      return json({ session: backend.session, nextPosition: 1 })
    if (path === '/dora/sessions/rt/finish') {
      backend.session = { ...backend.session, status: 'COMPLETED', nextPosition: null }
      return json(backend.session)
    }
    return json({ code: 'not-found' }, 404)
  })
  return backend
}

async function reflows(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
    true,
  )
}

test('012-59/60/61/65/66: real-time call in Chrome at 360, 768 and 1280 px, accessible and released on hang up', async ({
  page,
}) => {
  await page.addInitScript(fakeWebRtc)
  await page.goto('/ingresar')
  const backend = await simulatedBackend(page)
  const reject = page.getByRole('button', { name: es.cookies.banner.reject, exact: true })
  await page.goto('/practicar/rt')
  if (await reject.count()) await reject.click()
  const start = page.getByRole('button', { name: r.startCall })
  await expect(start).toBeVisible()
  await start.click()
  const status = page.getByRole('status').filter({ hasText: r.statuses.listening })
  await expect(status).toBeVisible()
  expect(backend.requests.find((q) => q.path === '/dora/sessions/rt/realtime')?.body).toEqual({
    sdp: expect.stringContaining('m=audio'),
  })
  await page.evaluate(() => {
    const dora = (window as unknown as { __dora: { emit: (e: object) => void } }).__dora
    dora.emit({ type: 'output_audio_buffer.started' })
    dora.emit({
      type: 'response.output_audio_transcript.done',
      item_id: 'd1',
      transcript: 'Hola, Ana. Soy DORA, una asistente de inteligencia artificial.',
    })
  })
  await expect(page.getByRole('status').filter({ hasText: r.statuses.speaking })).toBeVisible()
  // The orb follows the real level of DORA's tone (never a looping fake).
  await expect
    .poll(() =>
      page.evaluate(() =>
        Number(
          getComputedStyle(document.querySelector('.dora-call__actors')!).getPropertyValue(
            '--dora-level',
          ),
        ),
      ),
    )
    .toBeGreaterThanOrEqual(0)

  mkdirSync(output, { recursive: true })
  for (const width of [360, 768, 1280]) {
    await page.setViewportSize({ width, height: width === 360 ? 780 : 900 })
    await reflows(page)
    for (const name of [r.hangUp, r.mute, c.write, c.transcript])
      await expect(page.getByRole('button', { name })).toBeVisible()
    await page.screenshot({ path: resolve(output, `dora-tiempo-real-${width}.png`) })
  }
  const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa', 'wcag21aa']).analyze()
  expect(axe.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious')).toEqual(
    [],
  )

  // Keyboard: mute and the floating transcript, with focus coming back.
  await page.getByRole('button', { name: r.mute }).focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('button', { name: r.unmute })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: c.transcript }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByText(/Soy DORA/)).toBeVisible()
  await expect(dialog.getByText(es.workflows.ai)).toBeVisible()
  await page.setViewportSize({ width: 360, height: 780 })
  await page.screenshot({ path: resolve(output, 'dora-transcripcion-360.png') })
  await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: c.transcript })).toBeFocused()

  await page.getByRole('button', { name: r.hangUp }).click()
  await page.getByRole('dialog').getByRole('button', { name: r.hangUpShort }).click()
  await expect(page.getByText(es.workflows.closedSession)).toBeVisible()
  expect(backend.requests.map((q) => q.path)).toEqual(
    expect.arrayContaining(['/dora/sessions/rt/realtime/hangup', '/dora/sessions/rt/finish']),
  )
  expect(
    backend.requests.find((q) => q.path === '/dora/sessions/rt/realtime/hangup')?.body,
  ).toEqual({ callId: 'call-1', reason: 'FINISHED' })
  expect(
    await page.evaluate(
      () => (window as unknown as { __dora: { state: { closed: number } } }).__dora.state.closed,
    ),
  ).toBe(1)
})

test('012-46/67: browser events persist in the real isolated backend, including late transcripts', async ({
  page,
}) => {
  test.skip(process.env.E2E_REAL_BACKEND !== '1', 'Needs the isolated simulated-provider backend.')
  await page.addInitScript(fakeWebRtc)
  await page.goto('/registro')
  const reject = page.getByRole('button', { name: es.cookies.banner.reject, exact: true })
  if (await reject.count()) await reject.click()
  await page.getByLabel('Correo', { exact: true }).fill(`realtime.${Date.now()}@example.test`)
  await page.getByLabel('Contraseña', { exact: true }).fill('Prueba-visual-2026')
  await page.getByRole('checkbox', { name: /Leí y acepto/ }).check()
  await page.getByRole('button', { name: es.identity.register.submit, exact: true }).click()
  await expect(page).toHaveURL(/\/$/)
  const options = await (await page.request.get('/api/v1/practice/options')).json()
  const vacancy = options.bases.find((basis: { basis: string }) => basis.basis === 'VACANCY')
    .vacancies[0]
  await page.request.get('/api/v1/auth/csrf')
  const csrf = (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')!
  const response = await page.request.post('/api/v1/practice/sessions', {
    headers: { 'X-XSRF-TOKEN': decodeURIComponent(csrf.value) },
    data: { basis: 'VACANCY', vacancyId: vacancy.id, questionCount: 2, voice: true },
  })
  expect(response.status()).toBe(201)
  const session = await response.json()
  // Only availability is adjusted to exercise the UI: the provider and WebRTC are simulated.
  // Every session, call, event, answer, feedback and finish request reaches the actual backend.
  await page.route('**/api/v1/practice/options', async (route) => {
    const real = await route.fetch()
    const body = await real.json()
    body.dora = { provider: 'OPENAI', realtime: { available: true } }
    await route.fulfill({ response: real, json: body })
  })
  await page.goto('/practicar/' + session.id)
  await page.getByRole('button', { name: r.startCall }).click()
  await expect(page.getByRole('status').filter({ hasText: r.statuses.listening })).toBeVisible()
  await page.evaluate(async () => {
    const emit = (window as unknown as { __dora: { emit: (e: object) => void } }).__dora.emit
    const stage = (position: number) =>
      emit({
        type: 'response.function_call_arguments.done',
        name: 'set_stage',
        call_id: 'question-' + position,
        arguments: JSON.stringify({ phase: 'QUESTION', position }),
      })
    stage(1)
    await new Promise((resolve) => setTimeout(resolve, 30))
    emit({ type: 'input_audio_buffer.speech_started', item_id: 'late-answer' })
    emit({ type: 'input_audio_buffer.speech_stopped', item_id: 'late-answer' })
    await new Promise((resolve) => setTimeout(resolve, 30))
    stage(2)
    emit({
      type: 'conversation.item.input_audio_transcription.completed',
      item_id: 'late-answer',
      transcript: 'Uso Java y SQL en mi proyecto de inventario.',
    })
  })
  await expect
    .poll(async () => {
      const detail = await (
        await page.request.get('/api/v1/practice/sessions/' + session.id)
      ).json()
      return detail.questions[0].answer?.transcriptFinal
    })
    .toBe('Uso Java y SQL en mi proyecto de inventario.')
  const detail = await (await page.request.get('/api/v1/practice/sessions/' + session.id)).json()
  expect(detail.channel).toBe('REALTIME')
  expect(detail.questions[1].answer).toBeNull()
  await page.getByRole('button', { name: r.hangUp }).click()
  await page.getByRole('dialog').getByRole('button', { name: r.hangUpShort }).click()
  await expect(page.getByText(es.workflows.closedSession)).toBeVisible()
  const finished = await (await page.request.get('/api/v1/practice/sessions/' + session.id)).json()
  expect(finished.status).toBe('COMPLETED')
  expect(finished.questions[0].answer.transcriptFinal).toBe(
    'Uso Java y SQL en mi proyecto de inventario.',
  )
})
