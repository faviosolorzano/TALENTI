import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { mkdirSync } from 'node:fs'
import { es } from '../src/i18n/es'

// Real built UI; every API request is intercepted, including before captures.
// These fixtures are examples, never provider results or Favio's information.
const output = process.env.E2E_SOFT_OUTPUT ?? 'test-results/soft-web'
const before = process.env.E2E_SOFT_BEFORE === '1'
const company = { id: 'company-demo', name: 'Equipo de ejemplo', city: 'Lima' }
const vacancy = {
  id: 'vacancy-demo',
  title: 'Practicante de desarrollo Java',
  summary: 'Colabora en servicios web y aprende con un equipo de desarrollo.',
  modality: 'REMOTE',
  location: null,
  hoursPerWeek: 30,
  publishedAt: '2026-10-28T12:00:00Z',
  featured: true,
  company,
  skills: [
    { slug: 'java', name: 'Java', kind: 'REQUIRED' },
    { slug: 'sql', name: 'SQL', kind: 'DESIRABLE' },
  ],
}
const profile = {
  about: {
    firstNames: 'Ana',
    lastNames: 'Torres',
    contactEmail: null,
    phone: null,
    about: 'Me gusta crear soluciones sencillas con Java.',
  },
  academic: { career: 'Desarrollo de Software', institute: 'Instituto de ejemplo', cycle: 5 },
  skills: [{ slug: 'java', name: 'Java', level: 2, evidence: 'DECLARED' }],
  availability: { hoursPerWeek: 30, acceptedModalities: ['REMOTE'], location: 'Lima' },
  projects: [],
  completeness: { percent: 75, nextStep: 'PROJECTS', items: [] },
  updatedAt: null,
}
const compatibility = {
  vacancyId: vacancy.id,
  status: 'CALCULATED',
  percent: 78,
  level: 'HIGH',
  capped: false,
}
const application = {
  id: 'application-demo',
  status: 'SUBMITTED',
  submittedAt: '2026-10-29T12:00:00Z',
  withdrawnAt: null,
  vacancy: { id: vacancy.id, title: vacancy.title, status: 'OPEN', company },
  history: [{ status: 'SUBMITTED', at: '2026-10-29T12:00:00Z' }],
}
test.describe.configure({ timeout: 180000 })

