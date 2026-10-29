import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { mkdir } from 'node:fs/promises'
import { join } from 'node:path'

// E0/E1: real built frontend and nginx CSP, simulated API. No external provider calls.
const profile = {
  about: { firstNames: null, lastNames: null, contactEmail: null, phone: null, about: null },
  academic: { career: null, institute: null, cycle: null },
  skills: [],
  availability: { hoursPerWeek: null, acceptedModalities: [], location: null },
  projects: [],
  completeness: {
    percent: 0,
    items: [
      { key: 'ACADEMIC', weight: 15, done: false },
      { key: 'SKILLS', weight: 25, done: false },
      { key: 'AVAILABILITY', weight: 15, done: false },
      { key: 'PROJECTS', weight: 25, done: false },
      { key: 'LOCATION', weight: 10, done: false },
      { key: 'ABOUT', weight: 10, done: false },
    ],
    nextStep: 'SKILLS',
  },
  updatedAt: null,
}

async function api(page: Page, initialStudent = false) {
  const state = { student: initialStudent, unavailable: false, slow: false }
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '')
    const json = (data: unknown, status = 200) => route.fulfill({ status, json: data })
    if (path === '/auth/me') {
      if (state.slow) await new Promise((resolve) => setTimeout(resolve, 1800))
      if (state.unavailable) return json({ code: 'internal-error' }, 503)
      return state.student
        ? json({
            id: '11111111-1111-4111-8111-111111111111',
            email: 'ana@example.test',
            role: 'STUDENT',
            legalAcceptancePending: false,
          })
        : json({ code: 'unauthenticated' }, 401)
    }
    if (path === '/auth/csrf')
      return route.fulfill({
        status: 204,
        headers: { 'Set-Cookie': 'XSRF-TOKEN=example-test; Path=/; SameSite=Lax' },
      })
    if (path === '/auth/login') {
      state.student = true
      return json({
        id: '11111111-1111-4111-8111-111111111111',
        email: 'ana@example.test',
        role: 'STUDENT',
      })
    }
    if (path === '/auth/logout') {
      state.student = false
      return route.fulfill({ status: 204 })
    }
    if (path === '/profile') return json(profile)
    if (path === '/vacancies/filters')
      return json({ openCount: 0, modalities: [], locations: [], skills: [] })
    if (path === '/vacancies/featured' || path === '/companies/featured' || path === '/skills')
      return json([])
    if (path === '/vacancies')
      return json({ items: [], page: 1, size: 12, totalItems: 0, totalPages: 0 })
    if (path === '/applications')
      return json({ items: [], page: 1, size: 12, totalItems: 0, totalPages: 0 })
    if (path === '/applications/summary') return json({ active: 0, withdrawn: 0, latest: null })
    return json({ code: 'fixture-not-provided' }, 404)
  })
  return state
}

const nav = (page: Page) => page.getByRole('navigation', { name: 'Navegación principal' })
const menu = (page: Page) => page.getByRole('button', { name: 'Menú', exact: true })

for (const width of [320, 360, 390, 430, 768, 1024, 1440]) {
  test(`E1: controls, white header and local Hanken at ${width}px`, async ({ page }) => {
    await api(page)
    await page.setViewportSize({ width, height: 844 })
    await page.goto('/ingresar')
    await expect(page.getByLabel('Correo', { exact: true })).toBeVisible()
    await page.evaluate(() => document.fonts.ready)
    const measured = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth > document.documentElement.clientWidth,
      header: document.querySelector('header')!.getBoundingClientRect().height,
      family: getComputedStyle(document.body).fontFamily,
      fontLoaded: [...document.fonts].some(
        (font) => font.family.includes('Hanken') && font.status === 'loaded',
      ),
      field: document.querySelector('input')!.getBoundingClientRect().height,
      button: document.querySelector('button[type="submit"]')!.getBoundingClientRect().height,
    }))
    expect(measured.overflow).toBe(false)
    expect(measured.header).toBe(width < 860 ? 65 : 73)
    expect(measured.family).toContain('Hanken Grotesk')
    expect(measured.fontLoaded).toBe(true)
    expect(measured.field).toBeGreaterThanOrEqual(48)
    expect(measured.button).toBeGreaterThanOrEqual(48)
    if (width < 860) await menu(page).click()
    await expect(nav(page).getByRole('link')).toHaveText(['Vacantes', 'Cómo funciona', 'Ingresar'])
  })
}

test('E1: mobile focus, Escape, outside click, navigation and resizing', async ({ page }) => {
  await api(page)
  await page.setViewportSize({ width: 390, height: 844 })
  await page.goto('/ingresar')
  await menu(page).click()
  await expect(nav(page).getByRole('link', { name: 'Vacantes' })).toBeFocused()
  await page.keyboard.press('Escape')
  await expect(menu(page)).toBeFocused()
  await expect(nav(page)).toBeHidden()
  await menu(page).click()
  await page.getByLabel('Contraseña', { exact: true }).click()
  await expect(nav(page)).toBeHidden()
  await menu(page).click()
  await page.setViewportSize({ width: 1024, height: 768 })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(nav(page)).toBeHidden()
  await menu(page).click()
  await nav(page).getByRole('link', { name: 'Vacantes' }).click()
  await expect(page).toHaveURL(/\/oportunidades$/)
  await expect(nav(page)).toBeHidden()
  await expect(page.getByRole('main')).toBeFocused()
})

