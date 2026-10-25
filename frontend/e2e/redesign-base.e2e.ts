import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Browser, type Page } from '@playwright/test'

/**
 * Spec 011, part A (base and navigation) against the real stack: a walk through the screens of a
 * visitor and of the demo student (README) at 360, 768 and 1280 px. On every screen: at most one
 * primary button and none in the header, the footer, the cookie notice or the tab bar (011-01),
 * nothing that announces what does not exist (011-02), none of the removed texts of the chrome
 * (011-03), no sideways scroll (011-04), and axe without serious or critical violations (011-05).
 * Then the tab bar (011-06), the visitor header (011-07) and the footer and notice (011-08).
 */
test.describe.configure({ mode: 'serial', timeout: 240_000 })

const WIDTHS = [360, 768, 1280] as const
const DEMO = { email: 'demo@example.test', password: 'Demo-Talenti-2026' }

/** Never drawn anywhere (011-02). */
const NOTHING_THAT_DOES_NOT_EXIST = [/Próximamente/i, /En construcción/i, /disponible pronto/i]
/** Removed from the header, the footer and the notice (011-03). */
const REMOVED_FROM_CHROME = [
  'Cómo funciona',
  'Menú',
  'Mi panel',
  'Configurar',
  'Preferencias de cookies',
  'Oportunidades',
  'Las empresas y vacantes de Talenti son ficticias y de ejemplo.',
]

async function seriousViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze()
  return results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target.join(' ')) }))
}

/** Visible primary buttons: on the whole page and inside the chrome. */
function primaries(page: Page) {
  return page.evaluate(() => {
    const visible = (el: Element) => {
      const box = el.getBoundingClientRect()
      return box.width > 0 && box.height > 0 && getComputedStyle(el).visibility !== 'hidden'
    }
    const all = [...document.querySelectorAll('.btn--primary')].filter(visible)
    const chrome = all.filter((el) =>
      el.closest('header, footer, .cookie-banner, .tabbar, [role="contentinfo"]'),
    )
    return { all: all.map((el) => el.textContent?.trim()), chrome: chrome.length }
  })
}

/** Controls of the header, the footer and the tab bar that are under 44 px tall. */
function smallChromeTargets(page: Page) {
  return page.evaluate(() =>
    [...document.querySelectorAll<HTMLElement>('header a, header button, footer a, footer button')]
      .filter((el) => el.getBoundingClientRect().width > 0)
      .filter((el) => el.getBoundingClientRect().height < 43.9)
      .map((el) => el.textContent?.trim() || el.getAttribute('aria-label')),
  )
}

async function settle(page: Page) {
  await page.waitForLoadState('networkidle')
  await page.evaluate(() => document.fonts.ready)
  await page.waitForTimeout(300)
}

/** Every check of 011-01 to 011-05 on the current page, at the three widths. */
async function checkScreen(page: Page, label: string, expectedPrimaries?: 0 | 1) {
  for (const width of WIDTHS) {
    await page.setViewportSize({ width, height: width < 800 ? 740 : 800 })
    await page.waitForTimeout(150)
    const where = `${label} a ${width} px`

    const { all, chrome } = await primaries(page)
    expect(chrome, `${where}: botón primario en el marco`).toBe(0)
    expect(
      all.length,
      `${where}: más de un botón primario (${all.join(', ')})`,
    ).toBeLessThanOrEqual(1)
    if (expectedPrimaries !== undefined) expect(all.length, where).toBe(expectedPrimaries)

    const text = await page.locator('body').innerText()
    for (const pattern of NOTHING_THAT_DOES_NOT_EXIST) expect(text, where).not.toMatch(pattern)
    const chromeText = (await page.locator('header, footer, .cookie-banner').allInnerTexts()).join(
      '\n',
    )
    for (const removed of REMOVED_FROM_CHROME) expect(chromeText, where).not.toContain(removed)

    const { scrollWidth, clientWidth } = await page.evaluate(() => ({
      scrollWidth: document.documentElement.scrollWidth,
      clientWidth: document.documentElement.clientWidth,
    }))
    expect(scrollWidth, `${where}: desplazamiento horizontal`).toBeLessThanOrEqual(clientWidth)
    expect(await smallChromeTargets(page), where).toEqual([])
    expect(await seriousViolations(page), where).toEqual([])
  }
}

async function firstVacancyPath(page: Page) {
  await page.goto('/oportunidades')
  const link = page.locator('main a[href^="/oportunidades/"]').first()
  await link.waitFor()
  return (await link.getAttribute('href')) as string
}

async function signInDemo(browser: Browser) {
  const context = await browser.newContext()
  const page = await context.newPage()
  await page.goto('/ingresar')
  await page.getByLabel('Correo').fill(DEMO.email)
  await page.getByLabel('Contraseña', { exact: true }).fill(DEMO.password)
  await page.getByRole('main').getByRole('button', { name: 'Ingresar' }).click()
  await page.waitForURL((url) => !url.pathname.startsWith('/ingresar'))
  return page
}

