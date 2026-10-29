import { expect, test } from '@playwright/test'
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
test('015: recomendaciones a pedido sin enlaces de estudio, foco y vistas adaptables', async ({
  page,
  baseURL,
}) => {
  expect(new URL(baseURL!).port).toBe('3091')
  mkdirSync(output, { recursive: true })
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
  let calls = 0
  page.on('request', (request) => {
    if (new URL(request.url()).pathname === endpoint && request.method() === 'POST') calls++
  })
  await page.goto(`/oportunidades/${vacancy}?desde=explorar`)
  const reject = page.getByRole('button', { name: es.cookies.banner.reject, exact: true })
  if (await reject.count()) await reject.click()
  await page.getByRole('button', { name: es.redesign.fitTab, exact: true }).click()
  const trigger = page.getByRole('button', { name: t.trigger, exact: true })
  await expect(trigger).toHaveAttribute('aria-expanded', 'false')
  expect(calls).toBe(0)
  await trigger.focus()
  await page.keyboard.press('Enter')
  const region = page.getByRole('region', { name: t.title })
  await expect(region.getByText(es.ai.label)).toBeVisible()
  await expect(page.getByText('81 %', { exact: false }).first()).toBeVisible()
  for (const width of [1280, 768, 360]) {
    await page.setViewportSize({ width, height: 900 })
    await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }))
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    )
    await page.screenshot({
      path: `${output}/explicacion-${width}.png`,
      fullPage: true,
      animations: 'disabled',
    })
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
})
