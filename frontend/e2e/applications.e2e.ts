import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

/**
 * Spec 008 against the real stack: sign up, apply from a vacancy, see it in "Mis postulaciones"
 * and its line of stages, withdraw, apply again, a duplicate from a second tab, axe and no
 * sideways scroll at 360, 768 and 1280 px. One sign-up only (the backend allows 10 per minute).
 */
test.describe.configure({ mode: 'serial', timeout: 180_000 })

const PASSWORD = 'Clave-de-prueba-123'
const email = `ana.post+${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}@example.test`

async function seriousViolations(page: Page) {
  const results = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21a', 'wcag21aa', 'wcag22aa'])
    .analyze()
  return results.violations
    .filter((v) => v.impact === 'serious' || v.impact === 'critical')
    .map((v) => ({ id: v.id, nodes: v.nodes.map((n) => n.target.join(' ')) }))
}

async function noSidewaysScroll(page: Page) {
  const { scrollWidth, clientWidth } = await page.evaluate(() => ({
    scrollWidth: document.documentElement.scrollWidth,
    clientWidth: document.documentElement.clientWidth,
  }))
  expect(scrollWidth).toBeLessThanOrEqual(clientWidth)
}

async function checkThreeWidths(page: Page, label: string) {
  for (const width of [360, 768, 1280]) {
    await page.setViewportSize({ width, height: 900 })
    expect(await seriousViolations(page), `${label} a ${width} px`).toEqual([])
    await noSidewaysScroll(page)
  }
}

