import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

/**
 * Spec 007 (interface) against the real stack. A student loads the example of RN-01 through the
 * API (Java 2, Spring Boot 1, SQL 1, Git 1; 20 h per week; remote), opens "Practicante Backend
 * Java" and sees the 81 % the SERVER calculated, with matches, gaps and missing data; another
 * student with an empty profile sees "Información insuficiente". Two sign-ups only (the backend
 * allows 10 per minute per origin); the e-mails are invented and unique.
 */
test.describe.configure({ mode: 'serial', timeout: 180_000 })

const PASSWORD = 'Clave-de-prueba-123'
const unique = () => `${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}`
const WIDTHS = [360, 768, 1280] as const

async function seriousViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze()
  return results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target.join(' ')) }))
}

const horizontalScroll = (page: Page) =>
  page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))

async function signUp(page: Page, email: string) {
  await page.goto('/registro')
  await page.getByText('Lee qué datos usamos y para qué').waitFor()
  await page.getByLabel('Correo').fill(email)
  await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD)
  await page.getByRole('checkbox', { name: /Leí y acepto/ }).check()
  await page.getByRole('button', { name: 'Crear mi cuenta' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Hola de nuevo' })).toBeVisible()
}

/** Writes the profile of the RN-01 example through the API of the signed-in session. */
async function loadExampleProfile(page: Page) {
  const cookies = await page.context().cookies()
  const token = decodeURIComponent(cookies.find((c) => c.name === 'XSRF-TOKEN')?.value ?? '')
  const headers = { 'X-XSRF-TOKEN': token }
  const skills = await page.request.put('/api/v1/profile/skills', {
    headers,
    data: {
      skills: [
        { slug: 'java', level: 2 },
        { slug: 'spring-boot', level: 1 },
        { slug: 'sql', level: 1 },
        { slug: 'git', level: 1 },
      ],
    },
  })
  expect(skills.ok()).toBe(true)
  const availability = await page.request.put('/api/v1/profile/availability', {
    headers,
    data: { hoursPerWeek: 20, acceptedModalities: ['REMOTE'], location: 'Lima' },
  })
  expect(availability.ok()).toBe(true)
}

async function openBackendJava(page: Page) {
  await page.goto('/oportunidades')
  await page.getByRole('link', { name: 'Practicante Backend Java' }).click()
  await expect(
    page.getByRole('heading', { level: 1, name: 'Practicante Backend Java' }),
  ).toBeVisible()
}

const panel = (page: Page) => page.getByRole('region', { name: 'Tu compatibilidad' })

let student: Page
let beginner: Page

test.beforeAll(async ({ browser }) => {
  student = await (await browser.newContext()).newPage()
  beginner = await (await browser.newContext()).newPage()
  await signUp(student, `ana.compat+${unique()}@example.test`)
  await loadExampleProfile(student)
  await signUp(beginner, `luis.compat+${unique()}@example.test`)
})

test.afterAll(async () => {
  await student.context().close()
  await beginner.context().close()
})

test('007-18, 007-19: the detail shows the 81 % of the server with matches, gaps and missing data', async () => {
  await openBackendJava(student)
  const region = panel(student)
  await expect(region.getByText('81 % de compatibilidad')).toBeAttached()
  await expect(region.getByText('Encajas muy bien')).toBeVisible()
  await expect(region.getByText(/Basado en/)).toHaveCount(0)

  await expect(region.getByRole('heading', { level: 3, name: 'Coincidencias' })).toBeVisible()
  await expect(region.getByRole('heading', { level: 3, name: 'Brechas' })).toBeVisible()
  await expect(region.getByText('pide nivel intermedio · tú: básico · declarado')).toBeVisible()
  await expect(region.getByText('pide nivel básico · no está en tu perfil')).toBeVisible()

  await region.getByRole('link', { name: /Subir mi nivel.*SQL/ }).click()
  await expect(student).toHaveURL(/\/perfil\?seccion=habilidades$/)
})

test('007-20: the explanation says "calculada con reglas", has no AI label and the disclosure works with the keyboard', async () => {
  await openBackendJava(student)
  const region = panel(student)
  await expect(region.getByText('Explicación automática, calculada con reglas')).toBeVisible()
  await expect(region.getByText('DORA · inteligencia artificial')).toHaveCount(0)

  const summary = region.getByText('Cómo se calcula este porcentaje')
  await summary.focus()
  await student.keyboard.press('Enter')
  await expect(region.getByText('no la inteligencia artificial')).toBeVisible()
  await expect(region.getByRole('cell', { name: '50 puntos' })).toBeVisible()
  await expect(region.getByText('el máximo es 69 %')).toBeVisible()
  await expect(region.getByText('todavía no se usa')).toBeVisible()
})

test('007-23: the list shows a badge per card with ONE summaries call per page', async () => {
  const summaries: string[] = []
  student.on('request', (request) => {
    if (request.url().includes('/api/v1/compatibility/summaries')) summaries.push(request.url())
  })
  await student.goto('/oportunidades')
  const cards = student.getByRole('article')
  await expect(cards).toHaveCount(9)
  await expect(student.getByText(/\d+ % de compatibilidad/)).toHaveCount(9)
  expect(summaries).toHaveLength(1)
  const backendJava = cards.filter({ hasText: 'Practicante Backend Java' })
  await expect(backendJava.getByText('81 % de compatibilidad')).toBeVisible()
  student.removeAllListeners('request')
})

test('007-24: "Para ti" lists the recommended ones from the highest to the lowest', async () => {
  await student.goto('/oportunidades')
  await student.getByRole('link', { name: 'Para ti' }).click()
  await expect(student).toHaveURL(/vista=para-ti/)
  await expect(student.getByText('Esta vista no usa la búsqueda ni los filtros')).toBeVisible()
  await expect(student.getByRole('search')).toHaveCount(0)

  const badges = student.getByText(/\d+ % de compatibilidad/)
  await expect(badges).toHaveCount(9)
  const percents = (await badges.allTextContents()).map((text) => Number.parseInt(text, 10))
  expect(percents).toEqual([...percents].sort((a, b) => b - a))
  await expect(student.getByRole('article').first()).toContainText('Practicante Backend Java')

  await student.getByRole('link', { name: 'Página 2' }).click()
  await expect(student).toHaveURL(/pagina=2/)
  await expect(student.getByRole('article')).toHaveCount(3)
})

test('007-22: the Home block "Vacantes para ti" shows 3 with their percentage', async () => {
  await student.goto('/')
  const block = student.getByRole('region', { name: 'Vacantes para ti' })
  await expect(block.getByRole('listitem')).toHaveCount(3)
  await expect(block.getByText(/\d+ % de compatibilidad/)).toHaveCount(3)
  await block.getByRole('link', { name: /Ver todas las recomendadas/ }).click()
  await expect(student).toHaveURL(/\/oportunidades\?vista=para-ti$/)
})

test('007-21, 007-23, 007-24, 007-22: an empty profile gets "Información insuficiente", one line and a way to the profile', async () => {
  await openBackendJava(beginner)
  const region = panel(beginner)
  await expect(region.getByText('Información insuficiente', { exact: true })).toBeVisible()
  await expect(region.getByText(/\d+ %/)).toHaveCount(0)
  await expect(region.getByRole('link', { name: 'Completar mi perfil' })).toHaveAttribute(
    'href',
    '/perfil?seccion=habilidades',
  )

  await beginner.goto('/oportunidades')
  await expect(beginner.getByRole('article')).toHaveCount(9)
  await expect(beginner.getByText('Completa tu perfil para ver tu compatibilidad')).toHaveCount(1)
  await expect(beginner.getByText(/de compatibilidad/)).toHaveCount(0)

  await beginner.goto('/oportunidades?vista=para-ti')
  await expect(beginner.getByText('Completa tu perfil para ver tus recomendadas')).toBeVisible()
  await expect(beginner.getByRole('article')).toHaveCount(0)

  await beginner.goto('/')
  const block = beginner.getByRole('region', { name: 'Vacantes destacadas' })
  await expect(block.getByText('Completa tu perfil para ver cuáles encajan contigo')).toBeVisible()
  await expect(block.getByText(/de compatibilidad/)).toHaveCount(0)
})

test('004-22, 007-21: a visitor sees no percentage and is invited to sign in', async ({
  browser,
}) => {
  const visitor = await (await browser.newContext()).newPage()
  await openBackendJava(visitor)
  await expect(visitor.getByText('Inicia sesión para ver tu compatibilidad')).toBeVisible()
  await expect(visitor.getByRole('region', { name: 'Tu compatibilidad' })).toHaveCount(0)
  await visitor.goto('/oportunidades?vista=para-ti')
  await expect(visitor).toHaveURL(/\/oportunidades$/)
  await expect(visitor.getByText(/de compatibilidad/)).toHaveCount(0)
  await visitor.context().close()
})

for (const width of WIDTHS) {
  test(`007-26: at ${width} px there is no horizontal scroll and axe finds nothing serious`, async () => {
    await student.setViewportSize({ width, height: 900 })
    const pages = [
      async () => openBackendJava(student),
      async () => {
        await student.goto('/oportunidades')
        await expect(student.getByText(/\d+ % de compatibilidad/)).toHaveCount(9)
      },
      async () => {
        await student.goto('/oportunidades?vista=para-ti')
        await expect(student.getByText(/\d+ % de compatibilidad/)).toHaveCount(9)
      },
      async () => {
        await student.goto('/')
        await expect(student.getByText(/\d+ % de compatibilidad/)).toHaveCount(3)
      },
    ]
    for (const open of pages) {
      await open()
      await student.evaluate(() => document.fonts.ready)
      const { scrollWidth, clientWidth } = await horizontalScroll(student)
      expect(scrollWidth).toBeLessThanOrEqual(clientWidth)
      expect(await seriousViolations(student)).toEqual([])
    }

    // The insufficient states too.
    await beginner.setViewportSize({ width, height: 900 })
    await openBackendJava(beginner)
    await expect(
      panel(beginner).getByText('Información insuficiente', { exact: true }),
    ).toBeVisible()
    expect((await horizontalScroll(beginner)).scrollWidth).toBeLessThanOrEqual(
      (await horizontalScroll(beginner)).clientWidth,
    )
    expect(await seriousViolations(beginner)).toEqual([])
  })
}
