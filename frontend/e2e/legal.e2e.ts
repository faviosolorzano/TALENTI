import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

/**
 * Spec 002 (legal framework and cookies) against the REAL system: the legal texts come from the
 * backend, the cookie is read from the browser. Only the "new version of the documents" flow
 * needs a student with an old version, which the real data cannot produce, so there the
 * answer of GET /auth/me is simulated with `page.route` (the rest is real).
 */

const WIDTHS = [360, 768, 1280] as const
const COOKIE = 'talenti_cookies'
const DOCUMENTS = [
  { path: '/terminos', key: 'terms' },
  { path: '/privacidad', key: 'privacy' },
  { path: '/cookies', key: 'cookies' },
] as const

interface Catalog {
  version: string
  publishedAt: string | null
  documents: { key: string; title: string; sections: { heading: string; body: string }[] }[]
}

async function seriousViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze()
  return results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.map((n) => n.target.join(' ')) }))
}

const horizontalOverflow = (page: Page) =>
  page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)

async function realCatalog(page: Page): Promise<Catalog> {
  const response = await page.request.get('/api/v1/legal/current')
  expect(response.ok()).toBe(true)
  return (await response.json()) as Catalog
}

/** The cookie as the browser stores it (attributes included), or `undefined`. */
async function storedCookie(page: Page) {
  return (await page.context().cookies()).find((cookie) => cookie.name === COOKIE)
}

const banner = (page: Page) => page.getByRole('region', { name: 'Aviso de cookies' })

/** Any cookie that decides "chose / did not choose", written as the app would. */
async function setCookie(page: Page, value: string) {
  const { hostname } = new URL(page.url() === 'about:blank' ? 'http://localhost' : page.url())
  await page.context().addCookies([
    {
      name: COOKIE,
      value: encodeURIComponent(value),
      domain: hostname,
      path: '/',
    },
  ])
}

