import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

/**
 * Spec 009 against the real stack: sign up, open the CV reader from /perfil, a real error with a
 * file that is not a PDF, upload an INVENTED PDF built here, review the suggestions (basic
 * reading by rules, so no AI label), accept with a level, see the profile the SERVER updated
 * (also on /perfil and on the Home), then axe and no sideways scroll at 360, 768 and 1280 px.
 * One sign-up only (the backend allows 10 per minute per origin).
 */
test.describe.configure({ mode: 'serial', timeout: 180_000 })

const PASSWORD = 'Clave-de-prueba-123'
const email = `ana.cv+${Date.now().toString(36)}${Math.random().toString(36).slice(2, 6)}@example.test`

/** A small, valid, one-page PDF with plain text lines. All the data is invented. */
function buildPdf(lines: string[]): Buffer {
  const escape = (text: string) => text.replace(/[\\()]/g, (char) => `\\${char}`)
  const stream = `BT /F1 12 Tf 50 780 Td 16 TL ${lines
    .map((line) => `(${escape(line)}) Tj T*`)
    .join(' ')} ET`
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >>',
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>',
  ]
  let out = '%PDF-1.4\n'
  const offsets: number[] = []
  objects.forEach((body, index) => {
    offsets.push(out.length)
    out += `${index + 1} 0 obj\n${body}\nendobj\n`
  })
  const xref = out.length
  out += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`
  for (const offset of offsets) out += `${String(offset).padStart(10, '0')} 00000 n \n`
  out += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`
  return Buffer.from(out, 'latin1')
}

const CV_LINES = [
  'Ana Prueba Ejemplo',
  'Estudiante con interes en aplicaciones web y bases de datos. Busco mi primera practica.',
  'Estudios',
  'Carrera: Desarrollo de Software',
  'Instituto Andino de Tecnologia - Ciclo 5',
  'Habilidades',
  'Java, SQL, Git, Docker',
  'Proyectos',
  'Tienda en linea',
  'Tienda web para un negocio de barrio, hecha con Java y SQL, con pagos y reportes.',
]

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

const percent = (page: Page) => page.getByRole('progressbar', { name: 'Avance de tu perfil' })

