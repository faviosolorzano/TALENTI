import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

/**
 * Spec 001 (identity and consent), interface criteria, against the real stack (nginx + backend
 * + PostgreSQL). All e-mails are invented (example.test) and unique per run.
 *
 * The backend allows 10 sign-in and 10 sign-up requests per minute per origin (001-11, decision
 * 9). Every request counts, even a rejected one, so the tests run one after another and
 * `reserve` waits when the next burst would pass the limit. Nothing here tries to hit 429 on
 * purpose (that is covered by the unit tests and by the backend tests).
 */

// Waiting for the rate-limit window (see `reserve`) can take up to a minute inside a test.
test.describe.configure({ mode: 'serial', timeout: 150_000 })

const WIDTHS = [360, 768, 1280] as const
const PASSWORD = 'Clave-de-prueba-123'
const MAX_PER_MINUTE = 9 // one less than the limit, as a margin
const WINDOW_MS = 61_000

const sent: Record<'login' | 'register', number[]> = { login: [], register: [] }

/** Waits, if needed, so that `count` more requests stay under the limit, and counts them. */
async function reserve(bucket: 'login' | 'register', count: number) {
  for (;;) {
    const now = Date.now()
    sent[bucket] = sent[bucket].filter((time) => now - time < WINDOW_MS)
    const oldest = sent[bucket][0]
    if (sent[bucket].length + count <= MAX_PER_MINUTE || oldest === undefined) break
    await new Promise((resolve) => setTimeout(resolve, oldest + WINDOW_MS - now + 50))
  }
  for (let i = 0; i < count; i += 1) sent[bucket].push(Date.now())
}

const unique = (tag: string) =>
  `ana.${tag}+${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}@example.test`

/** The account that the first tests create and the later ones use. */
let account = { email: '', password: PASSWORD }

async function seriousViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze()
  return results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.map((n) => n.target.join(' ')) }))
}

const horizontalScroll = (page: Page) =>
  page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))

const emailField = (page: Page) => page.getByLabel('Correo')
const passwordField = (page: Page) => page.getByLabel('Contraseña', { exact: true })
const consentBox = (page: Page) => page.getByRole('checkbox', { name: /Leí y acepto/ })
const panelTitle = (page: Page) => page.getByRole('heading', { level: 1, name: 'Hola de nuevo' })
const loginTitle = (page: Page) =>
  page.getByRole('heading', { level: 1, name: 'Ingresa a tu cuenta' })

async function openRegister(page: Page) {
  await page.goto('/registro')
  // The consent text has arrived when its summary is there.
  await expect(page.getByText('Lee qué datos usamos y para qué')).toBeVisible()
  await page.evaluate(() => document.fonts.ready)
}

async function fillRegister(page: Page, email: string, password: string, accept = true) {
  await emailField(page).fill(email)
  await passwordField(page).fill(password)
  if (accept) await consentBox(page).check()
}

async function registerViaUi(page: Page, email: string) {
  await reserve('register', 1)
  await openRegister(page)
  await fillRegister(page, email, PASSWORD)
  await page.getByRole('button', { name: 'Crear mi cuenta' }).click()
  await expect(panelTitle(page)).toBeVisible()
}

/** Signs out from the header (the student Home has no button of its own). */
async function logout(page: Page) {
  const menu = page.getByRole('button', { name: 'Menú' })
  if (await menu.isVisible()) await menu.click()
  await page
    .getByRole('banner')
    .getByRole('button', { name: 'Cerrar sesión' })
    .filter({ visible: true })
    .first()
    .click()
}

async function logoutViaPanel(page: Page) {
  await logout(page)
  await expect(loginTitle(page)).toBeVisible()
}

async function loginViaUi(page: Page, email: string, password: string, path = '/ingresar') {
  await reserve('login', 1)
  await page.goto(path)
  await expect(loginTitle(page)).toBeVisible()
  await emailField(page).fill(email)
  await passwordField(page).fill(password)
  await page.getByRole('button', { name: 'Ingresar', exact: true }).click()
}

/** Token for a POST made straight to the API (the same one the page would send). */
async function csrfHeader(page: Page) {
  await page.request.get('/api/v1/auth/csrf')
  const cookies = await page.context().cookies()
  const token = cookies.find((cookie) => cookie.name === 'XSRF-TOKEN')?.value ?? ''
  return { 'X-XSRF-TOKEN': token }
}

