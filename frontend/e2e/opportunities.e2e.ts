import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

/**
 * Spec 004, criteria 004-15 to 004-25, against the real stack (nginx + backend + PostgreSQL
 * with the demo data of 12 open vacancies and 8 companies).
 */

const WIDTHS = [360, 768, 1280] as const

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

const resultsTitle = (page: Page) => page.locator('#results-title')

/**
 * Waits for the list to finish loading and returns how many vacancies the title announces. While
 * a new answer arrives the old list stays on screen (aria-busy), so it waits for that to end.
 */
async function loadedCount(page: Page): Promise<number> {
  await expect(page.locator('section[aria-labelledby="results-title"]')).toHaveAttribute(
    'aria-busy',
    'false',
  )
  await expect(resultsTitle(page)).toHaveText(/^\d+ vacantes?$/)
  return Number.parseInt((await resultsTitle(page).textContent()) ?? '', 10)
}

async function firstVacancyId(page: Page): Promise<string> {
  const response = await page.request.get('/api/v1/vacancies?size=1')
  const body = (await response.json()) as { items: Array<{ id: string }> }
  return (body.items[0] as { id: string }).id
}

test.describe('Home page (004-15, 004-16)', () => {
  test('shows the featured vacancies and the real counter right after the hero, before "Cómo funciona"', async ({
    page,
  }) => {
    await page.goto('/')
    const featured = page.getByRole('region', { name: /Vacantes destacadas/ })
    await expect(featured.getByRole('article')).toHaveCount(4)
    await expect(featured.getByRole('heading', { level: 2 })).toContainText('12 vacantes abiertas')
    await expect(featured.getByText('Datos de ejemplo')).toBeVisible()
    await expect(page.getByRole('heading', { level: 1 })).toHaveCount(1)

    const order = await page.evaluate(() => {
      const top = (selector: string) =>
        document.querySelector(selector)?.getBoundingClientRect().top ?? -1
      return {
        card: top('#compatibilidad-card'),
        featured: top('.featured'),
        companies: top('.companies'),
        how: top('#como-funciona'),
        dora: top('#dora'),
      }
    })
    expect(order.card).toBeLessThan(order.featured)
    expect(order.featured).toBeLessThan(order.companies)
    expect(order.companies).toBeLessThan(order.how)
    expect(order.how).toBeLessThan(order.dora)
  })

  test('the strip of companies leads to the list searched by the company name', async ({
    page,
  }) => {
    await page.goto('/')
    const strip = page.getByRole('region', { name: 'Empresas que buscan talento' })
    await expect(strip.getByRole('link')).toHaveCount(4)
    await strip.getByRole('link').first().click()
    await expect(page).toHaveURL(/\/oportunidades\?q=/)
    const count = await loadedCount(page)
    expect(count).toBeGreaterThanOrEqual(1)
    await expect(page.getByRole('textbox', { name: '¿Qué sabes hacer?' })).not.toHaveValue('')
  })

  test('loads no image for the companies (no third party, no logo)', async ({ page }) => {
    const images: string[] = []
    page.on('request', (request) => {
      if (request.resourceType() === 'image') images.push(request.url())
    })
    await page.goto('/')
    await expect(page.getByRole('region', { name: 'Empresas que buscan talento' })).toBeVisible()
    expect(images.filter((url) => !new URL(url).pathname.startsWith('/brand/'))).toEqual([])
  })
})