test('apply, follow, withdraw and apply again from the real vacancy detail', async ({
  page,
  context,
}) => {
  // ---- Sign up ----
  await page.goto('/registro')
  await page.getByText('Lee qué datos usamos y para qué').waitFor()
  await page.getByLabel('Correo').fill(email)
  await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD)
  await page.getByRole('checkbox', { name: /Leí y acepto/ }).check()
  await page.getByRole('button', { name: 'Crear mi cuenta' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Hola de nuevo' })).toBeVisible()

  // Home: no applications yet, the block is real (no "Próximamente").
  const block = page.getByRole('region', { name: 'Tus postulaciones' })
  await expect(block.getByText('Aún no te postulaste a ninguna vacante')).toBeVisible()
  await expect(block.getByText('Próximamente')).toHaveCount(0)
  await expect(
    page.getByRole('navigation', { name: 'Principal' }).getByRole('link', {
      name: 'Postulaciones',
    }),
  ).toBeVisible()

  // ---- Open the first vacancy ----
  await page.goto('/oportunidades')
  await page.getByRole('article').first().getByRole('link').first().click()
  await page.waitForURL(/\/oportunidades\/[^/?]+$/)
  await expect(page.getByRole('button', { name: 'Postular', exact: true })).toBeVisible()
  const title = await page.getByRole('heading', { level: 1 }).innerText()
  const detailUrl = page.url()

  // ---- Apply: the dialog, no "example" warnings ----
  await page.getByRole('button', { name: 'Postular', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toBeVisible()
  await expect(dialog.getByRole('heading', { level: 2 })).toBeFocused()
  await expect(dialog.getByText('La decisión final es de la empresa')).toBeVisible()
  await expect(dialog).not.toContainText(/ejemplo|prueba|ficticia/i)
  await expect(dialog.getByText(/Tu perfil está al/)).toBeVisible()
  await checkThreeWidths(page, 'diálogo de postulación')
  await page.setViewportSize({ width: 1280, height: 900 })

  // Esc cancels and the focus goes back to the button.
  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Postular', exact: true })).toBeFocused()

  await page.getByRole('button', { name: 'Postular', exact: true }).click()
  await page.getByRole('button', { name: 'Confirmar postulación' }).click()
  await expect(page.getByText('Postulación enviada')).toBeVisible()
  await expect(page.getByText(/Postulaste el/)).toBeVisible()
  await expect(page.getByRole('link', { name: 'Ver mi postulación' })).toBeVisible()

  // ---- Mis postulaciones ----
  await page
    .getByRole('navigation', { name: 'Principal' })
    .getByRole('link', { name: 'Postulaciones' })
    .click()
  await expect(page).toHaveURL(/\/postulaciones$/)
  await expect(page.getByRole('heading', { level: 1, name: 'Mis postulaciones' })).toBeVisible()
  const item = page.getByRole('listitem').filter({ hasText: title })
  await expect(item.getByText('Enviada', { exact: true })).toBeVisible()
  await checkThreeWidths(page, '/postulaciones')

  // ---- Detail with the line of stages ----
  await item.getByRole('link', { name: title }).click()
  await expect(page.getByRole('heading', { level: 1, name: title })).toBeVisible()
  const stages = page.getByRole('region', { name: 'Etapas del proceso' }).getByRole('listitem')
  await expect(stages).toHaveText([
    /Enviada/,
    /Examen técnico/,
    /Entrevista/,
    /Revisión de la empresa/,
  ])
  await checkThreeWidths(page, 'detalle de la postulación')
  await page.setViewportSize({ width: 1280, height: 900 })

  // ---- Second tab keeps an old view of the vacancy, then the first one withdraws ----
  const other = await context.newPage()
  await other.goto(detailUrl)
  await expect(other.getByText(/Postulaste el/)).toBeVisible()

  await page.getByRole('button', { name: 'Retirar postulación' }).click()
  const withdrawDialog = page.getByRole('dialog')
  await expect(withdrawDialog.getByRole('heading', { level: 2 })).toContainText(
    '¿Retirar tu postulación',
  )
  await withdrawDialog.getByRole('button', { name: 'Sí, retirar' }).click()
  await expect(page.getByText('Postulación retirada')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Retirar postulación' })).toHaveCount(0)
  await expect(stages).toHaveText([/Enviada/, /Retirada/])

  // ---- Apply again from the vacancy ----
  await page.goto(detailUrl)
  await expect(page.getByText(/Retiraste tu postulación el/)).toBeVisible()
  await page.getByRole('button', { name: 'Postular de nuevo' }).click()
  await page.getByRole('button', { name: 'Confirmar postulación' }).click()
  await expect(page.getByText(/Postulaste el/)).toBeVisible()

  // ---- A duplicate from the other tab (its view is old: it thinks it was withdrawn) ----
  await other.reload()
  await expect(other.getByText(/Postulaste el/)).toBeVisible()
  const duplicate = await other.evaluate(async (vacancyUrl) => {
    const id = vacancyUrl.split('/').pop()
    const token = decodeURIComponent(
      document.cookie
        .split('; ')
        .find((c) => c.startsWith('XSRF-TOKEN='))
        ?.split('=')[1] ?? '',
    )
    const response = await fetch('/api/v1/applications', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'X-XSRF-TOKEN': token },
      body: JSON.stringify({ vacancyId: id }),
    })
    return { status: response.status, body: await response.json() }
  }, detailUrl)
  expect(duplicate.status).toBe(409)
  expect(duplicate.body.code).toBe('application-already-exists')
  await other.close()

  // ---- The list keeps both, the Home counts the active one, nothing in the browser storage ----
  await page.goto('/postulaciones')
  const rows = page.getByRole('listitem').filter({ hasText: title })
  await expect(rows).toHaveCount(2)
  await expect(rows.nth(0).getByText('Enviada', { exact: true })).toBeVisible()
  await expect(rows.nth(1).getByText('Retirada', { exact: true })).toBeVisible()
  await page.goto('/')
  await expect(
    page
      .getByRole('region', { name: 'Tus postulaciones' })
      .getByText('Tienes 1 postulación activa'),
  ).toBeVisible()
  await checkThreeWidths(page, 'Inicio')
  const stored = await page.evaluate(() => [
    ...Object.keys(localStorage),
    ...Object.keys(sessionStorage),
  ])
  expect(stored).toEqual([])
})