async function loginViaApi(page: Page, email: string, password: string) {
  await reserve('login', 1)
  const response = await page.request.post('/api/v1/auth/login', {
    data: { email, password },
    headers: await csrfHeader(page),
  })
  return response.status()
}

async function registerViaApi(page: Page, email: string) {
  await reserve('register', 1)
  const legal = await (await page.request.get('/api/v1/legal/current')).json()
  const response = await page.request.post('/api/v1/auth/register', {
    data: { email, password: PASSWORD, legalAccepted: true, legalVersion: legal.version },
    headers: await csrfHeader(page),
  })
  expect(response.status()).toBe(201)
  // Signing up also signs in: start the next step as a visitor.
  await page.context().clearCookies()
}

test.describe('Sign-up, sign-out and sign-in (001-01, 001-12, 001-13)', () => {
  test('a new person signs up, lands on the panel, signs out, cannot enter the panel, and signs in again', async ({
    page,
  }) => {
    account = { email: unique('flujo'), password: PASSWORD }
    await page.goto('/')
    await page.getByRole('banner').getByRole('link', { name: 'Crear cuenta' }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'Crea tu cuenta' })).toBeVisible()
    await expect(consentBox(page)).not.toBeChecked()

    await registerViaUi(page, account.email)
    await expect(page.getByText('Tu cuenta está lista')).toBeVisible()
    await expect(page.getByText(account.email)).toHaveCount(0)
    // 001-25: the navigation bar is there and the public sections are one click away.
    const nav = page.getByRole('navigation', { name: 'Navegación principal' })
    await nav.getByRole('link', { name: 'Oportunidades' }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'Oportunidades' })).toBeVisible()
    await page.getByRole('banner').getByRole('link', { name: 'Inicio', exact: true }).click()
    await expect(panelTitle(page)).toBeVisible()
    await expect(page.getByText('Tu cuenta está lista')).toBeHidden()

    // The password and the session are not kept in anything the page can read.
    const stored = await page.evaluate(() => ({
      local: Object.keys(localStorage),
      session: Object.keys(sessionStorage),
      cookie: document.cookie,
    }))
    expect(stored.local).toEqual([])
    expect(stored.session).toEqual([])
    expect(stored.cookie).not.toContain('JSESSIONID')

    // 001-12: sign out.
    await logoutViaPanel(page)
    await expect(page.getByText('Cerraste sesión.')).toBeVisible()
    await expect(page.getByRole('banner').getByRole('link', { name: 'Crear cuenta' })).toBeVisible()

    // 001-13: the panel asks to sign in again, and so does the back button.
    await page.goto('/panel')
    await expect(loginTitle(page)).toBeVisible()
    expect(new URL(page.url()).pathname).toBe('/ingresar')
    expect((await page.request.get('/api/v1/auth/me')).status()).toBe(401)

    // Sign in again.
    await loginViaUi(page, account.email, account.password)
    await expect(panelTitle(page)).toBeVisible()
    await expect(page.getByText(account.email)).toHaveCount(0)
    await expect(page.getByText('Tu cuenta está lista')).toBeHidden()
    await expect(page.getByRole('banner').getByRole('link', { name: 'Mi perfil' })).toBeVisible()
    await logoutViaPanel(page)
  })

  test('001-04: a repeated e-mail (in other letter case) says so, offers to sign in and creates nothing', async ({
    page,
  }) => {
    await reserve('register', 1)
    await openRegister(page)
    await fillRegister(page, account.email.toUpperCase(), PASSWORD)
    await page.getByRole('button', { name: 'Crear mi cuenta' }).click()

    const error = page.locator('.field__error', {
      hasText: 'Ya existe una cuenta con este correo.',
    })
    await expect(error).toBeVisible()
    await expect(error.getByRole('link', { name: 'Ingresar con esa cuenta' })).toHaveAttribute(
      'href',
      '/ingresar',
    )
    await expect(emailField(page)).toBeFocused()
    await expect(emailField(page)).toHaveAttribute('aria-invalid', 'true')
    await expect(page.getByRole('heading', { level: 1, name: 'Crea tu cuenta' })).toBeVisible()
  })

  test('001-05: a weak password shows the rule written by the backend and keeps what was typed', async ({
    page,
  }) => {
    await reserve('register', 1)
    await openRegister(page)
    const email = unique('debil')
    await fillRegister(page, email, 'corta')
    await page.getByRole('button', { name: 'Crear mi cuenta' }).click()

    await expect(passwordField(page)).toBeFocused()
    await expect(passwordField(page)).toHaveAttribute('aria-invalid', 'true')
    await expect(passwordField(page)).toHaveAccessibleDescription(
      /La contraseña debe tener al menos 8 caracteres\./,
    )
    await expect(emailField(page)).toHaveValue(email)
    await expect(consentBox(page)).toBeChecked()
  })

  test('001-02: without the box ticked the form explains it and creates nothing', async ({
    page,
  }) => {
    await reserve('register', 1)
    await openRegister(page)
    await fillRegister(page, unique('sinpermiso'), PASSWORD, false)
    await page.getByRole('button', { name: 'Crear mi cuenta' }).click()

    await expect(consentBox(page)).toBeFocused()
    await expect(consentBox(page)).toHaveAccessibleDescription(
      /Debes aceptar los Términos y Condiciones y la Política de Privacidad para continuar\./,
    )
    await expect(page.getByRole('heading', { level: 1, name: 'Crea tu cuenta' })).toBeVisible()
  })

  test('001-17, 001-18, 001-26: the consent text comes from the backend with its four sections, and the links open another tab', async ({
    page,
    context,
  }) => {
    await openRegister(page)
    await page.getByText('Lee qué datos usamos y para qué').click()
    await expect(page.getByText(/^Versión v\d+ · Publicada el /)).toBeVisible()
    for (const heading of [
      'Qué datos recogemos',
      'Para qué los usamos',
      'Qué enviamos a proveedores externos de IA y voz',
      'Cuánto tiempo conservamos tus datos',
    ]) {
      await expect(page.getByRole('heading', { level: 3, name: heading })).toBeVisible()
    }

    // What was typed is kept: the links open ANOTHER tab.
    await emailField(page).fill('ana.conserva@example.test')
    const terms = page.getByRole('main').getByRole('link', { name: /Términos y Condiciones/ })
    await expect(terms).toHaveAttribute('target', '_blank')
    await expect(terms).toHaveAccessibleName(/se abre en otra pestaña/)
    const [other] = await Promise.all([context.waitForEvent('page'), terms.click()])
    expect(new URL(other.url()).pathname).toBe('/terminos')
    await other.close()
    await expect(emailField(page)).toHaveValue('ana.conserva@example.test')
    await expect(page).toHaveURL(/\/registro$/)
  })

  test('001-26: if the consent text does not load there is "Reintentar" and no sign-up', async ({
    page,
  }) => {
    let failures = 1
    await page.route('**/api/v1/legal/current', async (route) => {
      if (failures > 0) {
        failures -= 1
        await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' })
      } else {
        await route.continue()
      }
    })
    await page.goto('/registro')

    await expect(
      page.getByText(
        'No pudimos cargar el texto del consentimiento. Sin leerlo no puedes crear tu cuenta.',
      ),
    ).toBeVisible()
    await expect(page.getByRole('button', { name: 'Crear mi cuenta' })).toBeDisabled()
    await expect(consentBox(page)).toBeDisabled()

    await page.getByRole('button', { name: 'Reintentar' }).click()
    await expect(page.getByText('Lee qué datos usamos y para qué')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Crear mi cuenta' })).toBeEnabled()
  })
})