// ---------------------------------------------------------------------------------------------
// Legal pages (002-01 to 002-07)
// ---------------------------------------------------------------------------------------------
test.describe('Legal pages with the real documents (002-01 to 002-05)', () => {
  test.beforeEach(async ({ page }) => {
    await setCookie(
      page,
      JSON.stringify({ v: 1, c: { necessary: true }, t: new Date().toISOString() }),
    )
  })

  for (const { path, key } of DOCUMENTS) {
    test(`002-01: ${path} shows its title as the only h1 and every section in the order received`, async ({
      page,
    }) => {
      const catalog = await realCatalog(page)
      const document = catalog.documents.find((candidate) => candidate.key === key)!
      await page.goto(path)

      await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)
      await expect(page.getByRole('heading', { level: 1 })).toHaveText(document.title)
      const expected = document.sections.filter((s) => s.heading).map((s) => s.heading)
      await expect(page.locator('main h2.legal-section__heading')).toHaveText(expected)
    })

    test(`002-02 and 002-05: ${path} shows the version, the publication date and the draft notice`, async ({
      page,
    }) => {
      const catalog = await realCatalog(page)
      await page.goto(path)

      if (catalog.publishedAt) {
        await expect(page.getByText(`Versión ${catalog.version} · Publicada el `)).toBeVisible()
      } else {
        await expect(
          page.getByText(`Versión ${catalog.version} · Fecha de publicación pendiente`),
        ).toBeVisible()
      }
      await expect(
        page.getByText('Borrador: pendiente de revisión legal y sujeto a cambios'),
      ).toBeVisible()
    })
  }

  test('002-05: the bracketed placeholders show exactly as written', async ({ page }) => {
    await page.goto('/privacidad')
    await expect(page.getByText('[titular del servicio]').first()).toBeVisible()
    await expect(page.getByText('[correo de contacto]').first()).toBeVisible()
  })

  test('002-03: tables and bold text of the real Markdown are drawn', async ({ page }) => {
    await page.goto('/cookies')
    const table = page.locator('main table').first()
    await expect(table).toBeVisible()
    await expect(table.getByRole('columnheader', { name: 'Cookie' })).toBeVisible()
    await expect(page.locator('main strong').first()).toBeVisible()
    await page.goto('/privacidad')
    await expect(page.locator('main ul li').first()).toBeVisible()
  })

  test('002-03: an internal link of the text navigates without reloading the page', async ({
    page,
  }) => {
    await page.goto('/privacidad')
    await page.evaluate(() => {
      ;(window as unknown as { __marker: string }).__marker = 'still here'
    })

    await page.locator('main').getByRole('link', { name: 'Política de Cookies' }).click()

    await expect(page).toHaveURL(/\/cookies$/)
    await expect(page.getByRole('heading', { level: 1 })).toHaveText(/Política de Cookies/)
    expect(await page.evaluate(() => (window as unknown as { __marker?: string }).__marker)).toBe(
      'still here',
    )
  })

  test('002-03: raw HTML and comments in a document are neither run nor shown', async ({
    page,
  }) => {
    const catalog = await realCatalog(page)
    catalog.documents[0] = {
      key: 'terms',
      title: 'Términos de prueba',
      sections: [
        {
          heading: '1. Prueba de seguridad',
          body: [
            'Texto visible <!-- comentario secreto --> y más texto.',
            '',
            '<script>window.__pwned = true</script>',
            '',
            '<img src="x" onerror="window.__pwned = true">',
            '',
            '<div id="html-crudo" onclick="window.__pwned = true">bloque html</div>',
            '',
            '[enlace malo](javascript:window.__pwned=true)',
          ].join('\n'),
        },
      ],
    }
    await page.route('**/api/v1/legal/current', (route) => route.fulfill({ json: catalog }))
    await page.goto('/terminos')

    await expect(page.getByRole('heading', { level: 1, name: 'Términos de prueba' })).toBeVisible()
    await expect(page.getByText('Texto visible')).toBeVisible()
    const main = page.locator('main')
    await expect(main.locator('script, img, #html-crudo, [onerror], [onclick]')).toHaveCount(0)
    await expect(main.locator('a[href^="javascript"]')).toHaveCount(0)
    await expect(main).not.toContainText('comentario secreto')
    await expect(main).not.toContainText('<script>')
    await expect(main).not.toContainText('bloque html')
    expect(
      await page.evaluate(() => (window as unknown as { __pwned?: boolean }).__pwned),
    ).toBeUndefined()
  })

  test('002-06: while loading it shows a skeleton announced as busy, then the text', async ({
    page,
  }) => {
    await page.route('**/api/v1/legal/current', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 800))
      await route.continue()
    })
    await page.goto('/privacidad')

    await expect(page.locator('[aria-busy="true"]')).toBeVisible()
    await expect(page.locator('.skeleton').first()).toBeVisible()
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(page.locator('main h2.legal-section__heading').first()).toBeVisible()
    await expect(page.locator('[aria-busy="true"]')).toHaveCount(0)
  })

  test('002-06: if the request fails it shows an alert and "Reintentar" loads the text without reloading', async ({
    page,
  }) => {
    await page.route('**/api/v1/legal/current', (route) => route.abort())
    await page.goto('/privacidad')
    const alert = page.getByRole('alert')
    await expect(alert).toBeVisible()
    await page.evaluate(() => {
      ;(window as unknown as { __marker: string }).__marker = 'still here'
    })

    await page.unroute('**/api/v1/legal/current')
    await alert.getByRole('button', { name: 'Reintentar' }).click()

    await expect(page.locator('main h2.legal-section__heading').first()).toBeVisible()
    await expect(page.getByRole('alert')).toHaveCount(0)
    expect(await page.evaluate(() => (window as unknown as { __marker?: string }).__marker)).toBe(
      'still here',
    )
  })

  test('002-06: a document missing from the answer is an error', async ({ page }) => {
    const catalog = await realCatalog(page)
    catalog.documents = catalog.documents.filter((document) => document.key !== 'privacy')
    await page.route('**/api/v1/legal/current', (route) => route.fulfill({ json: catalog }))
    await page.goto('/privacidad')
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Reintentar' })).toBeVisible()
  })

  test('002-01: a different answer (other number of sections) is shown without changing code', async ({
    page,
  }) => {
    const catalog = await realCatalog(page)
    catalog.documents[1] = {
      key: 'privacy',
      title: 'Otra política',
      sections: [
        { heading: 'Única sección', body: 'Solo hay una.' },
        { heading: 'Segunda sección', body: 'Y esta otra.' },
      ],
    }
    await page.route('**/api/v1/legal/current', (route) => route.fulfill({ json: catalog }))
    await page.goto('/privacidad')

    await expect(page.getByRole('heading', { level: 1, name: 'Otra política' })).toBeVisible()
    await expect(page.locator('main h2.legal-section__heading')).toHaveText([
      'Única sección',
      'Segunda sección',
    ])
  })
})

