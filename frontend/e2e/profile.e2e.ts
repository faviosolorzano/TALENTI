import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

/**
 * Specs 005 and 006 against the real stack: sign up, Home at 0 %, fill "Datos personales", the studies
 * and the skills and watch the percentage the SERVER calculates go up; axe on /perfil and on the
 * Home at 360 and 1280 px. The e-mail is invented and unique; one sign-up only (the backend
 * allows 10 per minute per origin).
 */
test.describe.configure({ mode: 'serial', timeout: 120_000 })

const PASSWORD = 'Clave-de-prueba-123'
const email = `ana.perfil+${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}@example.test`

async function seriousViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze()
  return results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target.join(' ')) }))
}

const percent = (page: Page) => page.getByRole('progressbar', { name: 'Avance de tu perfil' })

test('sign up, Home at 0 %, then the real percentage rises as sections are saved', async ({
  page,
}) => {
  await page.goto('/registro')
  await page.getByText('Lee qué datos usamos y para qué').waitFor()
  await page.getByLabel('Correo').fill(email)
  await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD)
  await page.getByRole('checkbox', { name: /Leí y acepto/ }).check()
  await page.getByRole('button', { name: 'Crear mi cuenta' }).click()

  await expect(page.getByRole('heading', { level: 1, name: 'Hola de nuevo' })).toBeVisible()
  await expect(page.getByText('Tu cuenta está lista')).toBeVisible()
  await expect(percent(page)).toHaveAttribute('aria-valuenow', '0')
  await expect(page.getByText(email)).toHaveCount(0)

  // Home -> next step the server chose (skills) -> opens that card.
  await page.getByRole('link', { name: 'Agrega tus habilidades' }).click()
  await expect(page).toHaveURL(/\/perfil\?seccion=habilidades$/)
  await expect(page.getByRole('searchbox', { name: 'Buscar en el catálogo' })).toBeFocused()

  // Skills: three from the catalog, found by name and by synonym.
  const search = page.getByRole('searchbox', { name: 'Buscar en el catálogo' })
  await search.fill('js')
  await page.getByRole('button', { name: /JavaScript/ }).click()
  await search.fill('java')
  await page.getByRole('button', { name: 'Java', exact: true }).click()
  await search.fill('sql')
  await page.getByRole('button', { name: 'SQL', exact: true }).click()
  await page.getByRole('button', { name: 'Guardar' }).click()
  await expect(percent(page)).toHaveAttribute('aria-valuenow', '25')

  // Studies: a 422 from the real server first, then the right data.
  await page.getByRole('button', { name: 'Editar Datos académicos' }).click()
  await page.getByLabel('Carrera').fill('Desarrollo de Software')
  await page.getByLabel('Instituto').fill('X')
  await page.getByLabel('Ciclo').fill('5')
  await page.getByRole('button', { name: 'Guardar' }).click()
  const institute = page.getByLabel('Instituto')
  await expect(institute).toBeFocused()
  await expect(institute).toHaveAttribute('aria-invalid', 'true')
  await institute.fill('Instituto Andino')
  await page.getByRole('button', { name: 'Guardar' }).click()
  await expect(percent(page)).toHaveAttribute('aria-valuenow', '40')

  // Personal data: the contact e-mail starts as the account's; a real 422 for the phone first.
  await page.getByRole('button', { name: 'Editar Datos personales' }).click()
  await expect(page.getByLabel('Correo de contacto')).toHaveValue(email)
  await page.getByLabel('Nombres').fill('Ana María')
  await page.getByLabel('Apellidos').fill('Quispe Huamán')
  await page.getByLabel('Celular').fill('12345')
  await page.getByLabel('Sobre ti').fill('Estudio software y busco mi primera práctica.')
  await page.getByRole('button', { name: 'Guardar' }).click()
  const phone = page.getByLabel('Celular')
  await expect(phone).toBeFocused()
  await expect(phone).toHaveAttribute('aria-invalid', 'true')
  await expect(phone).toHaveAccessibleDescription(/Nueve dígitos.*no es válido/)
  await phone.fill('+51 987 654 321')
  await page.getByRole('button', { name: 'Guardar' }).click()
  // Only "Sobre ti" adds to the percentage; the personal data do not.
  await expect(percent(page)).toHaveAttribute('aria-valuenow', '50')
  await expect(page.getByText('987 654 321')).toBeVisible()

  // Reload: what was saved is still there, nothing is kept in the browser.
  await page.reload()
  await expect(percent(page)).toHaveAttribute('aria-valuenow', '50')
  const stored = await page.evaluate(() => [
    ...Object.keys(localStorage),
    ...Object.keys(sessionStorage),
  ])
  expect(stored).toEqual([])

  await page
    .getByRole('navigation', { name: 'Principal' })
    .getByRole('link', { name: 'Inicio' })
    .click()
  await expect(page.getByRole('heading', { level: 1, name: 'Hola, Ana' })).toBeVisible()
  await expect(percent(page)).toHaveAttribute('aria-valuenow', '50')
})

for (const width of [360, 768, 1280]) {
  test(`axe and no sideways scroll on /perfil and on the Home at ${width} px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 900 })
    await page.goto('/ingresar')
    await page.getByLabel('Correo').fill(email)
    await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD)
    await page.getByRole('button', { name: 'Ingresar', exact: true }).click()
    await expect(page.getByRole('heading', { level: 1, name: 'Hola, Ana' })).toBeVisible()
    await expect(percent(page)).toBeVisible()
    expect(await seriousViolations(page), 'Inicio').toEqual([])

    const noSidewaysScroll = async () => {
      const { scrollWidth, clientWidth } = await page.evaluate(() => ({
        scrollWidth: document.documentElement.scrollWidth,
        clientWidth: document.documentElement.clientWidth,
      }))
      expect(scrollWidth).toBeLessThanOrEqual(clientWidth)
    }
    await noSidewaysScroll()

    await page.goto('/perfil?seccion=proyectos')
    await expect(page.getByRole('button', { name: 'Agregar proyecto' })).toBeVisible()
    expect(await seriousViolations(page), '/perfil con una sección abierta').toEqual([])
    await noSidewaysScroll()

    await page.goto('/perfil?seccion=sobre-ti')
    await expect(page.getByLabel('Nombres')).toBeVisible()
    await expect(page.getByRole('region', { name: 'Enlaza tu GitHub' })).toHaveCount(0)
    expect(await seriousViolations(page), 'Datos personales abiertos').toEqual([])
    await noSidewaysScroll()
  })
}