test.describe('Main journey (004-17 to 004-22)', () => {
  test('home, Oportunidades, search "JS", filter, paginate, detail and "Postular" with returnTo', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/')

    await page
      .getByRole('navigation', { name: 'Navegación principal' })
      .getByRole('link', { name: 'Oportunidades' })
      .click()
    await expect(page.getByRole('heading', { level: 1, name: 'Oportunidades' })).toBeVisible()
    expect(await loadedCount(page)).toBe(12)
    await expect(page.getByText('Mostrando 1–9 de 12')).toBeVisible()

    // Search by technology with a synonym.
    await page.getByRole('textbox', { name: '¿Qué sabes hacer?' }).fill('JS')
    await page.getByRole('button', { name: 'Buscar' }).click()
    await expect(page).toHaveURL(/\?q=JS$/)
    const withJs = await loadedCount(page)
    expect(withJs).toBeGreaterThanOrEqual(2)
    expect(withJs).toBeLessThan(12)

    // Filter by modality: applies as soon as it is checked.
    const hybrid = page.getByRole('checkbox', { name: /^Híbrido/ })
    await hybrid.click()
    await expect(hybrid).toBeChecked()
    await expect(page).toHaveURL(/q=JS&modalidad=hibrido/)
    expect(await loadedCount(page)).toBeLessThan(withJs + 1)

    // Back to everything and paginate.
    await page.getByRole('link', { name: 'Oportunidades' }).first().click()
    await page.goto('/oportunidades')
    expect(await loadedCount(page)).toBe(12)
    await page.getByRole('link', { name: 'Página 2' }).click()
    await expect(page).toHaveURL(/pagina=2/)
    await expect(resultsTitle(page)).toBeFocused()
    await expect(page.getByText('Mostrando 10–12 de 12')).toBeVisible()
    await expect(page.getByRole('article')).toHaveCount(3)
    await expect(
      page.getByRole('navigation', { name: 'Paginación' }).locator('[aria-current="page"]'),
    ).toHaveText('2')

    // Open a vacancy: the whole card is the link.
    const card = page.getByRole('article').first()
    const title = (await card.getByRole('link').textContent()) ?? ''
    await card.click({ position: { x: 10, y: 10 } })
    await expect(page).toHaveURL(/\/oportunidades\/[0-9a-f-]{36}$/)
    await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible()
    await expect(page.getByRole('heading', { level: 3, name: 'Obligatorios' })).toBeVisible()
    await expect(page.getByRole('heading', { level: 3, name: 'Deseables' })).toBeVisible()
    await expect(page.getByText('La decisión final es de la empresa')).toBeVisible()
    await expect(page.getByText('Inicia sesión para ver tu compatibilidad')).toBeVisible()
    await expect(page.getByText(/\d\s?%/)).toHaveCount(0)

    // "Volver a los resultados" keeps the page of the list.
    await page.getByRole('link', { name: /Volver a los resultados/ }).click()
    await expect(page).toHaveURL(/\/oportunidades\?pagina=2$/)
    await page.goBack()

    // "Postular" leads to /ingresar and keeps the vacancy.
    const id = new URL(page.url()).pathname.split('/').pop() as string
    await page.getByRole('link', { name: 'Postular', exact: true }).click()
    await expect(page).toHaveURL(`/ingresar?returnTo=%2Foportunidades%2F${id}`)
  })

  test('searching "programacion" without the accent works and a shared link reproduces the results', async ({
    page,
  }) => {
    await page.goto('/oportunidades?q=programacion')
    await expect(resultsTitle(page)).toHaveText(/^\d+ vacantes?$/)
    // Nothing breaks: either results or the useful empty message.
    const articles = await page.getByRole('article').count()
    if (articles === 0) {
      await expect(page.getByText('No encontramos vacantes para «programacion»')).toBeVisible()
    }

    await page.goto('/oportunidades?q=react&modalidad=remoto&orden=empresa')
    const count = await loadedCount(page)
    expect(count).toBeGreaterThanOrEqual(1)
    await expect(page.getByRole('textbox', { name: '¿Qué sabes hacer?' })).toHaveValue('react')
    await expect(page.getByRole('checkbox', { name: /^Remoto/ })).toBeChecked()
    await expect(page.getByRole('combobox', { name: 'Ordenar por' })).toHaveValue('company')
  })

  test('an invalid address is corrected and a page beyond the last leads to the last', async ({
    page,
  }) => {
    await page.goto('/oportunidades?modalidad=volando&pagina=abc&orden=raro')
    await loadedCount(page)
    await expect(page).toHaveURL(/\/oportunidades$/)

    await page.goto('/oportunidades?pagina=99')
    await expect(page).toHaveURL(/\/oportunidades\?pagina=2$/)
    expect(await loadedCount(page)).toBe(12)
  })

  test('back and forward walk through the searches', async ({ page }) => {
    await page.goto('/oportunidades')
    await loadedCount(page)
    const remoteBox = page.getByRole('checkbox', { name: /^Remoto/ })
    await remoteBox.click()
    await expect(remoteBox).toBeChecked()
    await expect(page).toHaveURL(/modalidad=remoto/)
    const remote = await loadedCount(page)
    expect(remote).toBeLessThan(12)
    await page.goBack()
    await expect(page).toHaveURL(/\/oportunidades$/)
    expect(await loadedCount(page)).toBe(12)
    await page.goForward()
    expect(await loadedCount(page)).toBe(remote)
  })

  test('empty filters without text offer to clear them and ask for no recent vacancies', async ({
    page,
  }) => {
    const recentRequests: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('size=3')) recentRequests.push(request.url())
    })
    await page.goto('/oportunidades?modalidad=presencial&tecnologia=laravel')
    await expect(resultsTitle(page)).toHaveText('0 vacantes')
    await expect(page.getByText('No encontramos vacantes con estos filtros')).toBeVisible()
    await expect(page.getByText('Vacantes recientes que pueden interesarte')).toHaveCount(0)
    await page.getByRole('button', { name: 'Limpiar filtros' }).last().click()
    expect(await loadedCount(page)).toBe(12)
    await expect(page).toHaveURL(/\/oportunidades$/)
    expect(recentRequests).toEqual([])
  })

  test('a missing vacancy says it does not exist and links back to the list', async ({ page }) => {
    await page.goto('/oportunidades/00000000-0000-4000-8000-000000000000')
    await expect(
      page.getByRole('heading', {
        level: 1,
        name: 'Esta vacante no existe o ya no está disponible',
      }),
    ).toBeVisible()
    await page.getByRole('link', { name: 'Ver todas las oportunidades' }).click()
    await expect(page).toHaveURL(/\/oportunidades$/)
  })

  test('the API error shows an alert with "Reintentar" and retrying recovers', async ({ page }) => {
    let fail = true
    await page.route('**/api/v1/vacancies?**', async (route) => {
      if (fail) await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' })
      else await route.continue()
    })
    await page.route('**/api/v1/vacancies', async (route) => {
      if (fail) await route.fulfill({ status: 500, contentType: 'application/json', body: '{}' })
      else await route.continue()
    })
    await page.goto('/oportunidades')
    const alert = page.getByRole('alert')
    await expect(alert).toContainText('No pudimos cargar las vacantes')
    fail = false
    await alert.getByRole('button', { name: 'Reintentar' }).click()
    expect(await loadedCount(page)).toBe(12)
  })

  test('the searched text is not written to the browser storage', async ({ page }) => {
    await page.goto('/oportunidades')
    await loadedCount(page)
    await page.getByRole('textbox', { name: '¿Qué sabes hacer?' }).fill('textoultrasecreto')
    await page.keyboard.press('Enter')
    await expect(resultsTitle(page)).toHaveText('0 vacantes')
    const stored = await page.evaluate(() =>
      JSON.stringify({
        local: { ...localStorage },
        session: { ...sessionStorage },
        cookie: document.cookie,
      }),
    )
    expect(stored).not.toContain('textoultrasecreto')
  })
})

