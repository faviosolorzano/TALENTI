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
  actionTimeout: 20000,
  launchOptions: { args: ['--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream'] },
  permissions: ['microphone', 'camera'],
})
async function shot(page: Page, name: string) {
  mkdirSync(output, { recursive: true })
  if (!name.includes('carga')) await expect(page.locator('main [aria-busy="true"]')).toHaveCount(0)
  await page.evaluate(async () => {
    await document.fonts.ready
    window.scrollTo({ top: 0, behavior: 'instant' })
    await new Promise<void>((resolve) =>
      requestAnimationFrame(() => requestAnimationFrame(() => resolve())),
    )
  })
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
  const columns = await page.evaluate(() =>
    ['.hero__content', '.header__bar', '.featured .container'].map(
      (selector) => document.querySelector(selector)!.getBoundingClientRect().width,
    ),
  )
  expect(columns[0]).toBe(columns[2])
  expect(columns[1]).toBe(columns[2])
  await shot(page, 'inicio-escritorio')
  for (const [width, height] of [
    [320, 900],
    [360, 800],
    [390, 844],
    [667, 320],
    [768, 1024],
    [1024, 768],
    [1440, 1000],
  ]) {
    await page.setViewportSize({ width: width!, height: height! })
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
  await expect(page.locator('input[type=file]')).toBeAttached()
  await shot(page, 'cv-subir-escritorio')
  await page.setViewportSize({ width: 360, height: 800 })
  await reflow(page)
  await shot(page, 'cv-subir-movil')
  await page.setViewportSize({ width: 1440, height: 1000 })
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
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await shot(page, 'vacante-descripcion-escritorio')
  await page.setViewportSize({ width: 360, height: 800 })
  await reflow(page)
  await shot(page, 'vacante-descripcion-movil')
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.getByRole('button', { name: es.redesign.fitTab, exact: true }).click()
  await expect(page.locator('.comparison-row').first()).toBeVisible()
  await shot(page, 'encaje-escritorio')
  await page.getByRole('link', { name: es.vacancyDetail.back, exact: true }).click()
  await expect(page).toHaveURL(/q=java.*modalidad=remoto/)
  await expect(page.locator('article h3 a').first()).toBeFocused()
  await page.goto('/practicar')
  const privacyHeaders = await page.request.get('/')
  expect(privacyHeaders.headers()['permissions-policy']).toContain('camera=(self)')
  await page.getByRole('button', { name: es.doraCall.deviceCheck, exact: true }).click()
  await page.getByRole('button', { name: es.doraCall.cameraStart, exact: true }).click()
  await expect(page.getByText(es.doraCall.cameraOn, { exact: true })).toBeVisible()
  await expect
    .poll(() => page.locator('video').evaluate((video) => (video as HTMLVideoElement).videoWidth))
    .toBeGreaterThan(0)
  await page.getByRole('button', { name: es.doraCall.micStart, exact: true }).click()
  await expect(page.getByText(es.doraCall.signal, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: es.doraCall.tone, exact: true }).click()
  await expect(page.getByRole('button', { name: es.doraCall.heard, exact: true })).toBeVisible()
  await shot(page, 'dora-dispositivos-escritorio')
  await page.setViewportSize({ width: 360, height: 800 })
  await reflow(page)
  await shot(page, 'dora-dispositivos-movil')
  await page.getByRole('button', { name: es.doraCall.close, exact: true }).click()
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.getByRole('combobox', { name: new RegExp(t.questions) }).selectOption('1')
  await page.getByRole('checkbox', { name: t.voice, exact: true }).check()
  await page.getByRole('button', { name: t.start, exact: true }).click()
  await expect(page).toHaveURL(/\/practicar\//)
  await expect(page.getByText(t.simulated, { exact: true })).toBeVisible()
  await page.getByRole('button', { name: t.start, exact: true }).click()
  await expect(page.getByRole('button', { name: t.record, exact: true })).toBeVisible()
  await expect(page.getByLabel(t.answer, { exact: true })).toBeHidden()
  await shot(page, 'dora-conversacion-escritorio')
  await page.setViewportSize({ width: 360, height: 800 })
  await reflow(page)
  await shot(page, 'dora-conversacion-movil')
  const conversationAccess = await new AxeBuilder({ page })
    .withTags(['wcag2a', 'wcag2aa', 'wcag21aa'])
    .analyze()
  expect(
    conversationAccess.violations.filter(
      (item) => item.impact === 'serious' || item.impact === 'critical',
    ),
  ).toEqual([])
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.getByRole('button', { name: t.record, exact: true }).click()
  await page.waitForTimeout(1000)
  await page.getByRole('button', { name: t.stopRecording, exact: true }).click()
  await expect(page.getByText(t.allAnswered, { exact: true })).toBeVisible({ timeout: 30000 })
  await shot(page, 'dora-sesion-escritorio')
  await page.getByText(es.doraCall.transcript, { exact: true }).click()
  await expect(page.locator('.workflow__transcript').first()).toBeVisible()
  await shot(page, 'dora-transcripcion-escritorio')
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
  await page.setViewportSize({ width: 360, height: 900 })
  await reflow(page)
  await shot(page, 'examen-movil')
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.getByRole('button', { name: '8', exact: true }).click()
  const codeEditor = page.locator('textarea')
  if (!(await codeEditor.inputValue()).trim()) {
    const label = await codeEditor.locator('..').innerText()
    await codeEditor.fill(
      label.includes('SQL')
        ? 'SELECT 0 AS total;'
        : label.includes('PYTHON')
          ? 'print(0)'
          : label.includes('JAVASCRIPT')
            ? 'console.log(0)'
            : 'class Main {public static void main(String[] args){System.out.println(0);}}',
    )
  }

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
  const firstQuestion = await page
    .getByRole('heading', { level: 2, name: /Pregunta 1\// })
    .innerText()
  const actualQuestions = Number(firstQuestion.split('/')[1])
  await expect(page.getByLabel(t.answer, { exact: true })).toBeHidden()
  await shot(page, 'entrevista-conversacion-escritorio')
  await page.setViewportSize({ width: 360, height: 800 })
  await reflow(page)
  await shot(page, 'entrevista-conversacion-movil')
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.getByRole('button', { name: es.doraCall.write, exact: true }).click()
  for (let n = 0; n < actualQuestions; n++) {
    const input = page.getByLabel(t.answer, { exact: true })
    await input.fill(
      'En un proyecto de estudio separé los requisitos, implementé la solución, ejecuté pruebas y documenté los resultados para que el equipo pudiera revisarlos.',
    )
    await page.getByRole('button', { name: t.send, exact: true }).click()
    if (n + 1 < actualQuestions)
      await expect(
        page.getByRole('heading', {
          level: 2,
          name: `${t.question} ${n + 2}/${actualQuestions}`,
          exact: true,
        }),
      ).toBeVisible({ timeout: 30000 })
    else
      await expect(page.getByText(t.closedSession, { exact: true })).toBeVisible({ timeout: 30000 })
    if (await page.getByText(t.allAnswered, { exact: true }).count()) break
  }
  await shot(page, 'entrevista-escritorio')
  await page.setViewportSize({ width: 360, height: 900 })
  await reflow(page)
  await shot(page, 'entrevista-movil')
  await page.setViewportSize({ width: 1440, height: 1000 })
  if (!(await page.getByText(t.closedSession, { exact: true }).count())) {
    await page.getByRole('button', { name: t.finish, exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: t.finish, exact: true }).click()
  }
  await expect(page.getByText(t.closedSession, { exact: true })).toBeVisible()
  await expect(page.getByRole('main')).not.toContainText(t.feedback)
  for (const [route, name] of [
    ['/', 'inicio-estudiante'],
    ['/perfil?seccion=habilidades', 'perfil-habilidades'],
    ['/perfil?seccion=sobre-ti', 'perfil-personal'],
    ['/perfil?seccion=academico', 'perfil-academico'],
    ['/perfil?seccion=disponibilidad', 'perfil-disponibilidad'],
    ['/perfil?seccion=proyectos', 'perfil-proyectos'],
    ['/postulaciones', 'postulaciones'],
    [applicationPath, 'postulacion'],
    ['/perfil/cv', 'cv'],
    ['/practicar', 'dora'],
    ['/perfil/github', 'github'],
  ]) {
    await page.goto(route!)
    await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
    await expect(page.locator('main [aria-busy="true"]')).toHaveCount(0)
    for (const [width, height] of [
      [320, 900],
      [360, 800],
      [390, 844],
      [667, 320],
      [768, 1024],
      [1024, 768],
      [1440, 1000],
    ]) {
      await page.setViewportSize({ width: width!, height: height! })
      await reflow(page)
      if (width === 360) await shot(page, name + '-movil')
      if (width === 1440) await shot(page, name + '-escritorio')
    }
  }
  expect(errors).toEqual([])
})