// ---------------------------------------------------------------------------------------------
// Index and focus (002-04), keyboard only
// ---------------------------------------------------------------------------------------------
test.describe('Index and focus (002-04)', () => {
  test.beforeEach(async ({ page }) => {
    await setCookie(
      page,
      JSON.stringify({ v: 1, c: { necessary: true }, t: new Date().toISOString() }),
    )
  })

  test('at 1280 px the index is a side column and a link moves focus to the heading', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/privacidad')
    const toc = page.getByRole('navigation', { name: 'Contenido' })
    await expect(toc).toBeVisible()
    await expect(page.getByRole('button', { name: 'Contenido' })).toBeHidden()

    const tocBox = (await toc.boundingBox())!
    const bodyBox = (await page.locator('.legal__sections').boundingBox())!
    expect(tocBox.x + tocBox.width).toBeLessThanOrEqual(bodyBox.x)

    await toc.getByRole('link', { name: '7. Cuánto tiempo conservamos tus datos' }).click()

    const heading = page.locator('#seccion-7')
    await expect(heading).toBeFocused()
    await expect(page).toHaveURL(/#seccion-7$/)
    // The page scrolls smoothly: wait until it arrives, just below the fixed header.
    await expect
      .poll(async () => (await heading.boundingBox())!.y, { timeout: 5000 })
      .toBeGreaterThanOrEqual(60)
    await expect.poll(async () => (await heading.boundingBox())!.y).toBeLessThan(300)
  })

  test('at 1280 px the index stays in view while reading (fixed column)', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/privacidad')
    const toc = page.getByRole('navigation', { name: 'Contenido' })
    await page.evaluate(() => window.scrollTo(0, 1500))
    await expect(toc).toBeInViewport()
  })

  for (const width of [360, 768] as const) {
    test(`at ${width} px the index is folded above the text under "Contenido"`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 900 })
      await page.goto('/privacidad')
      const toggle = page.getByRole('button', { name: 'Contenido' })
      await expect(toggle).toBeVisible()
      await expect(toggle).toHaveAttribute('aria-expanded', 'false')
      const list = page.getByRole('navigation', { name: 'Contenido' }).getByRole('list')
      await expect(list).toBeHidden()

      await toggle.click()
      await expect(toggle).toHaveAttribute('aria-expanded', 'true')
      await expect(list).toBeVisible()
      const toggleBox = (await toggle.boundingBox())!
      const firstSection = (await page.locator('#seccion-1').boundingBox())!
      expect(toggleBox.y).toBeLessThan(firstSection.y)

      await list.getByRole('link', { name: '3. Datos que no necesitamos' }).click()
      await expect(page.locator('#seccion-3')).toBeFocused()
      await expect(toggle).toHaveAttribute('aria-expanded', 'false')
      await expect
        .poll(async () => (await page.locator('#seccion-3').boundingBox())!.y)
        .toBeGreaterThanOrEqual(60)
    })
  }

  test('a URL with #seccion-N opens in that section once the text has loaded', async ({ page }) => {
    await page.goto('/privacidad#seccion-10')
    await expect(page.locator('#seccion-10')).toBeFocused()
    await expect(page.locator('#seccion-10')).toBeInViewport()
  })

  test('a #seccion-N that does not exist stays at the top', async ({ page }) => {
    await page.goto('/privacidad#seccion-99')
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(page.locator('main h2.legal-section__heading').first()).toBeVisible()
    expect(await page.evaluate(() => window.scrollY)).toBe(0)
  })

  test('only the keyboard: footer -> document -> index -> section', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/')
    await page.evaluate(() => document.fonts.ready)

    const tabUntil = async (matches: () => Promise<boolean>) => {
      for (let i = 0; i < 80; i++) {
        await page.keyboard.press('Tab')
        if (await matches()) return
      }
      throw new Error('The keyboard never reached the element')
    }
    const focusedIn = (selector: string, name: string) => async () =>
      page.evaluate(
        ([sel, text]) => {
          const el = document.activeElement
          return Boolean(el && el.closest(sel) && el.textContent?.trim() === text)
        },
        [selector, name] as const,
      )

    await tabUntil(focusedIn('footer', 'Política de Privacidad'))
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/\/privacidad$/)
    await expect(page.locator('main h2.legal-section__heading').first()).toBeVisible()

    await tabUntil(focusedIn('nav[aria-label="Contenido"]', '5. Con quién los compartimos'))
    await page.keyboard.press('Enter')

    await expect(page.locator('#seccion-5')).toBeFocused()
    await expect(page).toHaveURL(/#seccion-5$/)
  })
})

// ---------------------------------------------------------------------------------------------
// Widths, reading line and axe (002-07)
// ---------------------------------------------------------------------------------------------
test.describe('Widths 360, 768 and 1280 (002-07)', () => {
  test.beforeEach(async ({ page }) => {
    await setCookie(
      page,
      JSON.stringify({ v: 1, c: { necessary: true }, t: new Date().toISOString() }),
    )
  })

  for (const width of WIDTHS) {
    for (const path of ['/terminos', '/privacidad', '/cookies', '/transparencia']) {
      test(`at ${width} px ${path} does not scroll horizontally and has no serious accessibility violations`, async ({
        page,
      }) => {
        await page.setViewportSize({ width, height: 900 })
        await page.goto(path)
        await expect(page.locator('main h1')).toBeVisible()
        if (path !== '/transparencia') {
          await expect(page.locator('main h2.legal-section__heading').first()).toBeVisible()
        }
        await page.evaluate(() => document.fonts.ready)

        expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0)
        expect(await seriousViolations(page)).toEqual([])
      })
    }

    test(`at ${width} px the reading line is about 70 characters at most`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
      await page.goto('/terminos')
      await expect(page.locator('main h2.legal-section__heading').first()).toBeVisible()
      await page.evaluate(() => document.fonts.ready)

      const longest = await page.evaluate(() => {
        let best = 0
        for (const p of document.querySelectorAll('main .markdown p')) {
          const node = p.firstChild
          if (!node || node.nodeType !== Node.TEXT_NODE) continue
          const text = node.textContent ?? ''
          if (text.length < 200) continue
          const lines = new Map<number, number>()
          const range = document.createRange()
          for (let i = 0; i < text.length; i++) {
            range.setStart(node, i)
            range.setEnd(node, i + 1)
            const top = Math.round(range.getBoundingClientRect().top)
            lines.set(top, (lines.get(top) ?? 0) + 1)
          }
          // The last line of a paragraph is short; look at the full lines.
          const counts = [...lines.values()].slice(0, -1)
          best = Math.max(best, ...counts)
        }
        return best
      })
      expect(longest).toBeGreaterThan(20)
      expect(longest).toBeLessThanOrEqual(75) // about 70 characters, spaces included
      if (width === 1280) expect(longest).toBeGreaterThanOrEqual(60)
    })
  }

  test('002-07: a wide table scrolls inside its own box, reachable with the keyboard, not the page', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 900 })
    await page.goto('/cookies')
    const scroller = page.getByRole('group', { name: 'Tabla desplazable' }).first()
    await expect(scroller).toBeVisible()

    const sizes = await scroller.evaluate((el) => ({
      scrollWidth: el.scrollWidth,
      clientWidth: el.clientWidth,
    }))
    expect(sizes.scrollWidth).toBeGreaterThan(sizes.clientWidth)
    expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0)

    await scroller.focus()
    await expect(scroller).toBeFocused()
    await page.keyboard.press('ArrowRight')
    // The page scrolls smoothly: wait for the box to move instead of reading it at once.
    await expect.poll(() => scroller.evaluate((el) => el.scrollLeft)).toBeGreaterThan(0)
  })
})

