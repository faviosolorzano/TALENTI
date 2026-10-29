import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page, type Route } from '@playwright/test'
import { mkdirSync } from 'node:fs'
import { es } from '../src/i18n/es'

/**
 * Spec 014, Enmienda 1, en Chrome real con la imagen real (nginx y su CSP), la cámara falsa de Chrome y un backend
 * simulado (`page.route`). La cámara falsa no muestra un rostro: sirve para comprobar que MediaPipe y sus modelos
 * cargan con la CSP y que la calibración sin rostro pide repetir. Para recorrer el examen se simula que los modelos
 * no cargan (014-40), así que la detección real de giros queda para la prueba manual.
 */

const t = es.workflows
const p = t.proctoring
const output = process.env.E2E_EXAM_OUTPUT ?? 'test-results/exam-proctoring'

test.use({
  permissions: ['camera'],
  launchOptions: {
    args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'],
  },
})
test.describe.configure({ timeout: 120000 })

interface Backend {
  events: { kind: string; data?: Record<string, unknown> }[]
  snapshots: { reason: string; bytes: number; jpeg: boolean }[]
  start: unknown[]
  status: string
}

function attempt(status: string) {
  return {
    id: 'attempt-e2e',
    applicationId: 'app-e2e',
    status,
    gradingStatus: 'NOT_STARTED',
    noticeVersion: 'v2',
    notice: 'Reglas del examen (v2) de prueba.',
    startedAt: null,
    deadlineAt: null,
    remainingSeconds: 3600,
    events: [],
    answers: [],
    proctoring: { required: true, thresholds: { calibrationMs: 2000 }, snapshotInterval: 60 },
    watermark: { name: 'Ana Prueba', code: 'T-APPE2E' },
    questions: [
      {
        id: 'q1',
        type: 'OPEN',
        prompt: 'Explica cómo evitarías una consulta N+1.',
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

async function mockBackend(page: Page): Promise<Backend> {
  const backend: Backend = { events: [], snapshots: [], start: [], status: 'NOT_STARTED' }
  await page
    .context()
    .addCookies([{ name: 'XSRF-TOKEN', value: 'token-de-prueba', url: 'http://localhost' }])
  await page.route('**/api/v1/**', async (route: Route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname.replace('/api/v1', '')
    const json = (body: unknown, status = 200) => route.fulfill({ status, json: body })
    if (path === '/auth/me')
      return json({
        id: '11111111-1111-4111-8111-111111111111',
        email: 'ana.prueba@example.test',
        role: 'STUDENT',
        legalAcceptancePending: false,
      })
    if (path === '/auth/csrf') return route.fulfill({ status: 204 })
    if (path === '/applications/app-e2e/exam') return json(attempt(backend.status))
    if (path === '/exam-attempts/attempt-e2e') return json(attempt(backend.status))
    if (path === '/exam-attempts/attempt-e2e/start') {
      backend.start.push(request.postDataJSON())
      backend.status = 'IN_PROGRESS'
      return json(attempt('IN_PROGRESS'))
    }
    if (path === '/exam-attempts/attempt-e2e/events') {
      backend.events.push(request.postDataJSON() as Backend['events'][number])
      return json({ rule: 'NONE', message: '', status: 'IN_PROGRESS' })
    }
    if (path === '/exam-attempts/attempt-e2e/snapshots') {
      const body = request.postDataBuffer() ?? Buffer.alloc(0)
      const text = body.toString('latin1')
      const reason = /name="reason"\r\n\r\n([A-Z]+)/.exec(text)?.[1] ?? ''
      const start = body.indexOf(Buffer.from([0xff, 0xd8, 0xff]))
      const end = body.lastIndexOf(Buffer.from([0xff, 0xd9]))
      backend.snapshots.push({ reason, bytes: end > start ? end - start + 2 : 0, jpeg: start > 0 })
      return json({ id: 'snap', takenAt: new Date().toISOString() }, 201)
    }
    return json({ code: 'not-found' }, 404)
  })
  return backend
}

test('014-45: MediaPipe y sus modelos cargan con la CSP real; sin rostro, la calibración pide repetir', async ({
  page,
}) => {
  const errors: string[] = []
  page.on('console', (message) => {
    if (message.type() === 'error' && /Content Security Policy|CSP/i.test(message.text()))
      errors.push(message.text())
  })
  const external: string[] = []
  page.on('request', (request) => {
    if (!request.url().startsWith('http://localhost')) external.push(request.url())
  })
  await mockBackend(page)
  await page.goto('/postulaciones/app-e2e/examen')
  await page.getByRole('button', { name: p.begin }).click()
  await expect(page.getByText(p.stepCalibrating)).toBeVisible({ timeout: 60000 })
  await expect(page.getByText(p.calibrationFrames)).toBeVisible({ timeout: 30000 })
  await expect(page.getByText(p.detectionUnavailable)).toHaveCount(0)
  // Nada del motor ni de los modelos viene de otro origen; la telemetría de MediaPipe, si la intenta, la frena la CSP.
  expect(external.filter((url) => !url.includes('googleapis.com/v1/log'))).toEqual([])
  expect(errors.filter((e) => !/googleapis/.test(e))).toEqual([])
})

test('014-38, 014-40, 014-42, 014-44, 014-53: empezar, foto inicial, bloqueos y pantalla completa', async ({
  page,
}) => {
  mkdirSync(output, { recursive: true })
  // Los modelos no cargan: se rinde con cámara y fotos (014-40).
  await page.route('**/models/**', (route) => route.fulfill({ status: 503, body: '' }))
  const backend = await mockBackend(page)
  await page.goto('/postulaciones/app-e2e/examen')
  for (const width of [360, 768, 1280]) {
    await page.setViewportSize({ width, height: 900 })
    await page.screenshot({ path: `${output}/inicio-${width}.png`, fullPage: true })
  }
  await page.getByRole('button', { name: p.begin }).click()
  await expect(page.getByText(p.detectionUnavailable)).toBeVisible({ timeout: 60000 })
  // 360 px de ancho se considera computadora aquí: el agente de usuario es de escritorio.
  await page.getByRole('button', { name: p.enterAndStart }).click()
  await expect(page.getByText('Explica cómo evitarías una consulta N+1.')).toBeVisible()
  expect(backend.start[0]).toMatchObject({
    cameraReady: true,
    fullscreen: true,
    detection: 'UNAVAILABLE',
  })
  await expect.poll(() => backend.snapshots.length, { timeout: 15000 }).toBeGreaterThan(0)
  const first = backend.snapshots[0]!
  expect(first.reason).toBe('START')
  expect(first.jpeg).toBe(true)
  expect(first.bytes).toBeLessThanOrEqual(150 * 1024)
  await expect.poll(() => backend.events.map((e) => e.kind)).toContain('DETECTION_UNAVAILABLE')

  await expect(page.getByText(p.cameraLabel, { exact: true })).toBeVisible()
  await expect(page.getByTestId('exam-watermark')).toContainText('Ana Prueba · T-APPE2E')
  for (const width of [1280, 768, 360]) {
    await page.setViewportSize({ width, height: 900 })
    await page.screenshot({ path: `${output}/examen-${width}.png`, fullPage: true })
  }
  // La marca de agua es texto decorativo e incidental (WCAG 1.4.3 lo exime): su bajo contraste es a propósito; el
  // texto del examen sí debe cumplir AA (014-43).
  const accessibility = await new AxeBuilder({ page })
    .include('main')
    .exclude('.exam-watermark')
    .analyze()
  expect(
    accessibility.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious'),
  ).toEqual([])

  // Con el texto del enunciado no se puede seleccionar; se prueba copiar desde la respuesta.
  const editor = page.getByRole('textbox')
  await editor.fill('Usaría JOIN FETCH.')
  await editor.press('Control+A')
  await editor.press('Control+C')
  await editor.press('Control+P')
  await expect
    .poll(() => backend.events.map((e) => e.kind))
    .toEqual(expect.arrayContaining(['COPY_ATTEMPT', 'PRINT_ATTEMPT']))
  await editor.press('End')
  await expect(editor).toHaveValue('Usaría JOIN FETCH.')

  await page.evaluate(() => document.exitFullscreen())
  await expect(page.getByText(p.leftFullscreen)).toBeVisible()
  await expect(page.getByRole('textbox')).toBeHidden()
  await page.screenshot({ path: `${output}/tapado-360.png`, fullPage: true })
  await expect.poll(() => backend.events.map((e) => e.kind)).toContain('SCREEN_LEFT')
  await page.getByRole('button', { name: p.returnFullscreen }).click()
  await expect(page.getByRole('textbox')).toHaveValue('Usaría JOIN FETCH.')
})
