import { expect, test, type Page } from '@playwright/test'
import AxeBuilder from '@axe-core/playwright'
import { mkdirSync } from 'node:fs'
import { resolve } from 'node:path'
import { es } from '../src/i18n/es'
const t = es.workflows
const output = process.env.E2E_REDESIGN_OUTPUT ?? 'test-results/redesign'
test.describe.configure({ mode: 'serial', timeout: 240000 })
test.skip(process.env.E2E_REAL_BACKEND !== '1', 'Requires an isolated simulated-provider backend.')
test.use({
  launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] },
  permissions: ['microphone'],
})
async function shot(page: Page, name: string) {
  mkdirSync(output, { recursive: true })
  await page.evaluate(() => document.fonts.ready)
  await page.screenshot({ path: resolve(output, name + '.png'), fullPage: true })
}
async function reflow(page: Page) {
  expect(
    await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1),
  ).toBe(true)
}
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

test('E2/E7: approved public composition, real content and responsive layout', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { level: 1 })).toContainText('Tu primera práctica.')
  await expect(page.locator('article').first()).toBeVisible()
  const reject = page.getByRole('button', { name: es.cookies.banner.reject, exact: true })
  if (await reject.count()) await reject.click()
  await shot(page, 'inicio-escritorio')
  for (const width of [320, 360, 768, 1280]) {
    await page.setViewportSize({ width, height: 900 })
    await reflow(page)
    if (width === 360) await shot(page, 'inicio-movil')
  }
  const result = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze()
  expect(
    result.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious'),
  ).toEqual([])
})
test('E3–E7, 012–014: real CV, DORA voice, application, code execution, exam and interview', async ({
  page,
}) => {
  const errors: string[] = []
  page.on('pageerror', (error) => errors.push(error.message))
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.goto('/registro')
  await page
    .getByLabel('Correo', { exact: true })
    .fill('presentacion.' + Date.now() + '@example.test')
  await page.getByLabel('Contraseña', { exact: true }).fill('Prueba-visual-2026')
  await page.getByRole('checkbox', { name: /Leí y acepto/ }).check()
  const reject = page.getByRole('button', { name: es.cookies.banner.reject, exact: true })
  if (await reject.count()) await reject.click()
  await page.getByRole('button', { name: es.identity.register.submit, exact: true }).click()
  await expect(page).toHaveURL(/\/$/)
  await page.goto('/perfil/cv')
  const pdf = buildPdf([
    'Ana Prueba Ejemplo',
    'Estudiante de desarrollo de software',
    'Estudios',
    'Carrera: Desarrollo de Software',
    'Instituto Andino de Tecnologia - Ciclo 5',
    'Habilidades',
    'Java, SQL, Git',
    'Proyectos',
    'Tienda en linea',
    'Tienda web para un negocio de barrio, hecha con Java y SQL, con pagos y reportes.',
  ])
  await page
    .locator('input[type=file]')
    .setInputFiles({ name: 'cv-inventado.pdf', mimeType: 'application/pdf', buffer: pdf })
  await expect(page.getByRole('heading', { name: es.cv.review.title })).toBeVisible({
    timeout: 30000,
  })
  await shot(page, 'cv-revision-escritorio')
  for (const name of ['Java', 'SQL', 'Git']) {
    const row = page
      .locator('.suggestion')
      .filter({ has: page.getByText(name, { exact: true }) })
      .first()
    await row.getByRole('button', { name: es.redesign.reviewLevel, exact: true }).click()
    await row.getByRole('radio', { name: 'Intermedio', exact: true }).check()
    await row.getByRole('button', { name: es.cv.review.acceptLabel(name), exact: true }).click()
  }
  await page.getByRole('button', { name: es.cv.review.save(3), exact: true }).click()
  await page.getByRole('button', { name: es.redesign.discardPending, exact: true }).click()
  await expect(page.getByRole('heading', { name: es.cv.done.title })).toBeVisible()
  await page.goto('/oportunidades?q=java&modalidad=remoto')
  await expect(page.locator('article').first()).toBeVisible()
  await shot(page, 'vacantes-escritorio')
  const vacancyLink = page.locator('article h3 a').first()
  const vacancyPath = await vacancyLink.getAttribute('href')
  expect(vacancyPath).toBeTruthy()
  await vacancyLink.click()
  await page.getByRole('button', { name: es.redesign.fitTab, exact: true }).click()
  await expect(page.locator('.comparison-row').first()).toBeVisible()
  await shot(page, 'encaje-escritorio')
  await page.getByRole('link', { name: es.vacancyDetail.back, exact: true }).click()
  await expect(page).toHaveURL(/q=java.*modalidad=remoto/)
  await expect(page.locator('article h3 a').first()).toBeFocused()
  await page.goto('/practicar')
  await page.getByRole('combobox', { name: new RegExp(t.questions) }).selectOption('1')
  await page.getByRole('checkbox', { name: t.voice, exact: true }).check()
  await page.getByRole('button', { name: t.start, exact: true }).click()
  await expect(page).toHaveURL(/\/practicar\//)
  await expect(page.getByText(t.simulated, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: t.start, exact: true }).click()
  await expect(page.getByLabel(t.answer, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: t.record, exact: true }).click()
  await page.waitForTimeout(1000)
  await page.getByRole('button', { name: t.stopRecording, exact: true }).click()
  await expect(page.getByText(t.allAnswered, { exact: true })).toBeVisible({ timeout: 30000 })
  await shot(page, 'dora-sesion-escritorio')
  await page.getByRole('button', { name: t.finish, exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: t.finish, exact: true }).click()
  await expect(page.getByText(t.closedSession, { exact: true })).toBeVisible()
  await page.goto('/perfil/github')
  await page.getByRole('button', { name: t.connect, exact: true }).click()
  await expect(page.getByRole('alert')).toContainText(t.errors['github-oauth-disabled'])
  await shot(page, 'github-configuracion-escritorio')
  await page.goto(vacancyPath!)
  await page.getByRole('link', { name: es.vacancyDetail.apply, exact: true }).click()
  await expect(page.getByText(t.applyDemo, { exact: true })).toBeVisible()
  await shot(page, 'postular-escritorio')
  await page.getByRole('button', { name: t.applyConfirm, exact: true }).click()
  await page.getByRole('link', { name: t.viewApplication, exact: true }).click()
  const applicationPath = new URL(page.url()).pathname
  await shot(page, 'postulacion-escritorio')
  await page.getByRole('link', { name: t.exam, exact: true }).click()
  await page.getByRole('checkbox', { name: t.acceptNotice, exact: true }).check()
  await page.getByRole('button', { name: t.prepareExam, exact: true }).click()
  await expect
    .poll(
      async () => {
        if (await page.getByRole('button', { name: t.startExam, exact: true }).count()) return true
        const refresh = page.getByRole('button', { name: t.refresh, exact: true })
        if (await refresh.count()) await refresh.click()
        return false
      },
      { timeout: 120000, intervals: [2000] },
    )
    .toBe(true)
  await page.getByRole('button', { name: t.startExam, exact: true }).click()
  await page.getByRole('radio').first().check()
  await page.getByRole('button', { name: t.save, exact: true }).click()
  await expect(page.getByText(t.answerSaved, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: '2', exact: true }).click()
  await page.getByRole('button', { name: '1', exact: true }).click()
  await expect(page.getByRole('radio').first()).toBeChecked()
  await shot(page, 'examen-escritorio')
  await page.getByRole('button', { name: '8', exact: true }).click()
  await page.getByRole('button', { name: t.run, exact: true }).click()
  await expect(
    page.locator('.workflow__row strong').filter({ hasText: /1\./ }).first(),
  ).toBeVisible({ timeout: 30000 })
  await shot(page, 'examen-codigo-escritorio')
  await page.getByRole('button', { name: t.submitExam, exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: t.submitExam, exact: true }).click()
  await expect(page.getByText(t.examDone, { exact: true })).toBeVisible()
  await page.getByRole('link', { name: t.interview, exact: true }).click()
  await page.getByRole('checkbox', { name: t.acceptInterview, exact: true }).check()
  await page.getByRole('button', { name: t.startInterview, exact: true }).click()
  await page.getByRole('button', { name: t.start, exact: true }).click()
  for (let n = 0; n < 5; n++) {
    const input = page.getByLabel(t.answer, { exact: true })
    await input.fill(
      'En un proyecto de estudio separé los requisitos, implementé la solución, ejecuté pruebas y documenté los resultados para que el equipo pudiera revisarlos.',
    )
    await page.getByRole('button', { name: t.send, exact: true }).click()
    await expect(page.getByRole('button', { name: t.finish, exact: true })).toBeEnabled()
    if (await page.getByText(t.allAnswered, { exact: true }).count()) break
  }
  await shot(page, 'entrevista-escritorio')
  await page.getByRole('button', { name: t.finish, exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: t.finish, exact: true }).click()
  await expect(page.getByText(t.closedSession, { exact: true })).toBeVisible()
  await expect(page.getByRole('main')).not.toContainText(t.feedback)
  for (const [route, name] of [
    ['/', 'inicio-estudiante'],
    ['/perfil?seccion=habilidades', 'perfil-habilidades'],
    ['/postulaciones', 'postulaciones'],
    [applicationPath, 'postulacion'],
    ['/perfil/cv', 'cv'],
    ['/practicar', 'dora'],
    ['/perfil/github', 'github'],
  ]) {
    await page.goto(route!)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    for (const width of [360, 768, 1280]) {
      await page.setViewportSize({ width, height: 900 })
      await reflow(page)
      if (width === 360) await shot(page, name + '-movil')
    }
  }
  expect(errors).toEqual([])
})
