import { act, screen, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { openStudentApp } from '../../test/studentApp'
import { clearCsrfCookie } from '../../test/fakeIdentity'
import { es } from '../../i18n/es'
import type { ExamAttempt } from '../../api/workflows'
const t = es.workflows
const json = (body: unknown, status = 200) =>
  new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
function mount(
  path: string,
  handler: (path: string, init?: RequestInit) => Response | Promise<Response> | undefined,
) {
  const app = openStudentApp(path)
  const fallback = globalThis.fetch
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) =>
    Promise.resolve(
      handler(String(input).split('/api/v1')[1] ?? '', init) ?? fallback(input, init),
    ),
  )
  return app
}
afterEach(clearCsrfCookie)
it('GitHub disabled reports the actual service limit and never claims a linked account', async () => {
  const { user } = mount('/perfil/github', (path) =>
    path === '/github'
      ? new Response(null, { status: 204 })
      : path === '/github/oauth/start'
        ? json({ code: 'github-oauth-disabled' }, 503)
        : undefined,
  )
  await user.click(await screen.findByRole('button', { name: t.connect }))
  expect(await screen.findByRole('alert')).toHaveTextContent(t.errors['github-oauth-disabled'])
  expect(screen.queryByText(t.linked)).toBeNull()
})
it('a workflow is guarded by sign-in and preserves its deep destination', async () => {
  const app = openStudentApp('/postulaciones/app-test/examen', { signedOut: true })
  await screen.findByRole('heading', { name: es.identity.login.title })
  expect(app.router.state.location.search).toContain(
    encodeURIComponent('/postulaciones/app-test/examen'),
  )
})
function fixture(): ExamAttempt {
  return {
    id: 'attempt-test',
    applicationId: 'app-test',
    status: 'IN_PROGRESS',
    gradingStatus: 'NOT_STARTED',
    noticeVersion: 'v1',
    notice: 'Aviso aprobado de prueba',
    startedAt: '2026-10-27T18:00:00Z',
    deadlineAt: '2026-10-27T19:00:00Z',
    remainingSeconds: 3600,
    events: [],
    answers: [],
    questions: [
      {
        id: 'q1',
        type: 'CHOICE',
        prompt: 'Pregunta de prueba',
        generatedBy: 'BANK',
        options: [
          { id: 'a', text: 'Primera opción' },
          { id: 'b', text: 'Segunda opción' },
        ],
        rubric: [],
        language: null,
        kind: null,
        initialCode: null,
        cases: [],
      },
      {
        id: 'q2',
        type: 'OPEN',
        prompt: 'Explica tu decisión',
        generatedBy: 'BANK',
        options: [],
        rubric: [],
        language: null,
        kind: null,
        initialCode: null,
        cases: [],
      },
    ],
  }
}
it('exam saves with the exact revision and restores an answer when returning to its question', async () => {
  const data = fixture()
  const writes: unknown[] = []
  const { user } = mount('/postulaciones/app-test/examen', (path, init) => {
    if (path === '/applications/app-test/exam') return json(data)
    if (path.endsWith('/answers/q1')) {
      writes.push(JSON.parse(String(init?.body)))
      return json({ revision: 1, savedAt: '2026-10-27T18:01:00Z' })
    }
    return undefined
  })
  await user.click(await screen.findByRole('radio', { name: 'Primera opción' }))
  await user.click(screen.getByRole('button', { name: t.save }))
  await screen.findByText(t.answerSaved)
  await user.click(screen.getByRole('button', { name: '2' }))
  await user.click(screen.getByRole('button', { name: '1' }))
  expect(screen.getByRole('radio', { name: 'Primera opción' })).toBeChecked()
  expect(writes).toEqual([{ revision: 0, choiceId: 'a' }])
})
it('a failed exam save preserves the answer and prevents switching to a different question', async () => {
  const { user } = mount('/postulaciones/app-test/examen', (path) =>
    path === '/applications/app-test/exam'
      ? json(fixture())
      : path.endsWith('/answers/q1')
        ? json({ code: 'stale-revision' }, 409)
        : undefined,
  )
  await user.click(await screen.findByRole('radio', { name: 'Primera opción' }))
  await user.click(screen.getByRole('button', { name: '2' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(t.errors['stale-revision'])
  expect(screen.getByRole('radio', { name: 'Primera opción' })).toBeChecked()
  expect(screen.queryByText('Explica tu decisión')).toBeNull()
})
it('exam creation waits for explicit acceptance of the actual versioned notice', async () => {
  let writes = 0
  const { user } = mount('/postulaciones/app-test/examen', (path, init) => {
    if (path !== '/applications/app-test/exam') return undefined
    if (init?.method === 'POST') {
      writes++
      expect(JSON.parse(String(init.body))).toEqual({ noticeAccepted: true, noticeVersion: 'v1' })
      return json({ ...fixture(), status: 'NOT_STARTED' })
    }
    return writes
      ? json({ ...fixture(), status: 'NOT_STARTED' })
      : new Response(null, { status: 204 })
  })
  const prepare = await screen.findByRole('button', { name: t.prepareExam })
  expect(prepare).toBeDisabled()
  expect(writes).toBe(0)
  await user.click(screen.getByRole('checkbox', { name: t.acceptNotice }))
  await user.click(prepare)
  await waitFor(() => expect(writes).toBe(1))
  expect(await screen.findByRole('button', { name: t.startExam })).toBeInTheDocument()
})

it('persists supplied initial code when the server answer row exists with null code, before executing', async () => {
  const data = fixture()
  data.questions = [
    {
      ...data.questions[0]!,
      id: 'code1',
      type: 'CODE',
      prompt: 'Corrige esta consulta',
      language: 'SQL',
      initialCode: 'SELECT MIN(n) FROM numbers',
      options: [],
      cases: [],
    },
  ]
  data.answers = [
    {
      questionId: 'code1',
      choiceId: null,
      text: null,
      code: null,
      revision: 0,
      runCount: 0,
      updatedAt: '2026-10-27T18:00:00Z',
    },
  ]
  const calls: string[] = []
  const { user } = mount('/postulaciones/app-test/examen', (path, init) => {
    if (path === '/applications/app-test/exam') return json(data)
    if (path.endsWith('/answers/code1')) {
      calls.push('save')
      expect(JSON.parse(String(init?.body))).toEqual({
        revision: 0,
        code: 'SELECT MIN(n) FROM numbers',
      })
      return json({ revision: 1, savedAt: '2026-10-28T15:00:00Z' })
    }
    if (path.endsWith('/answers/code1/run')) {
      calls.push('run')
      return json({ cases: [{ passed: false, output: '1', error: null, ms: 3, table: null }] })
    }
    return undefined
  })
  await user.click(await screen.findByRole('button', { name: t.run }))
  expect(await screen.findByText('1. ' + t.failed)).toBeInTheDocument()
  expect(calls).toEqual(['save', 'run'])
})

it('locks the current exam input while saving, so a newer edit cannot be discarded by a late response', async () => {
  let release: ((response: Response) => void) | undefined
  const pending = new Promise<Response>((resolve) => {
    release = resolve
  })
  const data = fixture()
  const { user } = mount('/postulaciones/app-test/examen', (path) =>
    path === '/applications/app-test/exam'
      ? json(data)
      : path.endsWith('/answers/q1')
        ? pending
        : undefined,
  )
  await user.click(await screen.findByRole('radio', { name: 'Primera opción' }))
  await user.click(screen.getByRole('button', { name: t.save }))
  try {
    expect(screen.getByRole('radio', { name: 'Segunda opción' })).toBeDisabled()
  } finally {
    release!(json({ revision: 1, savedAt: '2026-10-28T15:00:00Z' }))
  }
  await screen.findByText(t.answerSaved)
  expect(screen.getByRole('radio', { name: 'Primera opción' })).toBeChecked()
})

it('releases a microphone approved after leaving DORA and permits only one permission request', async () => {
  let approve!: (stream: MediaStream) => void
  const stop = vi.fn()
  const permission = new Promise<MediaStream>((resolve) => {
    approve = resolve
  })
  const getUserMedia = vi.fn(() => permission)
  const previous = Object.getOwnPropertyDescriptor(navigator, 'mediaDevices')
  Object.defineProperty(navigator, 'mediaDevices', { configurable: true, value: { getUserMedia } })
  const app = mount('/practicar/mic-test', (path) =>
    path === '/practice/sessions/mic-test'
      ? json({
          id: 'mic-test',
          mode: 'PRACTICE',
          basis: 'PROFILE',
          topic: null,
          questionCount: 1,
          voice: true,
          status: 'IN_PROGRESS',
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
          ],
        })
      : undefined,
  )
  try {
    const record = await screen.findByRole('button', { name: t.record })
    await app.user.click(record)
    await app.user.click(record)
    expect(record).toBeDisabled()
    expect(getUserMedia).toHaveBeenCalledTimes(1)
    app.unmount()
    await act(async () => {
      approve({ getTracks: () => [{ stop }] } as unknown as MediaStream)
      await permission
    })
    expect(stop).toHaveBeenCalledTimes(1)
  } finally {
    if (previous) Object.defineProperty(navigator, 'mediaDevices', previous)
    else Reflect.deleteProperty(navigator, 'mediaDevices')
  }
})

it('an expired session during a private read returns to sign-in with the exact destination', async () => {
  let expired = false
  const app = mount('/perfil/github', (path) => {
    if (path !== '/github') return undefined
    if (!expired) return new Response(null, { status: 204 })
    return json({ code: 'session-expired' }, 401)
  })
  await screen.findByRole('button', { name: t.connect })
  expired = true
  app.identity.expireSession()
  await act(async () => {
    await app.router.navigate('/perfil')
  })
  await act(async () => {
    await app.router.navigate('/perfil/github')
  })
  await screen.findByRole('heading', { name: es.identity.login.title })
  expect(app.router.state.location.search).toContain(encodeURIComponent('/perfil/github'))
})

it('switching practice sessions hides the old question while loading and clears its draft', async () => {
  let deliver!: (response: Response) => void
  const pending = new Promise<Response>((resolve) => {
    deliver = resolve
  })
  const session = (id: string, text: string) => ({
    id,
    mode: 'PRACTICE',
    basis: 'PROFILE',
    topic: null,
    questionCount: 1,
    voice: false,
    status: 'IN_PROGRESS',
    nextPosition: 1,
    answered: 0,
    degraded: [],
    createdAt: '2026-10-28T15:00:00Z',
    questions: [{ position: 1, text, source: 'BANK', generatedBy: 'BANK', answer: null }],
  })
  const app = mount('/practicar/primera', (path) =>
    path === '/practice/sessions/primera'
      ? json(session('primera', 'Pregunta anterior'))
      : path === '/practice/sessions/segunda'
        ? pending
        : undefined,
  )
  await screen.findByText('Pregunta anterior')
  await app.user.type(screen.getByRole('textbox', { name: t.answer }), 'Mi borrador anterior')
  await act(async () => {
    await app.router.navigate('/practicar/segunda')
  })
  await screen.findByRole('dialog')
  await app.user.click(screen.getByRole('button', { name: t.cancel }))
  expect(screen.getByRole('textbox', { name: t.answer })).toHaveValue('Mi borrador anterior')
  await act(async () => {
    await app.router.navigate('/practicar/segunda')
  })
  await app.user.click(screen.getByRole('button', { name: t.leaveWithoutSaving }))
  try {
    expect(screen.queryByText('Pregunta anterior')).not.toBeInTheDocument()
  } finally {
    await act(async () => {
      deliver(json(session('segunda', 'Pregunta nueva')))
      await pending
    })
  }
  await screen.findByText('Pregunta nueva')
  expect(screen.getByRole('textbox', { name: t.answer })).toHaveValue('')
})