test.describe('Order of the results (004-30)', () => {
  const sortBox = (page: Page) => page.getByRole('combobox', { name: 'Ordenar por' })
  const options = (page: Page) => sortBox(page).getByRole('option').allTextContents()

  test('"Más relevantes" exists only with text, is the default and writing "se usar java" puts the Java vacancy first', async ({
    page,
  }) => {
    await page.goto('/oportunidades')
    await loadedCount(page)
    expect(await options(page)).toEqual(['Más recientes', 'Empresa (A-Z)'])

    const sorts: string[] = []
    page.on('request', (request) => {
      const url = new URL(request.url())
      if (url.pathname.endsWith('/vacancies') && url.searchParams.has('sort')) {
        sorts.push(url.search)
      }
    })
    await page.getByRole('textbox', { name: '¿Qué sabes hacer?' }).fill('se usar java')
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/\?q=se\+usar\+java$/)
    await loadedCount(page)
    expect(await options(page)).toEqual(['Más relevantes', 'Más recientes', 'Empresa (A-Z)'])
    await expect(sortBox(page)).toHaveValue('relevance')
    await expect(page.getByRole('article').first()).toContainText('Practicante Backend Java')
    expect(sorts).toEqual([]) // the server decides: no `sort` is sent by default
  })

  test('an explicit order goes to the address and to the API, and the shared link reproduces it', async ({
    page,
  }) => {
    await page.goto('/oportunidades?q=practicante')
    await loadedCount(page)

    const request = page.waitForRequest((r) => r.url().includes('sort=company'))
    await sortBox(page).selectOption({ label: 'Empresa (A-Z)' })
    await request
    await expect(page).toHaveURL(/\?q=practicante&orden=empresa$/)
    await loadedCount(page)
    await expect(page.getByRole('article').first()).toContainText('Andes Tech')

    await page.reload()
    await loadedCount(page)
    await expect(sortBox(page)).toHaveValue('company')

    await sortBox(page).selectOption({ label: 'Más relevantes' })
    await expect(page).toHaveURL(/\?q=practicante&orden=relevancia$/)
    await loadedCount(page)
  })

  test('"orden=relevancia" without text is ignored and the address is corrected', async ({
    page,
  }) => {
    await page.goto('/oportunidades?orden=relevancia&modalidad=remoto')
    await loadedCount(page)
    await expect(page).toHaveURL(/\/oportunidades\?modalidad=remoto$/)
    await expect(sortBox(page)).toHaveValue('recent')
    expect(await options(page)).toEqual(['Más recientes', 'Empresa (A-Z)'])
  })

  test('changing the text goes back to the first page', async ({ page }) => {
    await page.goto('/oportunidades?q=practicante&pagina=2')
    await loadedCount(page)
    const field = page.getByRole('textbox', { name: '¿Qué sabes hacer?' })
    await field.fill('python')
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/\?q=python$/)
  })
})