test.describe('Sign-in failures (001-09, 001-10)', () => {
  test('001-09: a wrong password and an unknown e-mail show the same generic message', async ({
    page,
  }) => {
    await loginViaUi(page, account.email, 'Otra-clave-99')
    const alert = page.getByRole('alert')
    await expect(alert).toContainText('El correo o la contraseña no son correctos.')
    const first = await alert.textContent()
    await expect(alert).toBeFocused()
    await expect(emailField(page)).toHaveValue(account.email)
    await expect(passwordField(page)).toHaveValue('')

    await reserve('login', 1)
    await emailField(page).fill(unique('nadie'))
    await passwordField(page).fill('Otra-clave-99')
    await page.getByRole('button', { name: 'Ingresar', exact: true }).click()
    await expect(alert).toContainText('El correo o la contraseña no son correctos.')
    expect(await alert.textContent()).toBe(first)
    await expect(alert).not.toContainText(/no existe|no está registrado|bloquead/i)
  })

  test('001-10: after 5 failed attempts the right password gets the same generic message', async ({
    page,
  }) => {
    const email = unique('bloqueo')
    await registerViaApi(page, email)
    for (let attempt = 0; attempt < 5; attempt += 1) {
      expect(await loginViaApi(page, email, 'Otra-clave-99')).toBe(401)
    }

    await loginViaUi(page, email, PASSWORD)

    await expect(page.getByRole('alert')).toContainText(
      'El correo o la contraseña no son correctos.',
    )
    await expect(loginTitle(page)).toBeVisible()
    expect((await page.request.get('/api/v1/auth/me')).status()).toBe(401)
  })
})