test.describe('Visitor (011-01 to 011-05, 011-07, 011-08)', () => {
  test('every screen of a visitor at 360, 768 and 1280 px', async ({ page }) => {
    const vacancy = await firstVacancyPath(page)
    const screens: Array<[string, (0 | 1)?]> = [
      ['/'],
      ['/oportunidades', 0],
      [vacancy],
      ['/registro', 1],
      ['/ingresar', 1],
      ['/terminos', 0],
      ['/privacidad', 0],
      ['/cookies', 0],
      ['/transparencia', 0],
      ['/no-existe', 1],
    ]
    for (const [path, expected] of screens) {
      await page.goto(path)
      await settle(page)
      await checkScreen(page, `visitante ${path}`, expected)
    }
  })

  test('011-07: at 360 px logo, Vacantes, Ingresar and Crear cuenta fit in one row, without a menu', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 740 })
    await page.goto('/')
    await settle(page)
    const banner = page.getByRole('banner')
    const items = [
      banner.getByRole('link', { name: 'Talenti, inicio' }),
      banner.getByRole('link', { name: 'Vacantes' }),
      banner.getByRole('link', { name: 'Ingresar' }),
      banner.getByRole('link', { name: 'Crear cuenta' }),
    ]
    const tops: number[] = []
    for (const item of items) {
      await expect(item).toBeVisible()
      const box = (await item.boundingBox())!
      tops.push(box.y + box.height / 2)
    }
    expect(Math.max(...tops) - Math.min(...tops)).toBeLessThan(4)
    await expect(banner.getByRole('button')).toHaveCount(0)
  })

  for (const path of ['/registro', '/ingresar']) {
    test(`011-07: on ${path} the header offers only the logo and Vacantes`, async ({ page }) => {
      await page.setViewportSize({ width: 360, height: 740 })
      await page.goto(path)
      await settle(page)
      const banner = page.getByRole('banner')
      await expect(banner.getByRole('link')).toHaveCount(2)
      await expect(banner.getByRole('link', { name: 'Vacantes' })).toBeVisible()
      await expect(banner.getByRole('link', { name: /Crear cuenta|Ingresar/ })).toHaveCount(0)
    })
  }

  test('011-08: the footer is one line from 768 px and at most two at 360 px; "Cookies" opens the preferences', async ({
    page,
  }) => {
    await page.goto('/oportunidades')
    await settle(page)
    const lineCount = () =>
      page.evaluate(() => {
        const footer = document.querySelector('footer') as HTMLElement
        const tops = [...footer.querySelectorAll('a, button, p')].map((el) =>
          Math.round(el.getBoundingClientRect().top + el.getBoundingClientRect().height / 2),
        )
        return new Set(tops.map((top) => Math.round(top / 8))).size
      })
    for (const width of [768, 1280]) {
      await page.setViewportSize({ width, height: 800 })
      expect(await lineCount(), `${width} px`).toBe(1)
    }
    await page.setViewportSize({ width: 360, height: 740 })
    expect(await lineCount()).toBeLessThanOrEqual(2)

    const footer = page.getByRole('contentinfo')
    await expect(footer).toContainText('Empresas y vacantes de ejemplo')
    await footer.getByRole('button', { name: 'Cookies' }).click()
    const dialog = page.getByRole('dialog', { name: 'Preferencias de cookies' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByRole('link', { name: 'Leer la Política de Cookies' })).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(footer.getByRole('button', { name: 'Cookies' })).toBeFocused()
  })

  test('011-08: the cookie notice is at most 20 % of the screen at 360 px, with Aceptar and Rechazar only', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 740 })
    await page.goto('/')
    await settle(page)
    const region = page.getByRole('region', { name: 'Aviso de cookies' })
    await expect(region).toBeVisible()
    const box = (await region.boundingBox())!
    expect(box.height).toBeLessThanOrEqual(740 * 0.2)
    await expect(region.getByRole('button')).toHaveText(['Aceptar', 'Rechazar'])
  })

  test('011-05: with "reduce motion" the header and the footer have no transitions', async ({
    page,
  }) => {
    await page.emulateMedia({ reducedMotion: 'reduce' })
    await page.goto('/')
    await settle(page)
    const durations = await page.evaluate(() =>
      [...document.querySelectorAll('header a, footer a, footer button')].map(
        (el) => parseFloat(getComputedStyle(el).transitionDuration) * 1000,
      ),
    )
    expect(durations.every((ms) => ms <= 0.02)).toBe(true)
  })

  test('011-05: the header, the footer and the notice are operated with the keyboard, with a visible focus', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 740 })
    await page.goto('/')
    await settle(page)
    const names: string[] = []
    for (let i = 0; i < 5; i++) {
      await page.keyboard.press('Tab')
      const info = await page.evaluate(() => {
        const el = document.activeElement as HTMLElement
        return {
          name: (el.getAttribute('aria-label') ?? el.textContent ?? '').trim(),
          outline: getComputedStyle(el).outlineStyle,
        }
      })
      names.push(info.name)
      expect(info.outline).toBe('solid')
    }
    expect(names).toEqual([
      'Saltar al contenido',
      'Talenti, inicio',
      'Vacantes',
      'Ingresar',
      'Crear cuenta',
    ])
  })
})

