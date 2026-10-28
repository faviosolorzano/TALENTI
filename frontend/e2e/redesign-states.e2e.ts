import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { es } from '../src/i18n/es'
const output = process.env.E2E_REDESIGN_OUTPUT ?? 'test-results/redesign'
test.describe.configure({ timeout: 90000 })
test.use({ actionTimeout: 20000 })
async function shot(page: Page, name: string) {
  mkdirSync(output, { recursive: true })
  if (!name.includes('carga')) await expect(page.locator('main [aria-busy="true"]')).toHaveCount(0)
  await page.evaluate(async () => {
    await document.fonts.ready
    window.scrollTo({ top: 0, behavior: 'instant' })
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    )
  })
  await page.screenshot({ path: resolve(output, name + '.png'), fullPage: true })
}
async function reflow(page: Page) {
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
    true,
  )
}
async function cookies(page: Page) {
  const reject = page.getByRole('button', { name: es.cookies.banner.reject, exact: true })
  if (await reject.count()) await reject.click()
}
test('E6/E7: access, legal documents and missing pages keep identity and reflow', async ({
  page,
}) => {
  await page.goto('/ingresar')
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await cookies(page)
  for (const [path, name] of [
    ['/ingresar', 'ingresar'],
    ['/registro', 'registro'],
    ['/recuperar-cuenta', 'recuperar'],
    ['/terminos', 'terminos'],
    ['/privacidad', 'privacidad'],
    ['/cookies', 'cookies'],
    ['/transparencia', 'transparencia'],
    ['/pagina-inexistente', '404'],
  ]) {
    await page.goto(path!)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(page.locator('main [aria-busy="true"]')).toHaveCount(0)
    for (const width of [360, 768, 1280]) {
      await page.setViewportSize({ width, height: 900 })
      await reflow(page)
      if (width !== 768) await shot(page, name + (width === 360 ? '-movil' : '-escritorio'))
    }
  }
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze()
  expect(
    result.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious'),
  ).toEqual([])
})
test('E2/E7: real empty search, injected failure with retry and pending loading have distinct states', async ({
  page,
}) => {
  await page.goto('/oportunidades?q=zzzz-no-existe')
  await expect(
    page.getByRole('heading', { name: es.opportunities.results.noMatchesTitle('zzzz-no-existe') }),
  ).toBeVisible()
  await cookies(page)
  await shot(page, 'vacantes-vacio')
  await page.route(/\/api\/v1\/vacancies(?:\?|$)/, (route) =>
    route.fulfill({
      status: 503,
      contentType: 'application/problem+json',
      body: JSON.stringify({ code: 'test-unavailable', status: 503 }),
    }),
  )
  await page.goto('/oportunidades')
  await expect(page.getByRole('alert')).toBeVisible()
  await shot(page, 'vacantes-error')
  await page.unroute(/\/api\/v1\/vacancies(?:\?|$)/)
  await page.getByRole('button', { name: es.common.retry, exact: true }).click()
  await expect(page.locator('article').first()).toBeVisible()
  await page.route(/\/api\/v1\/vacancies(?:\?|$)/, async (route) => {
    await new Promise((resolve) => setTimeout(resolve, 1500))
    await route.continue()
  })
  await page.goto('/oportunidades?q=java')
  await expect(page.locator('.skeleton').first()).toBeVisible()
  await shot(page, 'vacantes-carga')
  await expect(page.locator('article').first()).toBeVisible()
})
test('E4/E7: profile protects unsaved changes and shows validation after save-and-leave', async ({
  page,
}) => {
  await page.goto('/ingresar')
  await page.getByLabel('Correo', { exact: true }).fill('demo@example.test')
  await page.getByLabel('Contraseña', { exact: true }).fill('Demo-Talenti-2026')
  await cookies(page)
  await page.getByRole('button', { name: es.identity.login.submit, exact: true }).click()
  await expect(page).toHaveURL(/\/$/)
  await page.goto('/perfil?seccion=academico')
  await page.getByLabel(es.profile.academic.institute, { exact: true }).fill('x')
  await page.getByRole('button', { name: es.profile.skills.title, exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await shot(page, 'perfil-cambios-sin-guardar')
  await page.getByRole('button', { name: es.redesign.saveLeave, exact: true }).click()
  await expect(page.getByLabel(es.profile.academic.institute, { exact: true })).toHaveAttribute(
    'aria-invalid',
    'true',
  )
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await shot(page, 'perfil-error-validacion')
  await page.getByRole('button', { name: es.profile.skills.title, exact: true }).click()
  await page.getByRole('button', { name: es.redesign.discard, exact: true }).click()
  await expect(page.getByRole('searchbox', { name: es.profile.skills.pickerLabel })).toHaveCount(0)
  await shot(page, 'perfil-habilidades-escritorio')
  await page.setViewportSize({ width: 360, height: 900 })
  await reflow(page)
  await shot(page, 'perfil-habilidades-movil')
})

test('012/013: GitHub connected composition uses explicit simulated contracts and conserves decisions', async ({
  page,
}) => {
  const t = es.workflows
  await page.goto('/ingresar')
  await page.getByLabel('Correo', { exact: true }).fill('demo@example.test')
  await page.getByLabel('Contraseña', { exact: true }).fill('Demo-Talenti-2026')
  await cookies(page)
  await page.getByRole('button', { name: es.identity.login.submit, exact: true }).click()
  await expect(page).toHaveURL(/\/$/)
  const selected: string[] = []
  await page.route('**/api/v1/github**', async (route) => {
    const path = new URL(route.request().url()).pathname
    let data: unknown
    if (path.endsWith('/repositories/selection')) {
      selected.splice(0, selected.length, ...route.request().postDataJSON().repoIds)
      data = {}
    } else if (path.endsWith('/repositories'))
      data = {
        items: [
          {
            id: 'demo-repo',
            name: 'Proyecto de práctica',
            url: 'https://github.com/example/practica',
            description: 'Repositorio inventado para validar la interfaz',
            languages: [{ name: 'Java', percent: 100 }],
            topics: [],
            archived: false,
            selected: selected.includes('demo-repo'),
          },
        ],
        fetchedAt: '2026-10-28T15:00:00Z',
        stale: false,
      }
    else if (path.endsWith('/suggestions/decisions')) data = {}
    else if (path.endsWith('/suggestions'))
      data = {
        suggestions: [
          {
            slug: 'java',
            name: 'Java',
            currentLevel: null,
            currentEvidence: null,
            evidence: [{ repo: 'Proyecto de práctica', source: 'LANGUAGE', share: 100 }],
          },
        ],
        unmatched: [
          {
            name: 'Tecnología sin catálogo',
            evidence: [{ repo: 'Proyecto de práctica', source: 'TOPIC' }],
          },
        ],
        source: 'RULES',
      }
    else
      data = { status: 'VERIFIED', login: 'cuenta-de-prueba', verifiedAt: '2026-10-28T15:00:00Z' }
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify(data),
    })
  })
  await page.goto('/perfil/github')
  await expect(page.getByText('cuenta-de-prueba', { exact: true })).toBeVisible()
  await page.getByRole('checkbox', { name: 'Proyecto de práctica', exact: true }).check()
  await expect(page.getByRole('combobox', { name: t.confirm, exact: true })).toBeDisabled()
  await shot(page, 'github-seleccion-pendiente')
  await page.getByRole('button', { name: t.saveRepos, exact: true }).click()
  await expect(page.getByRole('combobox', { name: t.confirm, exact: true })).toBeEnabled()
  await page.getByRole('combobox', { name: t.confirm, exact: true }).selectOption('ACCEPT')
  await expect(page.getByRole('button', { name: t.applySuggestions, exact: true })).toBeDisabled()
  await page.getByRole('combobox', { name: t.level, exact: true }).selectOption('2')
  for (const width of [360, 768, 1280]) {
    await page.setViewportSize({ width, height: 900 })
    await reflow(page)
    if (width !== 768)
      await shot(page, width === 360 ? 'github-conectado-movil' : 'github-conectado-escritorio')
  }
  await page.getByRole('button', { name: t.applySuggestions, exact: true }).click()
  await expect(page.getByText(t.saved, { exact: true })).toBeVisible()
  expect(selected).toEqual(['demo-repo'])
})