test('E1: student destinations, active section and Cuenta logout', async ({ page }) => {
  await api(page, true)
  await page.goto('/perfil')
  await expect(nav(page).getByRole('link')).toHaveText([
    'Vacantes',
    'Mis postulaciones',
    'Mi perfil',
  ])
  await expect(nav(page).getByRole('link', { name: 'Mi perfil' })).toHaveAttribute(
    'aria-current',
    'page',
  )
  await nav(page).getByRole('button', { name: 'Cuenta' }).click()
  await page.keyboard.press('Escape')
  await expect(nav(page).getByRole('button', { name: 'Cuenta' })).toBeFocused()
  await nav(page).getByRole('button', { name: 'Cuenta' }).click()
  await nav(page).getByRole('button', { name: 'Cerrar sesión' }).click()
  await expect(page).toHaveURL(/\/ingresar(?:\?|$)/)
  await expect(nav(page).getByRole('link', { name: 'Ingresar' })).toBeVisible()
})

test('E1: student compact menu has the same destinations in a low window', async ({ page }) => {
  await api(page, true)
  await page.setViewportSize({ width: 667, height: 320 })
  await page.goto('/perfil')
  await menu(page).click()
  await expect(nav(page).getByRole('link')).toHaveText([
    'Vacantes',
    'Mis postulaciones',
    'Mi perfil',
  ])
  await nav(page).getByRole('button', { name: 'Cuenta' }).click()
  await nav(page).getByRole('button', { name: 'Cerrar sesión' }).click()
  await expect(page).toHaveURL(/\/ingresar(?:\?|$)/)
})

test('E1: session failure retains retry and logout without claiming a visitor', async ({
  page,
}) => {
  const state = await api(page)
  state.unavailable = true
  await page.goto('/ingresar')
  await expect(page.getByRole('banner').getByRole('status')).toBeVisible()
  await expect(nav(page).getByRole('button', { name: 'Cerrar sesión' })).toBeVisible()
  await expect(nav(page).getByRole('link', { name: 'Ingresar' })).toHaveCount(0)
  state.unavailable = false
  await nav(page).getByRole('button', { name: 'Reintentar' }).click()
  await expect(nav(page).getByRole('link', { name: 'Ingresar' })).toBeVisible()
})

test('E1: login preserves the protected route intention', async ({ page }) => {
  await api(page)
  await page.goto('/perfil?seccion=habilidades')
  await expect(page).toHaveURL(/\/ingresar\?returnTo=/)
  await page.getByLabel('Correo', { exact: true }).fill('ana@example.test')
  await page.getByLabel('Contraseña', { exact: true }).fill('ClaveDePrueba2026')
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click()
  await expect(page).toHaveURL(/\/perfil\?seccion=habilidades$/)
})

test('E1: local font and controls obey nginx CSP; no external requests', async ({ page }) => {
  await api(page)
  const violations: string[] = []
  await page.exposeFunction('recordViolation', (value: string) => violations.push(value))
  await page.addInitScript(() =>
    document.addEventListener('securitypolicyviolation', (event) => {
      void (window as unknown as { recordViolation(value: string): Promise<void> }).recordViolation(
        event.violatedDirective,
      )
    }),
  )
  const external: string[] = []
  const fonts: string[] = []
  page.on('request', (request) => {
    if (/^https?:/.test(request.url()) && !request.url().startsWith('http://localhost:3074/'))
      external.push(request.url())
    if (request.url().endsWith('.woff2')) fonts.push(request.url())
  })
  const response = await page.goto('/ingresar')
  expect(response?.headers()['content-security-policy']).toContain("default-src 'self'")
  await page.evaluate(() => document.fonts.ready)
  expect(violations).toEqual([])
  expect(external).toEqual([])
  expect(fonts).toHaveLength(1)
  const results = await new AxeBuilder({ page }).analyze()
  expect(results.violations).toEqual([])
})

test('E1: capture desktop/mobile header, controls and account for review', async ({ page }) => {
  const directory = process.env.REDESIGN_CAPTURE_DIR
  test.skip(!directory, 'Set REDESIGN_CAPTURE_DIR to export review images')
  await mkdir(directory!, { recursive: true })
  const state = await api(page)
  for (const width of [1440, 390]) {
    await page.setViewportSize({ width, height: 1000 })
    await page.goto('/ingresar')
    await page.getByLabel('Correo', { exact: true }).waitFor()
    await page.evaluate(() => document.fonts.ready)
    await page.screenshot({ path: join(directory!, `e1-controles-${width}.png`), fullPage: true })
    await page
      .getByRole('banner')
      .screenshot({ path: join(directory!, `e1-cabecera-visitante-${width}.png`) })
    if (width === 390) {
      await menu(page).click()
      await page.screenshot({ path: join(directory!, 'e1-menu-visitante-390.png') })
    }
  }
  state.student = true
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/perfil')
  await expect(nav(page).getByRole('button', { name: 'Cuenta' })).toBeVisible()
  await page
    .getByRole('banner')
    .screenshot({ path: join(directory!, 'e1-cabecera-estudiante-1440.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await menu(page).click()
  await nav(page).getByRole('button', { name: 'Cuenta' }).click()
  await page.screenshot({ path: join(directory!, 'e1-menu-estudiante-390.png') })
})
