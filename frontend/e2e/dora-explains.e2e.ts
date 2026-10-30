import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { mkdirSync } from 'node:fs'
import { es } from '../src/i18n/es'

const t = es.doraExplains
const vacancy = 'd0000000-0000-4000-8000-000000000001'
const endpoint = `/api/v1/compatibility/vacancies/${vacancy}/explanation`
const output = process.env.E2E_EXPLANATION_OUTPUT ?? 'test-results/dora-explains'
test.skip(
  process.env.E2E_REAL_BACKEND !== '1',
  'Requiere servidor propio con explicación simulada.',
)
test.describe.configure({ timeout: 120000 })
async function prepare(page: Page) {
  await page.request.get('/api/v1/auth/csrf')
  const csrf = async () => ({
    'X-XSRF-TOKEN': (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value,
  })
  const login = await page.request.post('/api/v1/auth/login', {
    headers: await csrf(),
    data: { email: 'demo@example.test', password: 'Demo-Talenti-2026' },
  })
  expect(login.status()).toBe(200)
  expect(
    (
      await page.request.put('/api/v1/profile/skills', {
        headers: await csrf(),
        data: {
          skills: [
            { slug: 'java', level: 2 },
            { slug: 'spring-boot', level: 1 },
            { slug: 'sql', level: 1 },
            { slug: 'git', level: 1 },
          ],
        },
      })
    ).status(),
  ).toBe(200)
  expect(
    (
      await page.request.put('/api/v1/profile/availability', {
        headers: await csrf(),
        data: { hoursPerWeek: 20, acceptedModalities: ['REMOTE'], location: 'Lima' },
      })
    ).status(),
  ).toBe(200)
}

async function openFit(page: Page) {
  await page.goto(`/oportunidades/${vacancy}?desde=explorar`)
  await page.bringToFront()
  const reject = page.getByRole('button', { name: es.cookies.banner.reject, exact: true })
  if (await reject.count()) await reject.click()
  await page.getByRole('button', { name: es.redesign.fitTab, exact: true }).click()
}

async function shot(page: Page, name: string) {
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }))
  await page.screenshot({ path: `${output}/${name}.png`, fullPage: true, animations: 'disabled' })
}

test.beforeEach(async ({ page, baseURL }) => {
  expect(new URL(baseURL!).port).toBe('3091')
  mkdirSync(output, { recursive: true })
  await prepare(page)
})

test('D-72: recomendaciones a pedido sin enlaces de estudio, foco y vistas adaptables', async ({
  page,
}) => {
  let calls = 0
  const sessions: string[] = []
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === endpoint && request.method() === 'POST') calls++
    if (
      request.method() === 'POST' &&
      /\/api\/v1\/(practice\/sessions|dora\/sessions)/.test(request.url())
    )
      sessions.push(new URL(request.url()).pathname)
  })
  await openFit(page)
  const trigger = page.getByRole('button', { name: t.trigger, exact: true })
  await expect(trigger).toHaveAttribute('aria-expanded', 'false')
  expect(calls).toBe(0)
  await page.setViewportSize({ width: 360, height: 900 })
  await shot(page, 'plegada-360')
  let release: () => void = () => {}
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  await page.route(
    `**${endpoint}`,
    async (route) => {
      await gate
      await route.continue()
    },
    { times: 1 },
  )
  await trigger.focus()
  await page.keyboard.press('Enter')
  const region = page.getByRole('region', { name: t.title })
  try {
    await expect(region.getByRole('status')).toHaveText(t.loading)
    await shot(page, 'carga-360')
  } finally {
    release()
  }
  await expect(region.getByText(es.ai.label)).toBeVisible()
  await expect(page.getByText('81 %', { exact: false }).first()).toBeVisible()
  for (const width of [1280, 768, 360]) {
    await page.setViewportSize({ width, height: 900 })
    await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }))
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    )
    await shot(page, `explicacion-${width}`)
  }
  const violations = await new AxeBuilder({ page }).include('main').analyze()
  expect(
    violations.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious'),
  ).toEqual([])
  await region.getByRole('button', { name: t.hide, exact: true }).click()
  await expect(trigger).toBeFocused()
  await trigger.press('Enter')
  await expect(region.getByText(es.ai.label)).toBeVisible()
  expect(calls).toBe(1)
  expect(await region.getByRole('link').count()).toBe(0)
  expect(sessions).toEqual([])
})

test('D-72: error local, reintento y plantilla con límite, sin ofrecer sesiones', async ({
  page,
}) => {
  // La página y el perfil son reales. Solo la respuesta de explicación se controla
  // para revisar estados; estas capturas NO acreditan un límite real del proveedor.
  const csrf = (await page.context().cookies()).find((c) => c.name === 'XSRF-TOKEN')!.value
  const result = await page.request.post(endpoint, { headers: { 'X-XSRF-TOKEN': csrf } })
  expect(result.status()).toBe(200)
  const template = {
    ...(await result.json()),
    source: 'TEMPLATE',
    fallbackReason: 'PERSONAL_LIMIT',
  }
  let calls = 0
  await page.route(`**${endpoint}`, (route) => {
    calls++
    return calls === 1
      ? route.fulfill({ status: 503, json: { code: 'unavailable' } })
      : route.fulfill({ status: 200, json: template })
  })
  await page.setViewportSize({ width: 360, height: 900 })
  await openFit(page)
  await page.getByRole('button', { name: t.trigger, exact: true }).click()
  const region = page.getByRole('region', { name: t.title })
  await expect(region.getByRole('alert')).toContainText(t.error)
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await shot(page, 'error-controlado-360')
  await region.getByRole('button', { name: t.retry, exact: true }).click()
  await expect(region.getByText(t.template, { exact: true })).toBeVisible()
  await expect(region.getByRole('note')).toHaveText(t.personalLimit)
  await expect(region.getByText(es.ai.label)).toHaveCount(0)
  await expect(region.getByRole('heading', { name: t.practice })).toHaveText('Qué estudiar')
  await expect(region.getByRole('link')).toHaveCount(0)
  expect(calls).toBe(2)
  await shot(page, 'plantilla-controlada-360')
  const access = await new AxeBuilder({ page }).include('main').analyze()
  expect(
    access.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious'),
  ).toEqual([])
})