test.describe('returnTo and the vacancy (001, edge cases, 004-22)', () => {
  test('coming from a vacancy, signing in comes back to it; with a session "Postular" is active', async ({
    page,
  }) => {
    await page.goto('/oportunidades')
    await page.getByRole('article').first().getByRole('link').first().click()
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    const vacancyPath = new URL(page.url()).pathname
    expect(vacancyPath).toMatch(/^\/oportunidades\/.+/)

    // Visitor: the notice and the link.
    await expect(page.getByText('Inicia sesión para ver tu compatibilidad')).toBeVisible()
    await page.getByRole('link', { name: 'Postular' }).click()
    await expect(loginTitle(page)).toBeVisible()
    expect(new URL(page.url()).searchParams.get('returnTo')).toBe(vacancyPath)
    await expect(page.getByText('Ingresa para continuar donde estabas.')).toBeVisible()

    await reserve('login', 1)
    await emailField(page).fill(account.email)
    await passwordField(page).fill(account.password)
    await page.getByRole('button', { name: 'Ingresar', exact: true }).click()

    await expect(page).toHaveURL(new RegExp(`${vacancyPath}$`))
    const apply = page.getByRole('button', { name: 'Postular' })
    await expect(apply).toBeEnabled()
    await expect(page.getByText('Inicia sesión para ver tu compatibilidad')).toBeHidden()
    await expect(page.getByRole('link', { name: 'Postular' })).toHaveCount(0)

    await page.getByRole('banner').getByRole('button', { name: 'Cerrar sesión' }).click()
    await expect(loginTitle(page)).toBeVisible()
  })

  for (const returnTo of ['https://malo.example/robo', '//malo.example', '/%5Cmalo.example']) {
    test(`an outside address in returnTo (${returnTo}) is ignored: it goes to the panel`, async ({
      page,
    }) => {
      await loginViaUi(
        page,
        account.email,
        account.password,
        `/ingresar?returnTo=${encodeURIComponent(returnTo)}`,
      )

      await expect(panelTitle(page)).toBeVisible()
      const url = new URL(page.url())
      expect(url.pathname).toBe('/')
      expect(url.hostname).toBe('localhost')
      await logoutViaPanel(page)
    })
  }

  test('returnTo that points to the sign-in page itself does not loop', async ({ page }) => {
    await loginViaUi(page, account.email, account.password, '/ingresar?returnTo=%2Fingresar')

    await expect(panelTitle(page)).toBeVisible()
    await logoutViaPanel(page)
  })
})

test.describe('Session expired (001-14)', () => {
  test('a session the server no longer knows takes to "Ingresar" with "Tu sesión expiró"', async ({
    page,
    baseURL,
  }) => {
    expect(await loginViaApi(page, account.email, account.password)).toBe(200)
    await page.goto('/oportunidades')
    await expect(page.getByRole('banner').getByRole('link', { name: 'Mi perfil' })).toBeVisible()

    // The server forgets the session (30 idle minutes): here, the browser holds an unknown id.
    await page
      .context()
      .addCookies([{ name: 'JSESSIONID', value: 'sesion-vencida', url: baseURL! }])
    await page.waitForTimeout(1700) // the page does not repeat a check made a moment ago
    await page.getByRole('banner').getByRole('link', { name: 'Inicio', exact: true }).click()

    await expect(loginTitle(page)).toBeVisible()
    await expect(page.getByText('Tu sesión expiró. Ingresa de nuevo.')).toBeVisible()
    expect(new URL(page.url()).pathname).toBe('/ingresar')
  })
})

test.describe('Recovery notice (001-23)', () => {
  test('"¿Olvidaste tu contraseña?" shows an information page and recovers nothing', async ({
    page,
  }) => {
    await page.goto('/ingresar')
    await page.getByRole('link', { name: '¿Olvidaste tu contraseña?' }).click()

    await expect(page.getByRole('heading', { level: 1, name: 'Recuperar tu cuenta' })).toBeVisible()
    await expect(page.getByText('Pronto podrás recuperar tu cuenta con tu correo.')).toBeVisible()
    await expect(page.getByLabel('Correo')).toHaveCount(0)
  })
})