test.describe('Search without results (004-33)', () => {
  const noMatches = (page: Page, text: string) =>
    page.getByRole('heading', { level: 3, name: `No encontramos vacantes para «${text}»` })
  const recentTitle = (page: Page) =>
    page.getByRole('heading', { level: 3, name: 'Vacantes recientes que pueden interesarte' })

  test('a text with no match says so, suggests what to do and offers 3 recent vacancies', async ({
    page,
  }) => {
    await page.goto('/oportunidades')
    await loadedCount(page)
    await page.getByRole('textbox', { name: '¿Qué sabes hacer?' }).fill('cobol')
    await page.keyboard.press('Enter')

    await expect(resultsTitle(page)).toHaveText('0 vacantes')
    await expect(noMatches(page, 'cobol')).toBeVisible()
    const hints = page.getByRole('list', { name: 'Sugerencias' })
    await expect(hints).toContainText('Revisa cómo lo escribiste.')
    await expect(hints).toContainText('Prueba con una tecnología, como Java o React.')
    await expect(page.getByRole('button', { name: 'Quitar filtros' })).toHaveCount(0)

    await expect(recentTitle(page)).toBeVisible()
    const recent = page.getByRole('list', { name: 'Vacantes recientes' })
    await expect(recent.getByRole('article')).toHaveCount(3)
    await expect(recent.getByText(/\d\s?%/)).toHaveCount(0)

    // The recent vacancies are real ones: one opens its detail.
    await recent.getByRole('article').first().getByRole('link').click()
    await expect(page).toHaveURL(/\/oportunidades\/[0-9a-f-]{36}$/)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  })

  test('with filters it suggests removing them and "Quitar filtros" keeps the text', async ({
    page,
  }) => {
    await page.goto('/oportunidades?q=cobol&modalidad=remoto&tecnologia=git')
    await expect(noMatches(page, 'cobol')).toBeVisible()
    await expect(page.getByRole('list', { name: 'Sugerencias' })).toContainText(
      'Quita algún filtro para ampliar la búsqueda.',
    )
    await page.getByRole('button', { name: 'Quitar filtros' }).click()
    await expect(page).toHaveURL(/\?q=cobol$/)
    await expect(noMatches(page, 'cobol')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Quitar filtros' })).toHaveCount(0)
    await expect(page.getByRole('textbox', { name: '¿Qué sabes hacer?' })).toHaveValue('cobol')
  })

  test('the text is shown as plain text and cut when long, with no horizontal scroll', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 900 })
    const long = 'x'.repeat(70)
    await page.goto(`/oportunidades?q=${encodeURIComponent(`<b>${long}</b>`)}`)
    const title = page.getByRole('heading', { level: 3, name: /^No encontramos vacantes para/ })
    await expect(title).toBeVisible()
    await expect(title.locator('b')).toHaveCount(0)
    expect(await title.textContent()).toContain('<b>')
    expect(await title.textContent()).toContain('…')
    expect(await title.textContent()).not.toContain(long)
    const size = await horizontalScroll(page)
    expect(size.scrollWidth).toBeLessThanOrEqual(size.clientWidth)
  })

  test('when the recent vacancies fail the screen keeps working without that section', async ({
    page,
  }) => {
    await page.route('**/api/v1/vacancies?sort=recent&size=3', (route) =>
      route.fulfill({ status: 500, contentType: 'application/json', body: '{}' }),
    )
    await page.goto('/oportunidades?q=cobol')
    await expect(noMatches(page, 'cobol')).toBeVisible()
    await expect(recentTitle(page)).toHaveCount(0)
    await expect(page.getByRole('alert')).toHaveCount(0)
    await expect(page.getByRole('textbox', { name: '¿Qué sabes hacer?' })).toHaveValue('cobol')
  })

  test('with results it does not ask for recent vacancies', async ({ page }) => {
    const recentRequests: string[] = []
    page.on('request', (request) => {
      if (request.url().includes('size=3')) recentRequests.push(request.url())
    })
    await page.goto('/oportunidades?q=java')
    expect(await loadedCount(page)).toBeGreaterThanOrEqual(1)
    await expect(recentTitle(page)).toHaveCount(0)
    expect(recentRequests).toEqual([])
  })

  for (const width of WIDTHS) {
    test(`at ${width} px the empty state with text and recent vacancies does not scroll sideways, has no serious axe violations and 44 px targets`, async ({
      page,
    }) => {
      test.setTimeout(120_000)
      await page.setViewportSize({ width, height: 900 })
      await page.goto('/oportunidades?q=cobol')
      await expect(page.getByRole('list', { name: 'Vacantes recientes' })).toBeVisible()
      await page.evaluate(() => document.fonts.ready)
      await page.waitForTimeout(500)
      let size = await horizontalScroll(page)
      expect(size.scrollWidth).toBeLessThanOrEqual(size.clientWidth)
      expect(await seriousViolations(page)).toEqual([])

      await page.goto('/oportunidades?q=cobol&modalidad=remoto')
      const remove = page.getByRole('button', { name: 'Quitar filtros' })
      await expect(remove).toBeVisible()
      expect((await remove.boundingBox())?.height ?? 0).toBeGreaterThanOrEqual(43.9)
      size = await horizontalScroll(page)
      expect(size.scrollWidth).toBeLessThanOrEqual(size.clientWidth)
      expect(await seriousViolations(page)).toEqual([])
    })
  }
})