// ---------------------------------------------------------------------------------------------
// Transparency page (002-10)
// ---------------------------------------------------------------------------------------------
test.describe('Page /transparencia (002-10, 002-05)', () => {
  test.beforeEach(async ({ page }) => {
    await setCookie(
      page,
      JSON.stringify({ v: 1, c: { necessary: true }, t: new Date().toISOString() }),
    )
  })

  test('explains the six topics in plain language, with the draft notice', async ({ page }) => {
    await page.goto('/transparencia')

    await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)
    await expect(page.locator('main h2')).toHaveText([
      'En pocas palabras',
      'Qué datos usamos',
      'Para qué los usamos',
      'Con quién los compartimos',
      'Cuánto tiempo los conservamos',
      'Cómo funciona DORA',
      'Tus derechos y tu control',
    ])
    await expect(
      page.getByText('Borrador: pendiente de revisión legal y sujeto a cambios'),
    ).toBeVisible()
    await expect(page.getByRole('region', { name: 'Cómo funciona DORA' })).toContainText(
      'inteligencia artificial',
    )
  })

  test('every link goes to a section of the Privacy Policy that exists, with focus on its heading', async ({
    page,
  }) => {
    await page.goto('/transparencia')
    const hrefs = await page
      .locator('main section a[href^="/privacidad#seccion-"]')
      .evaluateAll((links) => links.map((link) => link.getAttribute('href') as string))
    expect(hrefs.length).toBeGreaterThanOrEqual(8)

    for (const href of hrefs) {
      await page.goto('/transparencia')
      await page.locator(`main a[href="${href}"]`).first().click()
      const id = href.split('#')[1] as string
      await expect(page.locator(`#${id}`)).toBeFocused()
    }
  })
})

// ---------------------------------------------------------------------------------------------
// Footer on every screen (002-08)
// ---------------------------------------------------------------------------------------------
test.describe('Footer (002-08)', () => {
  test.beforeEach(async ({ page }) => {
    await setCookie(
      page,
      JSON.stringify({ v: 1, c: { necessary: true }, t: new Date().toISOString() }),
    )
  })

  for (const width of WIDTHS) {
    test(`at ${width} px every screen has the footer with the four links and the cookie button, all inside the window`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 800 })
      for (const path of [
        '/',
        '/oportunidades',
        '/registro',
        '/ingresar',
        '/panel',
        '/terminos',
        '/transparencia',
        '/esta-ruta-no-existe',
      ]) {
        await page.goto(path)
        const footer = page.getByRole('contentinfo')
        await expect(footer).toBeVisible()
        await expect(footer.getByRole('listitem')).toHaveText([
          'Términos y Condiciones',
          'Política de Privacidad',
          'Política de Cookies',
          'Transparencia',
          'Preferencias de cookies',
        ])
        for (const item of await footer.getByRole('listitem').all()) {
          const box = (await item.boundingBox())!
          expect(box.x).toBeGreaterThanOrEqual(0)
          expect(box.x + box.width).toBeLessThanOrEqual(width)
        }
        expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0)
      }
    })
  }

  test('the footer links and the button are reached with Tab and opened with Enter', async ({
    page,
  }) => {
    // A static public page: /panel is private now and would redirect (and move the focus).
    await page.goto('/recuperar-cuenta')
    const footer = page.getByRole('contentinfo')
    await footer.getByRole('link', { name: 'Términos y Condiciones' }).focus()
    await page.keyboard.press('Tab')
    await expect(footer.getByRole('link', { name: 'Política de Privacidad' })).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(footer.getByRole('link', { name: 'Política de Cookies' })).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(footer.getByRole('link', { name: 'Transparencia' })).toBeFocused()
    await page.keyboard.press('Tab')
    await expect(footer.getByRole('button', { name: 'Preferencias de cookies' })).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('dialog', { name: 'Preferencias de cookies' })).toBeVisible()
  })

  test('the footer stays when the legal texts fail to load (it does not depend on the API)', async ({
    page,
  }) => {
    await page.route('**/api/v1/legal/current', (route) => route.abort())
    await page.goto('/terminos')
    await expect(page.getByRole('alert')).toBeVisible()
    await expect(page.getByRole('contentinfo')).toBeVisible()
  })
})

