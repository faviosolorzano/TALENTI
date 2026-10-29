import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { mkdirSync } from 'node:fs'
import { es } from '../src/i18n/es'

const t = es.workflows
const p = t.proctoring
const output = process.env.E2E_EXAM_OUTPUT ?? 'test-results/exam-proctoring-real'
test.skip(process.env.E2E_REAL_BACKEND !== '1', 'Solo entorno aislado con proveedores simulados.')
test.use({
  permissions: ['camera'],
  launchOptions: {
    args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream'],
  },
})
test.describe.configure({ timeout: 180000 })

async function csrf(page: Page) {
  await page.request.get('/api/v1/auth/csrf')
  const token = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value
  return { 'X-XSRF-TOKEN': token }
}

test('014: cámara falsa y servidor real conservan respuestas, fotos, eventos y retiro', async ({
  page,
  baseURL,
}) => {
  // Esta prueba nunca debe tocar la web ni la cuenta privada de Favio.
  expect(new URL(baseURL!).port).toBe('3091')
  mkdirSync(output, { recursive: true })
  const login = await page.request.post('/api/v1/auth/login', {
    headers: await csrf(page),
    data: { email: 'demo@example.test', password: 'Demo-Talenti-2026' },
  })
  expect(login.status()).toBe(200)
  const list = await (await page.request.get('/api/v1/vacancies?size=24')).json()
  const vacancy = list.items[0].id as string
  // Permite repetir tras una interrupción sin reutilizar un examen ni tocar otras cuentas.
  const previousResponse = await page.request.get(
    `/api/v1/applications?size=20&vacancyId=${vacancy}`,
  )
  expect(previousResponse.status()).toBe(200)
  const previous = await previousResponse.json()
  for (const item of previous.items) {
    if (item.status === 'WITHDRAWN' || item.status === 'EXPIRED') continue
    const cleanup = await page.request.post(`/api/v1/applications/${item.id}/withdraw`, {
      headers: await csrf(page),
    })
    expect(cleanup.status()).toBe(200)
  }
  const application = await page.request.post('/api/v1/applications', {
    headers: await csrf(page),
    data: { vacancyId: vacancy },
  })
  expect(application.status()).toBe(201)
  const app = await application.json()
  let attempt: { id: string } | undefined
  await expect
    .poll(
      async () => {
        const response = await page.request.post(`/api/v1/applications/${app.id}/exam`, {
          headers: await csrf(page),
          data: { noticeAccepted: true, noticeVersion: 'v2' },
        })
        if (response.status() === 201) attempt = await response.json()
        else expect(response.status()).toBe(202)
        return Boolean(attempt)
      },
      { timeout: 60000, intervals: [1000] },
    )
    .toBe(true)
  const route = `/api/v1/exam-attempts/${attempt!.id}`
  // Solo se impide cargar el detector: se ejerce el modo degradado aprobado (014-40).
  // Todas las rutas API, el JPEG, la cámara y la pantalla completa son reales.
  await page.route('**/models/**', (request) => request.fulfill({ status: 503, body: '' }))
  await page.goto(`/postulaciones/${app.id}/examen`)
  const reject = page.getByRole('button', { name: es.cookies.banner.reject, exact: true })
  if (await reject.count()) await reject.click()
  await page.bringToFront()
  expect(
    await page.evaluate(
      () => document.fullscreenEnabled && Boolean(navigator.mediaDevices?.getUserMedia),
    ),
  ).toBe(true)
  await page.getByRole('button', { name: p.begin, exact: true }).click()
  await expect(page.getByText(p.detectionUnavailable)).toBeVisible({ timeout: 60000 })
  const starting = page.waitForResponse(
    (response) => response.url().endsWith('/start') && response.request().method() === 'POST',
  )
  await page.getByRole('button', { name: p.enterAndStart, exact: true }).click()
  const started = await starting
  expect(started.request().postDataJSON()).toMatchObject({ cameraReady: true, fullscreen: true })
  expect(started.status()).toBe(200)
  // La cámara de Chrome puede silenciarse al cambiar de vista. Se verifica el recorrido
  // de recuperación real; no se fuerza el estado de la cámara ni se destapan las preguntas.
  const reconnect = page.getByRole('button', { name: p.reconnect, exact: true })
  if (await reconnect.isVisible()) await reconnect.click()
  await expect(page.getByRole('radio').first()).toBeVisible()
  await expect
    .poll(async () => (await (await page.request.get(route + '/snapshots')).json()).length)
    .toBeGreaterThan(0)
  const photos = await (await page.request.get(route + '/snapshots')).json()
  const photo = await page.request.get(`${route}/snapshots/${photos[0].id}`)
  expect(photo.headers()['cache-control']).toBe('no-store')
  expect(photo.headers()['content-type']).toContain('image/jpeg')
  expect((await photo.body()).length).toBeLessThanOrEqual(150 * 1024)
  if (await reconnect.isVisible()) await reconnect.click()
  await expect(page.getByRole('radio').first()).toBeVisible({ timeout: 10000 })
  await page.getByRole('radio').first().check()
  await page.getByRole('button', { name: t.save, exact: true }).click()
  await expect(page.getByText(t.answerSaved, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '2', exact: true }).click()
  await page.getByRole('button', { name: '1', exact: true }).click()
  await expect(page.getByRole('radio').first()).toBeChecked()
  for (const width of [1280, 768, 360]) {
    await page.setViewportSize({ width, height: 900 })
    if (await reconnect.isVisible()) await reconnect.click()
    await page.evaluate(() => {
      window.scrollTo({ top: 0, behavior: 'instant' })
    })
    const camera = await page.locator('.exam-camera').boundingBox()
    const questions = await page.getByTestId('exam-questions').boundingBox()
    expect(camera).not.toBeNull()
    expect(questions).not.toBeNull()
    expect(
      camera!.x >= questions!.x + questions!.width || camera!.y + camera!.height <= questions!.y,
    ).toBe(true)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    )
    await page.screenshot({
      path: `${output}/servidor-real-${width}.png`,
      fullPage: true,
      animations: 'disabled',
    })
  }
  const access = await new AxeBuilder({ page }).include('main').exclude('.exam-watermark').analyze()
  expect(
    access.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious'),
  ).toEqual([])
  await page.getByRole('radio').first().press('Control+C')
  await page.getByRole('radio').first().press('Control+P')
  await expect
    .poll(async () => {
      const current = await (await page.request.get(route)).json()
      return current.events.map((e: { kind: string }) => e.kind)
    })
    .toEqual(expect.arrayContaining(['DETECTION_UNAVAILABLE', 'COPY_ATTEMPT', 'PRINT_ATTEMPT']))
  await page.evaluate(() => document.exitFullscreen())
  await expect(page.getByText(p.leftFullscreen)).toBeVisible()
  await expect(page.getByRole('radio').first()).toBeHidden()
  await page.getByRole('button', { name: p.returnFullscreen, exact: true }).click()
  await expect(page.getByRole('radio').first()).toBeChecked()
  const current = await (await page.request.get(route)).json()
  expect(current.status).toBe('IN_PROGRESS')
  expect(current.proctoring.detection).toBe('UNAVAILABLE')
  expect(current.watermark.code).toMatch(/^T-[A-F0-9]{6}$/)
  // Retiro por API para ejercer borrado efectivo de fotos sin introducir una segunda salida de pantalla.
  const withdrawn = await page.request.post(`/api/v1/applications/${app.id}/withdraw`, {
    headers: await csrf(page),
  })
  expect(withdrawn.status()).toBe(200)
  await expect
    .poll(async () => (await (await page.request.get(route + '/snapshots')).json()).length)
    .toBe(0)
  expect((await page.request.get(`${route}/snapshots/${photos[0].id}`)).status()).toBe(404)
})