test.describe('Keyboard only (004-17, 004-25)', () => {
  test('search, filter, paginate and open a vacancy using only the keyboard, with a visible focus', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/oportunidades')
    await loadedCount(page)

    const field = page.getByRole('textbox', { name: '¿Qué sabes hacer?' })
    await field.focus()
    await page.keyboard.type('git')
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/\?q=git$/)
    await loadedCount(page)

    const checkbox = page.getByRole('checkbox', { name: /^Remoto/ })
    await checkbox.focus()
    const ring = await page.evaluate(() => {
      const style = getComputedStyle(document.activeElement as HTMLElement)
      return { style: style.outlineStyle, width: parseFloat(style.outlineWidth) }
    })
    expect(ring.style).toBe('solid')
    expect(ring.width).toBeGreaterThanOrEqual(3)
    await page.keyboard.press('Space')
    await expect(page).toHaveURL(/q=git&modalidad=remoto/)
    await loadedCount(page)

    const link = page.getByRole('article').first().getByRole('link')
    await link.focus()
    const cardRing = await page.evaluate(() => {
      const style = getComputedStyle(document.activeElement as HTMLElement, '::after')
      return { style: style.outlineStyle, width: parseFloat(style.outlineWidth) }
    })
    expect(cardRing.style).toBe('solid')
    expect(cardRing.width).toBeGreaterThanOrEqual(3)
    await page.keyboard.press('Enter')
    await expect(page).toHaveURL(/\/oportunidades\/[0-9a-f-]{36}$/)
  })

  test('below 980 px the filters fold into "Filtros (n)"; Esc closes the panel and returns the focus', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 768, height: 900 })
    await page.goto('/oportunidades?modalidad=remoto')
    await loadedCount(page)

    const toggle = page.getByRole('button', { name: 'Filtros (1)' })
    const panel = page.getByRole('group', { name: 'Filtros de búsqueda' })
    await expect(toggle).toBeVisible()
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await expect(panel).toBeHidden()

    await toggle.focus()
    await page.keyboard.press('Enter')
    await expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await expect(panel).toBeVisible()
    await page.keyboard.press('Escape')
    await expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await expect(toggle).toBeFocused()
    await expect(panel).toBeHidden()

    await page.setViewportSize({ width: 1000, height: 900 })
    await expect(toggle).toBeHidden()
    await expect(panel).toBeVisible()
  })
})