// ---------------------------------------------------------------------------------------------
// Cookie notice and panel (002-11 to 002-17)
// ---------------------------------------------------------------------------------------------
test.describe('Cookie notice (002-11 to 002-16)', () => {
  test('002-11: a first visit shows the notice and writes no cookie before choosing', async ({
    page,
  }) => {
    await page.goto('/')
    const region = banner(page)
    await expect(region).toBeVisible()
    await expect(region).toContainText('Solo usamos cookies necesarias')
    await expect(region.getByRole('link', { name: 'Política de Cookies' })).toHaveAttribute(
      'href',
      '/cookies',
    )
    await expect(region.getByRole('button')).toHaveText(['Aceptar', 'Rechazar', 'Configurar'])
    expect(await storedCookie(page)).toBeUndefined()
  })

  for (const choice of ['Rechazar', 'Aceptar'] as const) {
    test(`002-13: "${choice}" closes the notice and saves the cookie as specified`, async ({
      page,
    }) => {
      await page.goto('/')
      const before = Date.now() / 1000
      await banner(page).getByRole('button', { name: choice }).click()

      await expect(banner(page)).toHaveCount(0)
      await expect(
        page.getByRole('status').filter({ hasText: 'Preferencias guardadas' }),
      ).toBeAttached()
      await expect(page.locator('main')).toBeFocused()

      const cookie = (await storedCookie(page))!
      expect(cookie).toBeDefined()
      expect(cookie.path).toBe('/')
      expect(cookie.sameSite).toBe('Lax')
      expect(cookie.httpOnly).toBe(false)
      // Secure only over HTTPS (production); this run is plain http://localhost.
      expect(cookie.secure).toBe(false)
      expect(cookie.expires).toBeGreaterThan(before + 31_536_000 - 120)
      expect(cookie.expires).toBeLessThan(before + 31_536_000 + 120)
      const value = JSON.parse(decodeURIComponent(cookie.value)) as Record<string, unknown>
      expect(Object.keys(value).sort()).toEqual(['c', 't', 'v'])
      expect(value.v).toBe(1)
      expect(value.c).toEqual({ necessary: true })
      expect(Number.isNaN(Date.parse(value.t as string))).toBe(false)
    })
  }

  test('002-13: after choosing, the notice does not come back on reload or on other screens', async ({
    page,
  }) => {
    await page.goto('/')
    await banner(page).getByRole('button', { name: 'Rechazar' }).click()

    await page.reload()
    await expect(page.getByRole('contentinfo')).toBeVisible()
    await expect(banner(page)).toHaveCount(0)
    for (const path of ['/oportunidades', '/ingresar', '/privacidad']) {
      await page.goto(path)
      await expect(page.getByRole('contentinfo')).toBeVisible()
      await expect(banner(page)).toHaveCount(0)
    }
  })

  test('002-13: the choice made in one tab shows in another one after reloading', async ({
    page,
  }) => {
    await page.goto('/')
    const second = await page.context().newPage()
    await second.goto('/')
    await expect(banner(second)).toBeVisible()
    await page.bringToFront() // a background tab is throttled and clicks wait for ever
    await banner(page).getByRole('button', { name: 'Aceptar' }).click()

    await second.bringToFront()
    await second.reload()
    await expect(second.getByRole('contentinfo')).toBeVisible()
    await expect(banner(second)).toHaveCount(0)
  })

  test('002-14: "Preferencias de cookies" in the footer changes the choice and renews the 12 months', async ({
    page,
  }) => {
    await page.goto('/')
    await banner(page).getByRole('button', { name: 'Rechazar' }).click()
    const first = (await storedCookie(page))!
    const firstValue = JSON.parse(decodeURIComponent(first.value)) as { t: string }
    await page.waitForTimeout(1100)

    const opener = page
      .getByRole('contentinfo')
      .getByRole('button', { name: 'Preferencias de cookies' })
    await opener.click()
    const dialog = page.getByRole('dialog', { name: 'Preferencias de cookies' })
    await expect(dialog).toBeVisible()
    await expect(dialog.getByText('Necesarias', { exact: true })).toBeVisible()
    await expect(dialog.getByText('Siempre activas')).toBeVisible()
    await expect(dialog.getByRole('checkbox')).toHaveCount(0)
    await dialog.getByRole('button', { name: 'Guardar preferencias' }).click()

    await expect(dialog).toHaveCount(0)
    await expect(opener).toBeFocused()
    const second = (await storedCookie(page))!
    const secondValue = JSON.parse(decodeURIComponent(second.value)) as { t: string }
    expect(Date.parse(secondValue.t)).toBeGreaterThan(Date.parse(firstValue.t))
    expect(second.expires).toBeGreaterThan(first.expires)
  })

  test('002-14: "Configurar" opens the panel; focus enters and stays inside; Esc closes it and gives focus back', async ({
    page,
  }) => {
    await page.goto('/')
    const configure = banner(page).getByRole('button', { name: 'Configurar' })
    await configure.click()
    const dialog = page.getByRole('dialog', { name: 'Preferencias de cookies' })
    await expect(dialog).toBeVisible()
    await expect(dialog).toBeFocused()

    for (let i = 0; i < 8; i++) {
      await page.keyboard.press('Tab')
      expect(
        await page.evaluate(() => document.activeElement?.closest('[role="dialog"]') !== null),
      ).toBe(true)
    }
    for (let i = 0; i < 8; i++) {
      await page.keyboard.press('Shift+Tab')
      expect(
        await page.evaluate(() => document.activeElement?.closest('[role="dialog"]') !== null),
      ).toBe(true)
    }

    await page.keyboard.press('Escape')
    await expect(dialog).toHaveCount(0)
    await expect(configure).toBeFocused()
    await expect(banner(page)).toBeVisible()
    expect(await storedCookie(page)).toBeUndefined()
  })

  test('002-14: the page behind the panel cannot be reached while it is open', async ({ page }) => {
    await page.goto('/')
    await banner(page).getByRole('button', { name: 'Configurar' }).click()
    await expect(page.locator('#root')).toHaveAttribute('inert', '')
    await page.keyboard.press('Escape')
    await expect(page.locator('#root')).not.toHaveAttribute('inert', '')
  })

  test('002-14: "Guardar preferencias" from the notice saves the cookie and closes both', async ({
    page,
  }) => {
    await page.goto('/')
    await banner(page).getByRole('button', { name: 'Configurar' }).click()
    await page.getByRole('button', { name: 'Guardar preferencias' }).click()

    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(banner(page)).toHaveCount(0)
    expect(await storedCookie(page)).toBeDefined()
    await expect(page.locator('main')).toBeFocused()
  })

  test('002-15: the notice is not modal: no layer, the page and the footer stay usable', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 700 })
    await page.goto('/oportunidades')
    await expect(banner(page)).toBeVisible()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.locator('.cookie-panel__scrim')).toHaveCount(0)
    // Nothing covers the page: the point in the middle of the window belongs to the content.
    expect(
      await page.evaluate(() => document.elementFromPoint(640, 200)?.closest('.cookie-banner')),
    ).toBeNull()
    // Focus is not taken: it is still on the document at the start.
    expect(await page.evaluate(() => document.activeElement === document.body)).toBe(true)

    // The page scrolls and the footer can be reached and clicked while the notice is shown.
    await page.getByRole('contentinfo').scrollIntoViewIfNeeded()
    const link = page.getByRole('contentinfo').getByRole('link', { name: 'Transparencia' })
    await expect(link).toBeInViewport()
    expect(
      await link.evaluate((el) => {
        const rect = el.getBoundingClientRect()
        return document.elementFromPoint(rect.x + rect.width / 2, rect.y + rect.height / 2) === el
      }),
    ).toBe(true)
    await link.click()
    await expect(page).toHaveURL(/\/transparencia$/)
    await expect(banner(page)).toBeVisible() // moving around does not count as choosing
  })

  test('002-15: it is a region named "Aviso de cookies", right before the footer in the tab order', async ({
    page,
  }) => {
    await page.goto('/ingresar')
    const region = banner(page)
    await expect(region).toBeVisible()
    expect(
      await page.evaluate(() => {
        const notice = document.querySelector('.cookie-banner')
        return notice?.nextElementSibling?.tagName
      }),
    ).toBe('FOOTER')

    await region.getByRole('button', { name: 'Configurar' }).focus()
    await page.keyboard.press('Tab')
    await expect(
      page.getByRole('contentinfo').getByRole('link', { name: 'Términos y Condiciones' }),
    ).toBeFocused()
  })

  test('002-15: at 360 px the notice takes at most 40 % of the window height', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 640 })
    await page.goto('/')
    await expect(banner(page)).toBeVisible()
    await page.evaluate(() => document.fonts.ready)

    const box = (await banner(page).boundingBox())!
    expect(box.height).toBeLessThanOrEqual(640 * 0.4)
    // "Aceptar" and "Rechazar" are equal in size.
    const accept = (await banner(page).getByRole('button', { name: 'Aceptar' }).boundingBox())!
    const reject = (await banner(page).getByRole('button', { name: 'Rechazar' }).boundingBox())!
    expect(accept.width).toBeCloseTo(reject.width, 0)
    expect(accept.height).toBeCloseTo(reject.height, 0)
    expect(accept.height).toBeGreaterThanOrEqual(44)
  })

  test('002-12: "Aceptar" and "Rechazar" look exactly the same', async ({ page }) => {
    await page.goto('/')
    const style = (name: string) =>
      banner(page)
        .getByRole('button', { name })
        .evaluate((el) => {
          const css = getComputedStyle(el)
          return [
            css.backgroundColor,
            css.color,
            css.borderTopColor,
            css.borderTopWidth,
            css.fontSize,
            css.fontWeight,
            css.paddingTop,
            css.paddingLeft,
            css.borderRadius,
            css.minHeight,
          ]
        })
    expect(await style('Aceptar')).toEqual(await style('Rechazar'))
    await expect(banner(page).getByRole('checkbox')).toHaveCount(0)
  })

  test('002-16: an unreadable, expired or older-version cookie brings the notice back; a current one does not', async ({
    page,
  }) => {
    await page.goto('/')
    const now = Date.now()
    const cases: [string, string, boolean][] = [
      ['unreadable', 'esto no es json', true],
      ['not an object', '42', true],
      [
        'without version',
        JSON.stringify({ c: { necessary: true }, t: new Date(now).toISOString() }),
        true,
      ],
      [
        'older version',
        JSON.stringify({ v: 0, c: { necessary: true }, t: new Date(now).toISOString() }),
        true,
      ],
      [
        'older than 12 months',
        JSON.stringify({
          v: 1,
          c: { necessary: true },
          t: new Date(now - 400 * 86_400_000).toISOString(),
        }),
        true,
      ],
      [
        'current',
        JSON.stringify({ v: 1, c: { necessary: true }, t: new Date(now).toISOString() }),
        false,
      ],
      [
        'current with an unknown category',
        JSON.stringify({
          v: 1,
          c: { necessary: true, marketing: true },
          t: new Date(now).toISOString(),
        }),
        false,
      ],
    ]
    for (const [name, value, shows] of cases) {
      await page.context().clearCookies({ name: COOKIE })
      await setCookie(page, value)
      await page.goto('/')
      await expect(page.getByRole('contentinfo'), name).toBeVisible()
      await expect(banner(page), name).toHaveCount(shows ? 1 : 0)
    }
  })

  test('edge case: if the browser blocks cookies the app works and the notice comes back on every load', async ({
    page,
  }) => {
    await page.addInitScript(() => {
      Object.defineProperty(document, 'cookie', { get: () => '', set: () => {} })
    })
    await page.goto('/')
    await banner(page).getByRole('button', { name: 'Rechazar' }).click()
    await expect(banner(page)).toHaveCount(0)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()

    await page.reload()
    await expect(banner(page)).toBeVisible()
  })

  for (const width of WIDTHS) {
    test(`at ${width} px the notice and the panel do not scroll the page sideways and have no serious violations`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 800 })
      await page.goto('/')
      await expect(banner(page)).toBeVisible()
      await page.evaluate(() => document.fonts.ready)
      await page.waitForTimeout(1300)
      expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0)
      expect(await seriousViolations(page)).toEqual([])

      await banner(page).getByRole('button', { name: 'Configurar' }).click()
      await expect(page.getByRole('dialog')).toBeVisible()
      expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0)
      expect(await seriousViolations(page)).toEqual([])
    })
  }
})