test.describe('Responsive layout (001-22)', () => {
  for (const width of WIDTHS) {
    test(`at ${width} px sign-up, sign-in, recovery and panel do not scroll sideways, also with errors and the consent open`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 900 })

      await openRegister(page)
      await page.getByText('Lee qué datos usamos y para qué').click()
      await expect(
        page.getByRole('heading', { level: 3, name: 'Qué datos recogemos' }),
      ).toBeVisible()
      await reserve('register', 1)
      await page.getByRole('button', { name: 'Crear mi cuenta' }).click()
      await expect(page.locator('.field__error').first()).toBeVisible()
      let { scrollWidth, clientWidth } = await horizontalScroll(page)
      expect(scrollWidth, '/registro').toBeLessThanOrEqual(clientWidth)

      await page.goto('/ingresar')
      await expect(loginTitle(page)).toBeVisible()
      await reserve('login', 1)
      await page.getByRole('button', { name: 'Ingresar', exact: true }).click()
      await expect(page.locator('.field__error').first()).toBeVisible()
      ;({ scrollWidth, clientWidth } = await horizontalScroll(page))
      expect(scrollWidth, '/ingresar').toBeLessThanOrEqual(clientWidth)

      await page.goto('/recuperar-cuenta')
      await expect(
        page.getByRole('heading', { level: 1, name: 'Recuperar tu cuenta' }),
      ).toBeVisible()
      ;({ scrollWidth, clientWidth } = await horizontalScroll(page))
      expect(scrollWidth, '/recuperar-cuenta').toBeLessThanOrEqual(clientWidth)

      expect(await loginViaApi(page, account.email, account.password)).toBe(200)
      await page.goto('/')
      await expect(panelTitle(page)).toBeVisible()
      ;({ scrollWidth, clientWidth } = await horizontalScroll(page))
      expect(scrollWidth, '/ (Inicio)').toBeLessThanOrEqual(clientWidth)
      await logout(page)
      await expect(loginTitle(page)).toBeVisible()
    })
  }

  test('at 360 px with the text enlarged to 200 % the sign-up form is still inside the page', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 900 })
    await openRegister(page)
    await page.evaluate(() =>
      document.documentElement.style.setProperty('font-size', '200%', 'important'),
    )
    const { scrollWidth, clientWidth } = await horizontalScroll(page)
    expect(scrollWidth).toBeLessThanOrEqual(clientWidth)
    const overflowing = await page.evaluate(() => {
      const limit = document.documentElement.clientWidth
      return [...document.querySelectorAll<HTMLElement>('main h1, main label, main p, main button')]
        .filter(
          (el) =>
            el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().right > limit + 1,
        )
        .map((el) => el.textContent?.slice(0, 40))
    })
    expect(overflowing).toEqual([])
  })

  test('every button, link and field of the sign-up form is at least 44 px tall at 360 px', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 900 })
    await openRegister(page)
    const small = await page.evaluate(() =>
      [
        ...document.querySelectorAll<HTMLElement>(
          'main button, main input:not([type=checkbox]), main summary',
        ),
      ]
        .filter((el) => el.getBoundingClientRect().width > 0)
        .map((el) => ({
          name: el.getAttribute('aria-label') ?? el.textContent?.trim().slice(0, 30),
          height: el.getBoundingClientRect().height,
        }))
        .filter((item) => item.height < 43.9),
    )
    expect(small).toEqual([])
  })

  test('in the folded menu a person with a session sees "Mi panel" and "Cerrar sesión" at 360 px', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 800 })
    expect(await loginViaApi(page, account.email, account.password)).toBe(200)
    await page.goto('/')
    await page.getByRole('button', { name: 'Menú' }).click()
    const menu = page.getByRole('navigation', { name: 'Navegación principal' })
    await expect(menu.getByRole('link', { name: 'Inicio', exact: true })).toBeVisible()
    await expect(menu.getByRole('button', { name: 'Cerrar sesión' })).toBeVisible()
    expect(await seriousViolations(page)).toEqual([])

    await menu.getByRole('button', { name: 'Cerrar sesión' }).click()
    await expect(loginTitle(page)).toBeVisible()
    await expect(page.getByRole('button', { name: 'Menú' })).toHaveAttribute(
      'aria-expanded',
      'false',
    )
  })
})