test.describe('Responsive and accessibility (004-25)', () => {
  // Three pages with axe each, on a slow machine and with parallel workers.
  test.setTimeout(120_000)

  for (const width of WIDTHS) {
    test(`at ${width} px the home, the list and the detail do not scroll horizontally and have no serious axe violations`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 900 })
      const id = await firstVacancyId(page)

      await page.goto('/')
      await expect(
        page.getByRole('region', { name: /Vacantes destacadas/ }).getByRole('article'),
      ).toHaveCount(4)
      await page.evaluate(() => document.fonts.ready)
      await page.waitForTimeout(1300)
      let size = await horizontalScroll(page)
      expect(size.scrollWidth).toBeLessThanOrEqual(size.clientWidth)
      expect(await seriousViolations(page)).toEqual([])

      await page.goto('/oportunidades')
      await loadedCount(page)
      await page.evaluate(() => document.fonts.ready)
      size = await horizontalScroll(page)
      expect(size.scrollWidth).toBeLessThanOrEqual(size.clientWidth)
      expect(await seriousViolations(page)).toEqual([])

      if (width < 980) {
        await page.getByRole('button', { name: /^Filtros/ }).click()
        size = await horizontalScroll(page)
        expect(size.scrollWidth).toBeLessThanOrEqual(size.clientWidth)
        expect(await seriousViolations(page)).toEqual([])
      }

      await page.goto(`/oportunidades/${id}`)
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
      await expect(page.getByRole('heading', { level: 3, name: 'Obligatorios' })).toBeVisible()
      await page.evaluate(() => document.fonts.ready)
      size = await horizontalScroll(page)
      expect(size.scrollWidth).toBeLessThanOrEqual(size.clientWidth)
      expect(await seriousViolations(page)).toEqual([])
    })
  }

  test('the empty and the not-found states have no serious axe violations', async ({ page }) => {
    await page.goto('/oportunidades?modalidad=presencial&tecnologia=laravel')
    await expect(page.getByText('No encontramos vacantes con estos filtros')).toBeVisible()
    expect(await seriousViolations(page)).toEqual([])

    await page.goto('/oportunidades/00000000-0000-4000-8000-000000000000')
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    expect(await seriousViolations(page)).toEqual([])
  })

  test('the text enlarged to 200 % does not break the list or the detail', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 900 })
    const id = await firstVacancyId(page)
    for (const path of ['/oportunidades', `/oportunidades/${id}`]) {
      await page.goto(path)
      await page.evaluate(() =>
        document.documentElement.style.setProperty('font-size', '200%', 'important'),
      )
      await page.evaluate(() => document.fonts.ready)
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
      const size = await horizontalScroll(page)
      expect(size.scrollWidth).toBeLessThanOrEqual(size.clientWidth)
    }
  })

  test('every button, link-button, field and filter is at least 44 px tall on the phone', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 900 })
    await page.goto('/oportunidades')
    await loadedCount(page)
    await page.getByRole('button', { name: /^Filtros/ }).click()
    const small = await page.evaluate(() =>
      [
        ...document.querySelectorAll<HTMLElement>(
          'main button, main input:not([type=checkbox]), main select, main .btn, main .filters__option, main .pagination__item',
        ),
      ]
        .filter((el) => el.getBoundingClientRect().width > 0)
        .map((el) => ({
          text: (el.textContent || el.getAttribute('aria-label') || el.tagName).trim().slice(0, 30),
          height: el.getBoundingClientRect().height,
        }))
        .filter((item) => item.height < 43.9),
    )
    expect(small).toEqual([])
  })

  test('with "reduce motion" the skeletons are not animated', async ({ browser }) => {
    const context = await browser.newContext({ reducedMotion: 'reduce' })
    const page = await context.newPage()
    await page.route('**/api/v1/vacancies?**', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 1500))
      await route.continue()
    })
    await page.goto('/oportunidades?pagina=1&q=a')
    const skeleton = page.locator('.skeleton').first()
    await expect(skeleton).toBeVisible()
    expect(await skeleton.evaluate((el) => getComputedStyle(el).animationName)).toBe('none')
    await context.close()
  })

  test('with motion allowed the skeletons do shimmer (so the previous test is meaningful)', async ({
    page,
  }) => {
    await page.route('**/api/v1/vacancies?**', async (route) => {
      await new Promise((resolve) => setTimeout(resolve, 1500))
      await route.continue()
    })
    await page.goto('/oportunidades?q=a')
    const skeleton = page.locator('.skeleton').first()
    await expect(skeleton).toBeVisible()
    expect(await skeleton.evaluate((el) => getComputedStyle(el).animationName)).toBe(
      'skeleton-shimmer',
    )
  })
})