// ---------------------------------------------------------------------------------------------
// New version of the documents (002-18, 002-19)
// ---------------------------------------------------------------------------------------------
test.describe('Page /aceptar-documentos (002-18, 002-19)', () => {
  test.beforeEach(async ({ page }) => {
    await setCookie(
      page,
      JSON.stringify({ v: 1, c: { necessary: true }, t: new Date().toISOString() }),
    )
  })

  /**
   * The student has an old version: only the answer of GET /auth/me is simulated. Like the
   * server, once a POST /legal/accept answers 204 nothing is pending any more.
   */
  async function pendingStudent(page: Page, pending = true) {
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
    await page.route('**/api/v1/auth/me', (route) =>
      route.fulfill({
        json: {
          id: '00000000-0000-4000-8000-000000000001',
          email: 'ana@example.org',
          role: 'STUDENT',
          legalAcceptancePending: pending && !accepted,
        },
      }),
    )
  }

  test('002-18: it says the documents changed, with version, date, links to the texts, an unticked box and both buttons', async ({
    page,
  }) => {
    await pendingStudent(page)
    await page.goto('/aceptar-documentos?returnTo=%2Foportunidades')

    await expect(
      page.getByRole('heading', { level: 1, name: 'Actualizamos nuestros documentos' }),
    ).toBeVisible()
    await expect(page.getByText(/Versión v1 · Publicada el /)).toBeVisible()
    const terms = page.locator('main').getByRole('link', { name: /Términos y Condiciones/ })
    const privacy = page.locator('main').getByRole('link', { name: /Política de Privacidad/ })
    await expect(terms).toHaveAttribute('href', '/terminos')
    await expect(terms).toHaveAttribute('target', '_blank')
    await expect(privacy).toHaveAttribute('href', '/privacidad')
    await expect(
      page.getByRole('checkbox', { name: 'He leído y acepto los documentos nuevos.' }),
    ).not.toBeChecked()
    await expect(page.getByRole('button', { name: 'Aceptar y continuar' })).toBeEnabled()
    await expect(
      page.getByRole('main').getByRole('button', { name: 'Cerrar sesión' }),
    ).toBeEnabled()
  })

  test('002-19: without the box it does not continue and says why; with it, it accepts and goes back to returnTo', async ({
    page,
  }) => {
    await pendingStudent(page)
    let accepted: { body: unknown; token: string | undefined } | null = null
    await page.route('**/api/v1/legal/accept', (route) => {
      accepted = {
        body: route.request().postDataJSON(),
        token: route.request().headers()['x-xsrf-token'],
      }
      return route.fulfill({ status: 204 })
    })
    await page.goto('/aceptar-documentos?returnTo=%2Foportunidades')
    const catalog = await realCatalog(page)

    await page.getByRole('button', { name: 'Aceptar y continuar' }).click()
    await expect(page.getByRole('alert')).toContainText('necesitas aceptar los documentos nuevos')
    expect(accepted).toBeNull()
    await expect(page).toHaveURL(/\/aceptar-documentos/)

    await page.getByRole('checkbox').check()
    await page.getByRole('button', { name: 'Aceptar y continuar' }).click()

    await expect(page).toHaveURL(/\/oportunidades$/)
    expect(accepted!.body).toEqual({ legalAccepted: true, legalVersion: catalog.version })
    // The CSRF token read from the cookie travels in the header.
    expect(accepted!.token).toBeTruthy()
  })

  test('002-19: a returnTo that leaves the site is ignored', async ({ page }) => {
    await pendingStudent(page)
    await page.route('**/api/v1/legal/accept', (route) => route.fulfill({ status: 204 }))
    await page.goto('/aceptar-documentos?returnTo=%2F%2Fevil.example%2Frobo')

    await page.getByRole('checkbox').check()
    await page.getByRole('button', { name: 'Aceptar y continuar' }).click()

    await expect(page).toHaveURL(/\/$/)
    expect(new URL(page.url()).hostname).toBe('localhost')
  })

  test('002-19: with the real backend, accepting stores the consent (204, CSRF accepted)', async ({
    page,
  }) => {
    const api = page.context().request
    await api.get('/api/v1/auth/csrf')
    const csrf = (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')
    expect(csrf, 'the backend sets the CSRF cookie').toBeDefined()
    const catalog = await realCatalog(page)
    const email = `e2e-legal-${Date.now()}@example.org`
    const registered = await api.post('/api/v1/auth/register', {
      headers: { 'X-XSRF-TOKEN': csrf!.value },
      data: {
        email,
        password: 'Clave-inventada-2026-Zx',
        legalAccepted: true,
        legalVersion: catalog.version,
      },
    })
    expect(registered.status()).toBe(201)

    // The real /auth/me says nothing is pending for a student who just registered.
    const me = await api.get('/api/v1/auth/me')
    expect(((await me.json()) as { legalAcceptancePending: boolean }).legalAcceptancePending).toBe(
      false,
    )
    await page.goto('/aceptar-documentos?returnTo=%2Foportunidades')
    await expect(page).toHaveURL(/\/oportunidades$/) // up to date: the screen shows nothing

    // Force the screen and accept for real: POST /legal/accept answers 204.
    await pendingStudent(page)
    await page.goto('/aceptar-documentos?returnTo=%2Fpanel')
    await page.getByRole('checkbox').check()
    const response = page.waitForResponse(
      (r) => r.url().endsWith('/api/v1/legal/accept') && r.request().method() === 'POST',
    )
    await page.getByRole('button', { name: 'Aceptar y continuar' }).click()
    expect((await response).status()).toBe(204)
    await expect(page).toHaveURL(/\/$/)

    // Sign out for real.
    const token = (await page.context().cookies()).find((cookie) => cookie.name === 'XSRF-TOKEN')!
    const out = await api.post('/api/v1/auth/logout', { headers: { 'X-XSRF-TOKEN': token.value } })
    expect(out.status()).toBe(204)
  })

  test('edge case: an outdated version (409) loads the current text again and asks again', async ({
    page,
  }) => {
    await pendingStudent(page)
    const real = await realCatalog(page)
    let loads = 0
    await page.route('**/api/v1/legal/current', (route) => {
      loads += 1
      return route.fulfill({ json: { ...real, version: loads === 1 ? 'v1' : 'v2' } })
    })
    await page.route('**/api/v1/legal/accept', (route) =>
      route.fulfill({ status: 409, json: { title: 'outdated' } }),
    )
    await page.goto('/aceptar-documentos')
    await expect(page.getByText(/Versión v1 · /)).toBeVisible()

    await page.getByRole('checkbox').check()
    await page.getByRole('button', { name: 'Aceptar y continuar' }).click()

    await expect(page.getByText(/Versión v2 · /)).toBeVisible()
    await expect(page.getByRole('alert')).toContainText('Los documentos cambiaron')
    await expect(page.getByRole('checkbox')).not.toBeChecked()
    await expect(page).toHaveURL(/\/aceptar-documentos/)
  })

  test('edge case: 422 explains that the documents must be accepted', async ({ page }) => {
    await pendingStudent(page)
    await page.route('**/api/v1/legal/accept', (route) =>
      route.fulfill({ status: 422, json: { title: 'invalid' } }),
    )
    await page.goto('/aceptar-documentos')
    await page.getByRole('checkbox').check()
    await page.getByRole('button', { name: 'Aceptar y continuar' }).click()
    await expect(page.getByRole('alert')).toContainText('necesitas aceptar los documentos nuevos')
  })

  test('T8: "Cerrar sesión" is always there and leaves to the sign-in page saying so', async ({
    page,
  }) => {
    await pendingStudent(page)
    let loggedOut = false
    await page.route('**/api/v1/auth/logout', (route) => {
      loggedOut = true
      return route.fulfill({ status: 204 })
    })
    await page.goto('/aceptar-documentos')
    await page.getByRole('main').getByRole('button', { name: 'Cerrar sesión' }).click()
    await expect(page).toHaveURL(/\/ingresar$/)
    await expect(page.getByText('Cerraste sesión.')).toBeVisible()
    expect(loggedOut).toBe(true)
  })

  test('001-14: without a session (the real backend answers 401) it goes to the sign-in page and remembers where to come back', async ({
    page,
  }) => {
    await page.goto('/aceptar-documentos?returnTo=%2Foportunidades')
    await expect(page).toHaveURL(/\/ingresar\?returnTo=%2Foportunidades$/)
  })

  test('the documents stay readable while the acceptance is pending', async ({ page }) => {
    await pendingStudent(page)
    await page.goto('/aceptar-documentos')
    await page.goto('/terminos')
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  })

  for (const width of WIDTHS) {
    test(`at ${width} px the screen does not scroll sideways and has no serious violations`, async ({
      page,
    }) => {
      await pendingStudent(page)
      await page.setViewportSize({ width, height: 800 })
      await page.goto('/aceptar-documentos')
      await expect(page.getByRole('checkbox')).toBeVisible()
      await page.evaluate(() => document.fonts.ready)

      expect(await horizontalOverflow(page)).toBeLessThanOrEqual(0)
      expect(await seriousViolations(page)).toEqual([])
    })
  }
})