test.describe('Demo student (011-01 to 011-06)', () => {
  let page: Page

  test.beforeAll(async ({ browser }) => {
    page = await signInDemo(browser)
    // The notice is already chosen, so the walk sees the screens as a returning student.
    const notice = page.getByRole('region', { name: 'Aviso de cookies' })
    if (await notice.isVisible()) await notice.getByRole('button', { name: 'Rechazar' }).click()
  })

  test.afterAll(async () => {
    await page.context().close()
  })

  test('every screen of a student at 360, 768 and 1280 px', async () => {
    const vacancy = await firstVacancyPath(page)
    const screens: Array<[string, (0 | 1)?]> = [
      ['/'],
      ['/oportunidades', 0],
      [vacancy],
      ['/postulaciones'],
      ['/perfil'],
      ['/perfil/cv'],
    ]
    for (const [path, expected] of screens) {
      await page.goto(path)
      await settle(page)
      await checkScreen(page, `estudiante ${path}`, expected)
    }
  })

  test('011-06: under 860 px the tab bar has four tabs and marks the section, also on inner pages', async () => {
    await page.setViewportSize({ width: 360, height: 740 })
    const vacancy = await firstVacancyPath(page)
    const cases: Array<[string, string]> = [
      ['/', 'Inicio'],
      ['/oportunidades', 'Vacantes'],
      [vacancy, 'Vacantes'],
      ['/postulaciones', 'Postulaciones'],
      ['/perfil', 'Perfil'],
    ]
    for (const [path, current] of cases) {
      await page.goto(path)
      await settle(page)
      const nav = page.getByRole('navigation', { name: 'Principal' })
      await expect(nav.getByRole('link')).toHaveText([
        'Inicio',
        'Vacantes',
        'Postulaciones',
        'Perfil',
      ])
      await expect(nav.locator('[aria-current="page"]')).toHaveText([current])
      const box = (await nav.boundingBox())!
      expect(box.y + box.height).toBeGreaterThan(739) // fixed at the bottom
      for (const tab of await nav.getByRole('link').all()) {
        expect((await tab.boundingBox())!.height).toBeGreaterThanOrEqual(44)
      }
    }
    // "Cerrar sesión" is not in the header under 860 px: it is the last row of Mi perfil.
    await expect(
      page.getByRole('banner').getByRole('button', { name: 'Cerrar sesión' }),
    ).toBeHidden()
    await expect(
      page.getByRole('main').getByRole('button', { name: 'Cerrar sesión' }),
    ).toBeVisible()
  })

  test('011-06: the tab bar never covers the end of the page', async () => {
    for (const path of ['/', '/perfil', '/oportunidades']) {
      await page.setViewportSize({ width: 360, height: 740 })
      await page.goto(path)
      await settle(page)
      await page.evaluate(() => window.scrollTo(0, document.documentElement.scrollHeight))
      await page.waitForTimeout(200)
      const { footerBottom, barTop } = await page.evaluate(() => ({
        footerBottom: (document.querySelector('footer') as HTMLElement).getBoundingClientRect()
          .bottom,
        barTop: (document.querySelector('.tabbar') as HTMLElement).getBoundingClientRect().top,
      }))
      expect(footerBottom, path).toBeLessThanOrEqual(barTop + 1)
    }
  })

  test('011-06: from 860 px the sections are links in the header and "Cerrar sesión" is text', async () => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/perfil')
    await settle(page)
    const banner = page.getByRole('banner')
    const nav = banner.getByRole('navigation', { name: 'Principal' })
    await expect(nav.getByRole('link')).toHaveText([
      'Inicio',
      'Vacantes',
      'Postulaciones',
      'Perfil',
    ])
    const navBox = (await nav.boundingBox())!
    const bannerBox = (await banner.boundingBox())!
    expect(navBox.y + navBox.height).toBeLessThanOrEqual(bannerBox.y + bannerBox.height + 1)
    const logout = banner.getByRole('button', { name: 'Cerrar sesión' })
    await expect(logout).toBeVisible()
    await expect(logout).not.toHaveClass(/btn--primary/)
    await expect(
      page.getByRole('main').getByRole('button', { name: 'Cerrar sesión' }),
    ).toBeVisible()
  })
})