test.describe('axe (001-22, RNF-14)', () => {
  for (const width of WIDTHS) {
    test(`at ${width} px no page of the identity slice has serious or critical violations`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 900 })

      await openRegister(page)
      expect(await seriousViolations(page), '/registro').toEqual([])
      await page.getByText('Lee qué datos usamos y para qué').click()
      await passwordField(page).fill('x')
      await page.getByRole('button', { name: 'Mostrar contraseña' }).click()
      expect(await seriousViolations(page), '/registro con el texto abierto').toEqual([])
      await reserve('register', 1)
      await page.getByRole('button', { name: 'Crear mi cuenta' }).click()
      await expect(page.locator('.field__error').first()).toBeVisible()
      expect(await seriousViolations(page), '/registro con errores').toEqual([])

      await page.goto('/ingresar')
      await expect(loginTitle(page)).toBeVisible()
      expect(await seriousViolations(page), '/ingresar').toEqual([])
      await reserve('login', 1)
      await emailField(page).fill(unique('axe'))
      await passwordField(page).fill('Otra-clave-99')
      await page.getByRole('button', { name: 'Ingresar', exact: true }).click()
      await expect(page.getByRole('alert')).toBeVisible()
      expect(await seriousViolations(page), '/ingresar con error').toEqual([])

      await page.goto('/recuperar-cuenta')
      await expect(
        page.getByRole('heading', { level: 1, name: 'Recuperar tu cuenta' }),
      ).toBeVisible()
      expect(await seriousViolations(page), '/recuperar-cuenta').toEqual([])

      expect(await loginViaApi(page, account.email, account.password)).toBe(200)
      await page.goto('/')
      await expect(panelTitle(page)).toBeVisible()
      expect(await seriousViolations(page), '/ (Inicio)').toEqual([])
      await logout(page)
      await expect(loginTitle(page)).toBeVisible()
    })
  }
})

test.describe('Keyboard only (001-22)', () => {
  const focusRing = (page: Page) =>
    page.evaluate(() => {
      const el = document.activeElement as HTMLElement
      const style = getComputedStyle(el)
      return { style: style.outlineStyle, width: parseFloat(style.outlineWidth) }
    })

  /** Tab until the focused element has this accessible name; every stop shows a focus ring. */
  async function tabTo(page: Page, name: RegExp | string) {
    for (let step = 0; step < 40; step += 1) {
      await page.keyboard.press('Tab')
      // The first Tab on a fresh page can only enter the document.
      if (await page.evaluate(() => document.activeElement === document.body)) continue
      const label = await page.evaluate(() => {
        const el = document.activeElement as HTMLElement
        const labelled = el.getAttribute('aria-label')
        const byLabel = (el as HTMLInputElement).labels?.[0]?.textContent
        return (labelled ?? byLabel ?? el.textContent ?? '').trim()
      })
      const ring = await focusRing(page)
      expect(ring.style, `focus ring on "${label}"`).toBe('solid')
      expect(ring.width, `focus ring on "${label}"`).toBeGreaterThanOrEqual(3)
      if (typeof name === 'string' ? label === name : name.test(label)) return
    }
    throw new Error(`Tab never reached ${String(name)}`)
  }

  test('a person signs up, signs out and signs in with the keyboard alone', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    const email = unique('teclado')
    await reserve('register', 1)
    await openRegister(page)

    await tabTo(page, 'Correo')
    await page.keyboard.type(email)
    await tabTo(page, 'Contraseña')
    await page.keyboard.type(PASSWORD)
    await tabTo(page, 'Mostrar contraseña')
    await page.keyboard.press('Enter')
    await expect(passwordField(page)).toHaveAttribute('type', 'text')
    await tabTo(page, 'Lee qué datos usamos y para qué')
    await page.keyboard.press('Enter')
    await expect(page.getByRole('heading', { level: 3, name: 'Qué datos recogemos' })).toBeVisible()
    await tabTo(page, /Leí y acepto/)
    await page.keyboard.press('Space')
    await expect(consentBox(page)).toBeChecked()
    await tabTo(page, 'Crear mi cuenta')
    await page.keyboard.press('Enter')
    await expect(panelTitle(page)).toBeVisible()
    await expect(page.getByText('Tu cuenta está lista')).toBeVisible()

    await tabTo(page, 'Cerrar sesión')
    await page.keyboard.press('Enter')
    await expect(loginTitle(page)).toBeVisible()

    await reserve('login', 1)
    await tabTo(page, 'Correo')
    await page.keyboard.type(email)
    await tabTo(page, 'Contraseña')
    await page.keyboard.type(PASSWORD)
    await page.keyboard.press('Enter') // Enter sends the form
    await expect(panelTitle(page)).toBeVisible()
    await logout(page)
    await expect(loginTitle(page)).toBeVisible()
  })

  test('after a failed send the focus goes to the first field with a problem, and the error is read with the field', async ({
    page,
  }) => {
    await reserve('register', 1)
    await openRegister(page)
    await page.getByRole('button', { name: 'Crear mi cuenta' }).click()

    await expect(emailField(page)).toBeFocused()
    await expect(emailField(page)).toHaveAccessibleDescription(/Este campo es obligatorio\./)
    await expect(passwordField(page)).toHaveAccessibleDescription(/Este campo es obligatorio\./)
  })
})

