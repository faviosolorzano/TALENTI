import { expect, test, type Page } from '@playwright/test'

const WIDTHS = [360, 768, 1280] as const

async function noHorizontalScroll(page: Page) {
  return page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
}

// 003-26
test.describe('Responsive layout (003-26)', () => {
  for (const width of WIDTHS) {
    test(`at ${width} px the home page does not scroll horizontally`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
      await page.goto('/')
      await page.evaluate(() => document.fonts.ready)
      const { scrollWidth, clientWidth } = await noHorizontalScroll(page)
      expect(scrollWidth).toBeLessThanOrEqual(clientWidth)
    })
  }

  test('below 980 px the columns become one; from 980 px there are two', async ({ page }) => {
    const columns = () =>
      page.evaluate(() => {
        const grid = document.querySelector('.hero__grid') as HTMLElement
        return getComputedStyle(grid).gridTemplateColumns.split(' ').length
      })
    await page.setViewportSize({ width: 979, height: 900 })
    await page.goto('/')
    expect(await columns()).toBe(1)
    await page.setViewportSize({ width: 1000, height: 900 })
    expect(await columns()).toBe(2)
  })

  test('below 860 px the menu folds into the "Menú" button; from 860 px it is a row', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 859, height: 900 })
    await page.goto('/')
    const nav = page.getByRole('navigation', { name: 'Navegación principal' })
    const button = page.getByRole('button', { name: 'Menú' })
    await expect(button).toBeVisible()
    await expect(nav).toBeHidden()

    await button.click()
    await expect(button).toHaveAttribute('aria-expanded', 'true')
    await expect(nav).toBeVisible()
    await expect(nav.getByRole('link', { name: 'Ingresar' })).toBeVisible()

    await page.setViewportSize({ width: 860, height: 900 })
    await expect(button).toBeHidden()
    await expect(nav).toBeVisible()
  })

  test('the menu closes when a link is chosen and the anchor leaves the section below the fixed header', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 800 })
    await page.goto('/')
    const button = page.getByRole('button', { name: 'Menú' })
    await button.click()
    await page
      .getByRole('navigation', { name: 'Navegación principal' })
      .getByRole('link', { name: 'Cómo funciona' })
      .click()
    await expect(button).toHaveAttribute('aria-expanded', 'false')

    await page.waitForFunction(() => window.scrollY > 100)
    await page.waitForTimeout(900)
    const geometry = await page.evaluate(() => {
      const header = document.querySelector('header') as HTMLElement
      const title = document.querySelector('#how-title') as HTMLElement
      return {
        headerBottom: header.getBoundingClientRect().bottom,
        titleTop: title.getBoundingClientRect().top,
        position: getComputedStyle(header).position,
      }
    })
    expect(geometry.titleTop).toBeGreaterThanOrEqual(geometry.headerBottom - 1)
    expect(geometry.titleTop).toBeLessThan(400)
    expect(['sticky', 'fixed']).toContain(geometry.position)
  })

  test('the header shows its bottom line after scrolling more than 8 px', async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 800 })
    await page.goto('/')
    const header = page.getByRole('banner')
    await expect(header).not.toHaveAttribute('data-scrolled', /.*/)
    await page.mouse.wheel(0, 300)
    await expect(header).toHaveAttribute('data-scrolled', 'true')
    const border = await header.evaluate((el) => getComputedStyle(el).borderBottomColor)
    expect(border).not.toBe('rgba(0, 0, 0, 0)')
  })

  for (const width of [360, 1280] as const) {
    test(`at ${width} px, with the text enlarged to 200 %, no content is lost or cut off`, async ({
      page,
    }) => {
      await page.setViewportSize({ width, height: 900 })
      await page.goto('/')
      // addStyleTag would be blocked by the CSP; setting the property through the DOM is allowed.
      await page.evaluate(() =>
        document.documentElement.style.setProperty('font-size', '200%', 'important'),
      )
      await page.evaluate(() => document.fonts.ready)
      const { scrollWidth, clientWidth } = await noHorizontalScroll(page)
      expect(scrollWidth).toBeLessThanOrEqual(clientWidth)

      // Every text of the hero and the card is still inside the page width.
      const overflowing = await page.evaluate(() => {
        const limit = document.documentElement.clientWidth
        return [
          ...document.querySelectorAll<HTMLElement>(
            'main h1, main h2, main h3, main p, main button',
          ),
        ]
          .filter(
            (el) =>
              el.getBoundingClientRect().width > 0 && el.getBoundingClientRect().right > limit + 1,
          )
          .map((el) => el.textContent?.slice(0, 40))
      })
      expect(overflowing).toEqual([])
    })
  }

  test('every button and chip is at least 44 px tall', async ({ page }) => {
    await page.setViewportSize({ width: 360, height: 900 })
    await page.goto('/')
    const small = await page.evaluate(() =>
      [...document.querySelectorAll<HTMLElement>('main button, header button, .btn, .level-chip')]
        .filter((el) => el.getBoundingClientRect().width > 0)
        .map((el) => ({
          text: el.textContent?.trim().slice(0, 30),
          height: el.getBoundingClientRect().height,
        }))
        .filter((item) => item.height < 43.9),
    )
    expect(small).toEqual([])
  })
})