test('upload an invented CV, review the suggestions, accept with a level and see the updated profile', async ({
  page,
}) => {
  // ---- Sign up ----
  await page.goto('/registro')
  await page.getByText('Lee qué datos usamos y para qué').waitFor()
  await page.getByLabel('Correo').fill(email)
  await page.getByLabel('Contraseña', { exact: true }).fill(PASSWORD)
  await page.getByRole('checkbox', { name: /Leí y acepto/ }).check()
  await page.getByRole('button', { name: 'Crear mi cuenta' }).click()
  await expect(page.getByRole('heading', { level: 1, name: 'Hola de nuevo' })).toBeVisible()
  await expect(percent(page)).toHaveAttribute('aria-valuenow', '0')

  // ---- From /perfil, the CV block is an entry, no longer "Próximamente" ----
  await page.goto('/perfil')
  const block = page.getByRole('region', { name: 'Llena tu perfil con tu CV' })
  await expect(block.getByText('Próximamente')).toHaveCount(0)
  await block.getByRole('link', { name: 'Subir mi CV' }).click()
  await expect(page).toHaveURL(/\/perfil\/cv$/)
  await expect(
    page.getByRole('heading', { level: 1, name: 'Llena tu perfil con tu CV' }),
  ).toBeVisible()

  // Information line, not a permission: no checkbox, nothing blocks.
  await expect(
    page.getByText('Leeremos tu CV para sugerirte tu perfil. Tú decides qué se guarda.'),
  ).toBeVisible()
  await expect(page.getByRole('checkbox')).toHaveCount(0)
  const fileInput = page.locator('input[type="file"]')

  // ---- A real error: a file that is not a PDF (checked here), then one that lies about it ----
  await fileInput.setInputFiles({
    name: 'nota.txt',
    mimeType: 'text/plain',
    buffer: Buffer.from('esto no es un CV'),
  })
  await expect(page.getByRole('alert')).toContainText('Solo podemos leer archivos PDF')
  await fileInput.setInputFiles({
    name: 'falso.pdf',
    mimeType: 'application/pdf',
    buffer: Buffer.from('esto dice ser un PDF pero no lo es'),
  })
  await expect(page.getByRole('alert')).toContainText('no parece un PDF válido')
  await expect(
    page.getByRole('alert').getByRole('link', { name: 'Prefiero completarlo a mano' }),
  ).toBeVisible()

  // ---- Upload the invented PDF ----
  await fileInput.setInputFiles({
    name: 'cv-ana.pdf',
    mimeType: 'application/pdf',
    buffer: buildPdf(CV_LINES),
  })
  await expect(
    page.getByRole('heading', { level: 2, name: 'Revisa lo que encontramos' }),
  ).toBeVisible({
    timeout: 60_000,
  })

  // Basic reading by rules: it says so and it does NOT wear the AI label.
  await expect(page.getByText('Sugerencias automáticas por reglas')).toBeVisible()
  await expect(page.getByText('DORA · inteligencia artificial')).toHaveCount(0)
  await expect(page.getByText('Tú decides: nada se guarda hasta que confirmes.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Leer mejor con IA' })).toHaveCount(0)
  await expect(page.getByRole('heading', { level: 3, name: 'Habilidades' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Aceptar Java' })).toBeVisible()

  // ---- Axe and no sideways scroll at 360, 768 and 1280 px ----
  for (const width of [360, 768, 1280]) {
    await page.setViewportSize({ width, height: 900 })
    expect(await seriousViolations(page), `revisión a ${width} px`).toEqual([])
    await noSidewaysScroll(page)
  }

  // ---- A skill needs a level ----
  await page.getByRole('button', { name: 'Aceptar Java' }).click()
  await expect(page.getByText('Elige tu nivel para aceptar esta habilidad.').first()).toBeVisible()

  // ---- Choose the level of every skill, then accept everything that was found ----
  const groups = page.getByRole('radiogroup')
  const skills = await groups.count()
  expect(skills).toBeGreaterThanOrEqual(3)
  for (let index = 0; index < skills; index += 1) {
    await groups.nth(index).getByRole('radio', { name: 'Básico' }).check()
  }
  const accepts = page.getByRole('button', { name: /^Aceptar / })
  const total = await accepts.count()
  for (let index = 0; index < total; index += 1) await accepts.nth(index).click()
  await expect(page.getByRole('button', { name: `Guardar en mi perfil (${total})` })).toBeEnabled()

  await page.getByRole('button', { name: `Guardar en mi perfil (${total})` }).click()
  await expect(page.getByRole('heading', { level: 2, name: 'Guardamos tus datos' })).toBeVisible()
  const saved = Number(await percent(page).getAttribute('aria-valuenow'))
  expect(saved).toBeGreaterThanOrEqual(40)

  // ---- The profile and the Home show what the SERVER saved ----
  await page.getByRole('link', { name: 'Ver mi perfil' }).click()
  await expect(page).toHaveURL(/\/perfil$/)
  await expect(percent(page)).toHaveAttribute('aria-valuenow', String(saved))
  await page.goto('/')
  await expect(percent(page)).toHaveAttribute('aria-valuenow', String(saved))

  // The CV is closed: the reader asks for a new upload, and nothing was kept in the browser.
  await page.goto('/perfil/cv')
  await expect(page.getByRole('button', { name: 'Subir mi CV' })).toBeVisible()
  const stored = await page.evaluate(() => [
    ...Object.keys(localStorage),
    ...Object.keys(sessionStorage),
  ])
  expect(stored).toEqual([])
  for (const width of [360, 1280]) {
    await page.setViewportSize({ width, height: 900 })
    expect(await seriousViolations(page), `subida a ${width} px`).toEqual([])
    await noSidewaysScroll(page)
  }
})