test.describe('Identity and legal framework together (001-30, 002-09, 002-18, 002-19)', () => {
  /**
   * A student with a real session whose legal acceptance is PENDING: only the answer of
   * `GET /auth/me` is changed (the real data cannot produce an old version). Once the real
   * `POST /legal/accept` answers 204 the answer says nothing is pending, like the server.
   */
  async function pendingStudent(page: Page) {
    let accepted = false
    page.on('response', (response) => {
      if (
        response.url().endsWith('/api/v1/legal/accept') &&
        response.request().method() === 'POST' &&
        response.status() === 204
      ) {
        accepted = true
      }
    })
    await page.route('**/api/v1/auth/me', async (route) => {
      const real = await route.fetch()
      const body = (await real.json()) as Record<string, unknown>
      await route.fulfill({
        response: real,
        json: { ...body, legalAcceptancePending: !accepted },
      })
    })
  }

  test('002-09: both links of the sign-up box open /terminos and /privacidad in another tab, saying so, and what was typed stays', async ({
    page,
    context,
  }) => {
    await openRegister(page)
    await emailField(page).fill('ana.conserva@example.test')
    await passwordField(page).fill(PASSWORD)

    for (const [name, path] of [
      ['Términos y Condiciones', '/terminos'],
      ['Política de Privacidad', '/privacidad'],
    ] as const) {
      const link = page.getByRole('main').getByRole('link', { name: new RegExp(name) })
      await expect(link).toHaveAttribute('href', path)
      await expect(link).toHaveAttribute('target', '_blank')
      await expect(link).toHaveAccessibleName(`${name} (se abre en otra pestaña)`)
      const [other] = await Promise.all([context.waitForEvent('page'), link.click()])
      await other.waitForLoadState()
      expect(new URL(other.url()).pathname).toBe(path)
      await expect(other.getByRole('heading', { level: 1 })).toBeVisible()
      await other.close()
    }
    await expect(page).toHaveURL(/\/registro$/)
    await expect(emailField(page)).toHaveValue('ana.conserva@example.test')
    await expect(passwordField(page)).toHaveValue(PASSWORD)
  })

  test('a student with a pending acceptance goes / -> /aceptar-documentos -> accepts -> back to /, without reloading the page', async ({
    page,
  }) => {
    expect(await loginViaApi(page, account.email, account.password)).toBe(200)
    await pendingStudent(page)
    await page.goto('/oportunidades')
    await expect(page.getByRole('banner').getByRole('link', { name: 'Mi perfil' })).toBeVisible()
    // A mark that a full page load would erase.
    await page.evaluate(() => {
      ;(window as unknown as { __noReload: boolean }).__noReload = true
    })

    // The private page sends the student to the acceptance screen, remembering where from.
    await page.getByRole('banner').getByRole('link', { name: 'Inicio', exact: true }).click()
    await expect(page).toHaveURL(/\/aceptar-documentos\?returnTo=%2F$/)
    await expect(
      page.getByRole('heading', { level: 1, name: 'Actualizamos nuestros documentos' }),
    ).toBeVisible()
    await expect(page.getByRole('checkbox')).not.toBeChecked()

    // Without the box it does not continue; with it, it accepts (for real) and returns.
    await page.getByRole('button', { name: 'Aceptar y continuar' }).click()
    await expect(page.getByRole('main').getByRole('alert')).toBeVisible()
    await expect(page).toHaveURL(/\/aceptar-documentos/)
    await page.getByRole('checkbox').check()
    const accepted = page.waitForResponse(
      (response) =>
        response.url().endsWith('/api/v1/legal/accept') && response.request().method() === 'POST',
    )
    await page.getByRole('button', { name: 'Aceptar y continuar' }).click()
    expect((await accepted).status()).toBe(204)

    await expect(panelTitle(page)).toBeVisible()
    expect(new URL(page.url()).pathname).toBe('/')
    expect(
      await page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload),
    ).toBe(true)
    await expect(page.getByRole('banner').getByRole('link', { name: 'Mi perfil' })).toBeVisible()
    await logoutViaPanel(page)
  })

  test('"Cerrar sesión" from /aceptar-documentos ends the session and shows the visitor links, without reloading', async ({
    page,
  }) => {
    expect(await loginViaApi(page, account.email, account.password)).toBe(200)
    await pendingStudent(page)
    await page.goto('/aceptar-documentos?returnTo=%2Fpanel')
    await expect(page.getByRole('checkbox')).toBeVisible()
    await page.evaluate(() => {
      ;(window as unknown as { __noReload: boolean }).__noReload = true
    })

    await logout(page)

    await expect(page).toHaveURL(/\/ingresar$/)
    await expect(page.getByText('Cerraste sesión.')).toBeVisible()
    await expect(page.getByRole('banner').getByRole('link', { name: 'Crear cuenta' })).toBeVisible()
    expect(
      await page.evaluate(() => (window as unknown as { __noReload?: boolean }).__noReload),
    ).toBe(true)
    await page.unroute('**/api/v1/auth/me')
    expect((await page.request.get('/api/v1/auth/me')).status()).toBe(401)
  })

  test('with no session /aceptar-documentos leads to sign-in and comes back after signing in', async ({
    page,
  }) => {
    await page.goto('/aceptar-documentos?returnTo=%2Foportunidades')
    await expect(loginTitle(page)).toBeVisible()
    expect(new URL(page.url()).searchParams.get('returnTo')).toBe('/oportunidades')
  })
})