async function fixture(page: Page) {
  const state = {
    student: true,
    role: 'STUDENT',
    legalPending: false,
    vacancies: 'ready',
    applications: true,
  }
  await page.route('**/api/v1/**', async (route) => {
    const path = new URL(route.request().url()).pathname.replace('/api/v1', '')
    const json = (data: unknown, status = 200) => route.fulfill({ status, json: data })
    if (path === '/auth/me')
      return state.student
        ? json({
            id: 'student-demo',
            email: 'ana@example.test',
            role: state.role,
            legalAcceptancePending: state.legalPending,
          })
        : json({ code: 'unauthenticated' }, 401)
    if (path === '/auth/csrf')
      return route.fulfill({
        status: 204,
        headers: { 'Set-Cookie': 'XSRF-TOKEN=soft-demo; Path=/; SameSite=Lax' },
      })
    if (path === '/auth/login') {
      state.student = true
      return json({ id: 'student-demo', email: 'ana@example.test', role: 'STUDENT' })
    }
    if (path === '/profile') return json(profile)
    if (path === '/legal/current')
      return json({
        version: 'demo-1',
        publishedAt: '2026-10-30',
        documents: [
          { key: 'terms', title: 'Términos y condiciones' },
          { key: 'privacy', title: 'Política de privacidad' },
          { key: 'cookies', title: 'Política de cookies' },
        ].map((document) => ({
          ...document,
          sections: [
            {
              heading: 'Documento de ejemplo para revisión',
              body: 'Texto ficticio para comprobar lectura, navegación y distribución. El contenido legal vigente lo entrega el servidor.',
            },
            {
              heading: 'Tus opciones',
              body: 'Consulta la información antes de continuar. Esta captura no representa un acuerdo real.',
            },
          ],
        })),
      })
    if (path === '/skills') return json([{ slug: 'java', name: 'Java' }])
    if (path === '/vacancies/filters')
      return json({
        openCount: 1,
        modalities: [{ value: 'REMOTE', count: 1 }],
        locations: [],
        skills: [],
      })
    if (path === '/vacancies/featured') return json({ items: [vacancy] })
    if (path === '/compatibility/recommended')
      return json({
        profile: { sufficient: true },
        items: [{ vacancy, compatibility }],
        page: 1,
        size: 12,
        totalItems: 1,
        totalPages: 1,
      })
    if (path === '/compatibility/summaries')
      return json({ sufficient: true, items: [compatibility] })
    if (path === '/vacancies') {
      if (state.vacancies === 'loading') await new Promise((resolve) => setTimeout(resolve, 2200))
      if (state.vacancies === 'error') return json({ code: 'unavailable' }, 503)
      const items = state.vacancies === 'empty' ? [] : [vacancy]
      return json({ items, page: 1, size: 12, totalItems: items.length, totalPages: items.length })
    }
    if (path === '/vacancies/' + vacancy.id)
      return json({
        ...vacancy,
        status: 'OPEN',
        description: 'Participarás en APIs Java y consultas SQL con acompañamiento del equipo.',
        company: { ...company, shortDescription: 'Equipo de software de ejemplo.' },
        requirements: {
          required: [{ skill: { slug: 'java', name: 'Java' }, minLevel: 1 }],
          desirable: [],
        },
      })
    if (path === '/compatibility/vacancies/' + vacancy.id)
      return json({
        ...compatibility,
        doraExplanation: { available: false },
        reasons: [],
        informationBasis: 85,
        components: [],
        matches: [],
        gaps: [],
        missingInfo: [],
        explanation: {
          title: 'Compatibilidad de ejemplo',
          text: 'Tu Java coincide con lo solicitado.',
        },
        formula: {
          version: 'demo',
          weights: { required: 70, desirable: 15, conditions: 15, similarity: 0 },
          cap: 95,
          minEvaluableWeight: 50,
          similarityEnabled: false,
        },
      })
    if (path === '/applications/summary')
      return json({
        active: state.applications ? 1 : 0,
        withdrawn: 0,
        latest: state.applications ? application : null,
      })
    if (path === '/applications')
      return json({
        items: state.applications ? [application] : [],
        page: 1,
        size: 20,
        totalItems: state.applications ? 1 : 0,
        totalPages: 1,
      })
    if (path === '/applications/' + application.id) return json(application)
    if (path === '/applications/by-vacancy/' + vacancy.id) return route.fulfill({ status: 204 })
    if (path === '/cv/analyses/current' || path === '/github') return route.fulfill({ status: 204 })
    if (path === '/cv/usage')
      return json({
        basic: { available: true },
        enhanced: { available: false, reason: 'DISABLED', used: 0, limit: 3, resetsAt: null },
      })
    if (path === '/practice/options')
      return json({
        bases: [{ basis: 'PROFILE', available: true, vacancies: [] }],
        topics: [{ slug: 'interview', name: 'Presentar mis proyectos' }],
        maxQuestions: 5,
        quota: { used: 0, limit: 5, resetsAt: '2026-11-01' },
        voiceAvailable: false,
        interviewNotice: { version: 'demo', text: 'Preparación de ejemplo' },
      })
    if (path === '/practice/sessions')
      return json({ items: [], page: 1, size: 20, totalItems: 0, totalPages: 0 })
    return json({ code: 'not-found' }, 404)
  })
  return state
}

async function capture(page: Page, name: string) {
  await page.evaluate(async () => {
    await document.fonts.ready
    window.scrollTo({ top: 0, behavior: 'instant' })
  })
  await page.screenshot({
    path: `${output}/${before ? 'antes' : 'nuevo'}-${name}.png`,
    fullPage: true,
    animations: 'disabled',
  })
}
async function widths(page: Page, name: string) {
  for (const width of [1280, 768, 360]) {
    await page.setViewportSize({ width, height: 900 })
    expect(
      await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth + 1),
      name + ' overflow',
    ).toBe(true)
    await capture(page, `${name}-${width}`)
  }
}
async function open(page: Page, path: string) {
  await page.goto(path)
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await expect(page.locator('main [aria-busy="true"]')).toHaveCount(0)
  const reject = page.getByRole('button', { name: es.cookies.banner.reject, exact: true })
  if (await reject.count()) await reject.click()
}

