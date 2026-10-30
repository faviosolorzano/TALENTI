import { test, expect, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { mkdirSync } from 'node:fs'
import { es } from '../src/i18n/es'

const output = process.env.E2E_PROFILE_OUTPUT ?? 'test-results/profile-soft'
const before = process.env.E2E_PROFILE_BEFORE === '1'
test.describe.configure({ timeout: 120000 })

function sample(empty = false) {
  return {
    about: {
      firstNames: empty ? null : 'Ana',
      lastNames: empty ? null : 'Torres',
      contactEmail: null,
      phone: null,
      about: empty ? null : 'Me gusta convertir ideas en soluciones sencillas con Java y SQL.',
    },
    academic: {
      career: empty ? null : 'Desarrollo de Software',
      institute: empty ? null : 'Instituto de ejemplo',
      cycle: empty ? null : 5,
    },
    skills: empty
      ? []
      : [
          { slug: 'java', name: 'Java', level: 2, evidence: 'DECLARED' },
          { slug: 'sql', name: 'SQL', level: 1, evidence: 'DECLARED' },
        ],
    availability: {
      hoursPerWeek: empty ? null : 20,
      acceptedModalities: empty ? [] : ['REMOTE'],
      location: empty ? null : 'Lima',
    },
    projects: empty
      ? []
      : [
          {
            id: 'p1',
            name: 'Biblioteca digital',
            description: 'Organicé préstamos y devoluciones con una API y pruebas.',
            technologies: ['Java', 'PostgreSQL'],
          },
        ],
    completeness: {
      percent: empty ? 0 : 100,
      nextStep: empty ? 'ABOUT' : null,
      items: ['ABOUT', 'ACADEMIC', 'SKILLS', 'LOCATION', 'AVAILABILITY', 'PROJECTS'].map((key) => ({
        key,
        weight: 10,
        done: !empty,
      })),
    },
    updatedAt: null,
  }
}

async function shot(page: Page, name: string) {
  mkdirSync(output, { recursive: true })
  await page.evaluate(() => window.scrollTo({ top: 0, behavior: 'instant' }))
  await page.screenshot({ path: `${output}/${name}.png`, fullPage: true, animations: 'disabled' })
}

test('D-75: composición, lectura, edición y protección con respuestas controladas', async ({
  page,
  baseURL,
}) => {
  expect(new URL(baseURL!).port).toBe(before ? '3000' : '3094')
  let profile = sample()
  let state = 'full'
  let release = () => {}
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  let writes = 0
  // TODA la API se intercepta, incluso en la captura anterior: ninguna petición llega
  // a los datos de Favio ni a un proveedor. Solo los archivos estáticos son reales.
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path.endsWith('/auth/csrf'))
      return route.fulfill({
        status: 204,
        headers: { 'Set-Cookie': 'XSRF-TOKEN=perfil-prueba; Path=/; SameSite=Lax' },
      })
    if (path.endsWith('/auth/me'))
      return route.fulfill({
        json: {
          id: 'u1',
          email: 'ana@example.test',
          role: 'STUDENT',
          legalAcceptancePending: false,
        },
      })
    if (path.endsWith('/profile') && request.method() === 'GET') {
      if (state === 'loading') await gate
      if (state === 'error') return route.fulfill({ status: 503, json: { code: 'unavailable' } })
      return route.fulfill({ json: profile })
    }
    if (path.endsWith('/profile/about') && request.method() === 'PUT') {
      writes++
      if (writes === 1) return route.fulfill({ status: 500, json: { code: 'unavailable' } })
      profile = { ...profile, about: request.postDataJSON() }
      return route.fulfill({ json: profile })
    }
    return route.fulfill({ status: 404, json: { code: 'not-found' } })
  })
  await page.goto('/perfil')
  const reject = page.getByRole('button', { name: es.cookies.banner.reject, exact: true })
  if (await reject.count()) await reject.click()
  await expect(page.getByRole('navigation', { name: es.profile.title, exact: true })).toBeVisible()
  for (const width of [1280, 768, 360]) {
    await page.setViewportSize({ width, height: 900 })
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(es.profile.title)
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1)).toBe(
      true,
    )
    if (!before) {
      await expect(page.locator('.profile .section-card')).toHaveCount(5)
      const result = await new AxeBuilder({ page }).include('main').analyze()
      expect(
        result.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious'),
      ).toEqual([])
    }
    await shot(page, `${before ? 'antes' : 'despues'}-${width}`)
  }
  if (before) {
    expect(writes).toBe(0)
    return
  }

  await expect(page.getByRole('link', { name: es.redesign.useCv, exact: true })).toHaveAttribute(
    'href',
    '/perfil/cv',
  )
  await expect(page.getByRole('link', { name: es.redesign.github, exact: true })).toHaveAttribute(
    'href',
    '/perfil/github',
  )
  await page.getByRole('button', { name: es.profile.about.title, exact: true }).click()
  await page.getByLabel(es.profile.about.firstNames, { exact: true }).fill('Anita')
  await page.getByRole('button', { name: es.profile.academic.title, exact: true }).click()
  await expect(page.getByRole('dialog')).toBeVisible()
  await shot(page, 'cambios-pendientes-360')
  await page.getByRole('button', { name: es.redesign.stay, exact: true }).click()
  await expect(page.getByLabel(es.profile.about.firstNames, { exact: true })).toHaveValue('Anita')
  await page.getByRole('button', { name: es.profile.actions.save, exact: true }).click()
  await expect(page.getByRole('alert')).toBeVisible()
  await shot(page, 'error-guardar-360')
  await page.getByRole('button', { name: es.profile.actions.retry, exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Anita Torres', exact: true })).toBeVisible()
  await shot(page, 'guardado-360')
  expect(writes).toBe(2)

  profile = sample(true)
  await page.goto('/perfil')
  await expect(page.getByRole('heading', { name: es.profile.overview.noName })).toBeVisible()
  await shot(page, 'vacio-360')
  state = 'error'
  await page.goto('/perfil')
  await expect(page.getByRole('alert')).toBeVisible()
  await shot(page, 'error-carga-360')
  state = 'full'
  await page.getByRole('button', { name: es.common.retry, exact: true }).click()
  await expect(page.getByRole('navigation', { name: es.profile.title, exact: true })).toBeVisible()
  state = 'loading'
  await page.goto('/perfil')
  await expect(page.locator('main [aria-busy="true"]')).toBeVisible()
  await shot(page, 'carga-360')
  release()
  await expect(page.getByRole('navigation', { name: es.profile.title, exact: true })).toBeVisible()
  await page.emulateMedia({ reducedMotion: 'reduce' })
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
})