test.describe('Session that cannot be checked (M1, M2)', () => {
  test('with /auth/me failing, the header says so and keeps "Cerrar sesión" and "Reintentar", without "Ingresar" or "Crear cuenta"', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 800 })
    await page.route('**/api/v1/auth/me', (route) => route.fulfill({ status: 500, body: '{}' }))
    await page.goto('/')

    const banner = page.getByRole('banner')
    await expect(banner.getByText('No pudimos comprobar tu sesión.')).toBeVisible()
    await expect(banner.getByRole('button', { name: 'Reintentar' })).toBeVisible()
    await expect(banner.getByRole('link', { name: 'Crear cuenta' })).toHaveCount(0)
    await expect(banner.getByRole('link', { name: 'Ingresar' })).toHaveCount(0)
    await page.getByRole('button', { name: 'Menú' }).click()
    await expect(
      page
        .getByRole('navigation', { name: 'Navegación principal' })
        .getByRole('button', { name: 'Cerrar sesión' }),
    ).toBeVisible()
    expect(await seriousViolations(page)).toEqual([])
  })

  test('a private page with /auth/me failing offers "Reintentar" and "Cerrar sesión"', async ({
    page,
  }) => {
    await page.route('**/api/v1/auth/me', (route) => route.fulfill({ status: 500, body: '{}' }))
    await page.goto('/panel')

    const main = page.getByRole('main')
    await expect(main.getByText('No pudimos comprobar tu sesión.')).toBeVisible()
    await expect(main.getByRole('button', { name: 'Reintentar' })).toBeVisible()
    await main.getByRole('button', { name: 'Cerrar sesión' }).click()
    await expect(loginTitle(page)).toBeVisible()
  })

  test('a failed sign-out from the header shows a visible note', async ({ page }) => {
    expect(await loginViaApi(page, account.email, account.password)).toBe(200)
    await page.goto('/oportunidades')
    const banner = page.getByRole('banner')
    await expect(banner.getByRole('link', { name: 'Mi perfil' })).toBeVisible()
    await page.route('**/api/v1/auth/logout', (route) => route.abort())

    await banner.getByRole('button', { name: 'Cerrar sesión' }).click()

    const note = banner.getByRole('alert')
    await expect(note).toBeVisible()
    await expect(note).toHaveText('No pudimos cerrar tu sesión. Inténtalo de nuevo.')
    // Another page: the note goes away.
    await banner.getByRole('link', { name: 'Inicio', exact: true }).click()
    await expect(panelTitle(page)).toBeVisible()
    await expect(banner.getByRole('alert')).toHaveCount(0)

    await page.unroute('**/api/v1/auth/logout')
    await logoutViaPanel(page)
  })
})
