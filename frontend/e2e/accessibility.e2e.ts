import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

const WIDTHS = [360, 768, 1280] as const

async function seriousViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze()
  return results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, impact: v.impact, nodes: v.nodes.map((n) => n.target.join(' ')) }))
}

// 003-27: axe reports no serious or critical violations (goal, RNF-14).
test.describe('axe (003-27)', () => {
  for (const width of WIDTHS) {
    test(`at ${width} px the home page has no serious or critical violations`, async ({ page }) => {
      await page.setViewportSize({ width, height: 900 })
      await page.goto('/')
      await page.evaluate(() => document.fonts.ready)
      await page.waitForTimeout(1300) // the ring finishes its animation
      expect(await seriousViolations(page)).toEqual([])
    })
  }

  test('with the menu open at 360 px there are no serious or critical violations', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 800 })
    await page.goto('/')
    await page.getByRole('button', { name: 'Menú' }).click()
    expect(await seriousViolations(page)).toEqual([])
  })

  test('with the states of the card and of DORA changed there are no serious or critical violations', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/')
    await page.getByRole('button', { name: /^Java,/ }).click()
    await page.getByRole('button', { name: 'Pensando' }).click()
    await page.locator('details.compat__how summary').click()
    await page.getByRole('tab', { name: /Practica y postula/ }).click()
    expect(await seriousViolations(page)).toEqual([])
  })
})

// 003-27: keyboard only.
test.describe('Keyboard only (003-27)', () => {
  test.beforeEach(async ({ page }) => {
    await page.setViewportSize({ width: 1280, height: 900 })
    await page.goto('/')
    await page.evaluate(() => document.fonts.ready)
  })

  const focusedOutline = (page: Page) =>
    page.evaluate(() => {
      const el = document.activeElement as HTMLElement
      const style = getComputedStyle(el)
      return { width: style.outlineWidth, style: style.outlineStyle, color: style.outlineColor }
    })

  test('the first Tab reaches "Saltar al contenido" and Enter moves focus to the main area', async ({
    page,
  }) => {
    await page.keyboard.press('Tab')
    const skip = page.getByRole('link', { name: 'Saltar al contenido' })
    await expect(skip).toBeFocused()
    await page.keyboard.press('Enter')
    await expect(page.getByRole('main')).toBeFocused()
  })

  test('tabbing goes through the header, then the hero, then the card, in a logical order, always with a visible focus', async ({
    page,
  }) => {
    const names: string[] = []
    for (let i = 0; i < 14; i++) {
      await page.keyboard.press('Tab')
      const label = await page.evaluate(() => {
        const el = document.activeElement as HTMLElement
        return (el.getAttribute('aria-label') ?? el.textContent ?? '').trim().slice(0, 30)
      })
      names.push(label)
      const outline = await focusedOutline(page)
      expect(outline.style).toBe('solid')
      expect(parseFloat(outline.width)).toBeGreaterThanOrEqual(3)
      // --color-focus is the brand blue (#2563EB); the skip link sits on navy and uses white.
      expect(outline.color).toBe(i === 0 ? 'rgb(255, 255, 255)' : 'rgb(37, 99, 235)')
    }
    expect(names[0]).toBe('Saltar al contenido')
    expect(names.slice(1, 4)).toEqual(['Talenti, inicio', 'Inicio', 'Cómo funciona'])
    const heroAction = names.indexOf('Ver mis vacantes')
    const firstChip = names.findIndex((n) => n.startsWith('Java,'))
    expect(heroAction).toBeGreaterThan(5)
    expect(firstChip).toBeGreaterThan(heroAction)
  })

  test('a chip changes level with Enter and Space and keeps the focus; ring and verdict follow', async ({
    page,
  }) => {
    const chip = page.getByRole('button', { name: /^Java,/ })
    await chip.focus()
    await page.keyboard.press('Enter') // Lo domino -> No lo sé
    await expect(chip).toBeFocused()
    await expect(
      page.getByRole('heading', { level: 3, name: 'Te falta un requisito obligatorio' }),
    ).toBeVisible()
    await page.keyboard.press('Space') // -> Lo básico
    await expect(chip).toBeFocused()
    await expect(chip).toHaveAccessibleName(/nivel actual: Lo básico/)
    await expect(page.getByRole('heading', { level: 3, name: /Encajas/ })).toBeVisible()
  })

  test('the ring announces only the final value in a polite live region', async ({ page }) => {
    const region = page.locator('.ring [aria-live="polite"]')
    await expect(region).toHaveText('82 % de compatibilidad')
  })

  test('the disclosure "Cómo se calcula este porcentaje" opens with Enter', async ({ page }) => {
    const summary = page.locator('details.compat__how summary')
    await summary.focus()
    await page.keyboard.press('Enter')
    await expect(page.locator('details.compat__how')).toHaveJSProperty('open', true)
    await expect(page.getByText('no la inteligencia artificial')).toBeVisible()
  })

  test('"Cómo funciona": only the active tab is tabbable; arrows change step and move focus', async ({
    page,
  }) => {
    const tabs = page.getByRole('tab')
    await expect(tabs).toHaveCount(3)
    await expect(tabs.nth(0)).toHaveAttribute('tabindex', '0')
    await expect(tabs.nth(1)).toHaveAttribute('tabindex', '-1')
    await tabs.nth(0).focus()
    await page.keyboard.press('ArrowDown')
    await expect(tabs.nth(1)).toBeFocused()
    await expect(tabs.nth(1)).toHaveAttribute('aria-selected', 'true')
    await page.keyboard.press('ArrowDown')
    await page.keyboard.press('ArrowDown') // wraps to the first
    await expect(tabs.nth(0)).toBeFocused()
    await expect(page.getByRole('tabpanel')).toHaveCount(1)
  })

  test('the states of DORA and the microphone button work with the keyboard', async ({ page }) => {
    const speaking = page.getByRole('button', { name: 'Hablando' })
    await speaking.focus()
    await page.keyboard.press('Enter')
    await expect(speaking).toHaveAttribute('aria-pressed', 'true')
    await expect(
      page.getByText('Háblame de un proyecto del que te sientas orgulloso.'),
    ).toBeVisible()
    await expect(page.getByRole('button', { name: 'Probar con mi micrófono' })).toBeVisible()
  })

  test('the folded menu opens with the keyboard, takes the focus in and closes with Esc returning it to the button', async ({
    page,
  }) => {
    await page.setViewportSize({ width: 360, height: 800 })
    const button = page.getByRole('button', { name: 'Menú' })
    await button.focus()
    await page.keyboard.press('Enter')
    await expect(button).toHaveAttribute('aria-expanded', 'true')
    await expect(
      page.getByRole('navigation', { name: 'Navegación principal' }).getByRole('link').first(),
    ).toBeFocused()
    await page.keyboard.press('Escape')
    await expect(button).toHaveAttribute('aria-expanded', 'false')
    await expect(button).toBeFocused()
  })
})