test('D-77: inicio, vacantes, regreso filtrado y seguimiento en tres anchos', async ({
  page,
  baseURL,
}) => {
  expect(new URL(baseURL!).port).toBe(before ? '3000' : '3095')
  mkdirSync(output, { recursive: true })
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await fixture(page)
  await open(page, '/')
  await expect(page.getByRole('heading', { name: 'Hola, Ana' })).toBeVisible()
  await expect(page.locator('.home__side > section')).toHaveCount(2)
  await widths(page, 'inicio')
  await open(page, '/oportunidades?q=java&modalidad=remoto')
  await expect(page.locator('.vacancy-card')).toHaveCount(1)
  await widths(page, 'vacantes')
  await page.locator('.vacancy-card__link').click()
  await expect(page.getByRole('heading', { level: 1 })).toHaveText(vacancy.title)
  await widths(page, 'vacante')
  await page.getByRole('link', { name: es.vacancyDetail.back, exact: true }).click()
  await expect(page).toHaveURL(/q=java/)
  await expect(page).toHaveURL(/modalidad=remoto/)
  await open(page, '/postulaciones')
  await expect(page.locator('.application-item')).toHaveCount(1)
  await widths(page, 'postulaciones')
  await page.locator('.application-item a').first().click()
  await expect(page.locator('.applications__panel')).toBeVisible()
  await widths(page, 'seguimiento')
  const accessibility = await new AxeBuilder({ page }).include('main').analyze()
  expect(
    accessibility.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious'),
  ).toEqual([])
  expect(errors).toEqual([])
})

test('D-77: CV, preparación DORA, GitHub, acceso y estados sin resultados', async ({ page }) => {
  mkdirSync(output, { recursive: true })
  const state = await fixture(page)
  for (const [path, name] of [
    ['/perfil/cv', 'cv'],
    ['/practicar', 'preparar-dora'],
    ['/perfil/github', 'github'],
  ]) {
    await open(page, path!)
    await widths(page, name!)
  }
  state.applications = false
  await open(page, '/postulaciones')
  await expect(page.locator('.applications__empty')).toBeVisible()
  await capture(page, 'postulaciones-vacio-360')
  state.vacancies = 'empty'
  await open(page, '/oportunidades?q=sin-resultados')
  await expect(page.locator('.opportunities__empty')).toBeVisible()
  await capture(page, 'vacantes-vacio-360')
  state.vacancies = 'error'
  await page.reload()
  await expect(page.getByRole('alert')).toBeVisible()
  await capture(page, 'vacantes-error-360')
  state.vacancies = 'loading'
  await page.reload()
  await expect(page.locator('.skeleton').first()).toBeVisible()
  await capture(page, 'vacantes-carga-360')
  state.vacancies = 'ready'
  await expect(page.locator('.vacancy-card')).toBeVisible()
  state.student = false
  await open(page, '/ingresar')
  await widths(page, 'ingresar')
})

test('D-77: revisión de postulación, acceso, documentos y avisos de roles existentes', async ({
  page,
}) => {
  mkdirSync(output, { recursive: true })
  const state = await fixture(page)
  state.applications = false
  await open(page, '/oportunidades/vacancy-demo/postular')
  await widths(page, 'revisar-postulacion')
  for (const [path, name] of [
    ['/terminos', 'terminos'],
    ['/privacidad', 'privacidad'],
    ['/cookies', 'cookies'],
    ['/transparencia', 'transparencia'],
    ['/pagina-inexistente', '404'],
  ]) {
    await open(page, path!)
    await widths(page, name!)
  }
  state.legalPending = true
  await open(page, '/aceptar-documentos')
  await widths(page, 'aceptar-documentos')
  state.legalPending = false
  for (const role of ['COMPANY', 'ADMIN']) {
    state.role = role
    await open(page, '/panel')
    await widths(page, 'aviso-' + role.toLowerCase())
  }
  state.student = false
  for (const [path, name] of [
    ['/registro', 'registro'],
    ['/recuperar-cuenta', 'recuperar'],
  ]) {
    await open(page, path!)
    await widths(page, name!)
  }
})
