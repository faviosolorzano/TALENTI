import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { es } from '../../i18n/es'
import { SAMPLE_SUGGESTIONS, type FakeCvOptions } from '../../test/fakeCv'
import { clearCsrfCookie } from '../../test/fakeIdentity'
import { openStudentApp } from '../../test/studentApp'

afterEach(clearCsrfCookie)

const t = es.cv
const pdf = (name = 'cv-ana.pdf', size?: number) => {
  const file = new File(['%PDF-1.4 contenido inventado'], name, { type: 'application/pdf' })
  if (size !== undefined) Object.defineProperty(file, 'size', { value: size })
  return file
}

async function openCv(cv: FakeCvOptions = {}) {
  const app = openStudentApp('/perfil/cv', { cv })
  await screen.findByRole('heading', { level: 1, name: t.title })
  return app
}

/** Opens the reader with an analysis already READY and waits for the review. */
async function openReview(cv: FakeCvOptions = {}) {
  const app = await openCv({ open: {}, ...cv })
  await screen.findByRole('heading', { level: 2, name: t.review.title })
  return app
}

const row = (name: string | RegExp) =>
  screen
    .getAllByTestId('suggestion')
    .find((item) => within(item).queryByText(name) !== null) as HTMLElement
const choose = async (file: File) =>
  fireEvent.change(await screen.findByTestId('cv-file'), { target: { files: [file] } })

describe('Entry from /perfil (009-19, 009-36)', () => {
  it('009-19: the CV block on /perfil is now an entry to /perfil/cv, next to the manual profile', async () => {
    openStudentApp('/perfil')
    const block = await screen.findByRole('region', { name: es.profile.cv.title })
    expect(within(block).getByRole('link', { name: es.profile.cv.action })).toHaveAttribute(
      'href',
      '/perfil/cv',
    )
    expect(screen.getByRole('region', { name: es.profile.academic.title })).toBeInTheDocument()
  })
})

describe('Upload step (009-20, 009-37)', () => {
  it('009-37: the information line sits by "Subir mi CV"; no checkbox and no permission asked', async () => {
    const { cv } = await openCv()
    await screen.findByRole('button', { name: t.upload.button })
    expect(screen.getByText(t.upload.info)).toBeInTheDocument()
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
    expect(cv.requests.some((request) => request.includes('consents'))).toBe(false)
    expect(screen.getByRole('link', { name: t.manual })).toHaveAttribute('href', '/perfil')
  })

  it('009-25: opening the page only asks for the state, it never reads or uploads anything', async () => {
    const { cv } = await openCv()
    await screen.findByRole('button', { name: t.upload.button })
    expect(cv.requests.every((request) => request.startsWith('GET '))).toBe(true)
  })

  it('009-20: a file that is not a PDF is rejected before sending, with a way to do it by hand', async () => {
    const { cv } = await openCv()
    await choose(new File(['hola'], 'cv.docx', { type: 'application/msword' }))
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(t.errors.notPdf)
    expect(within(alert).getByRole('link', { name: t.manual })).toHaveAttribute('href', '/perfil')
    expect(cv.uploads).toHaveLength(0)
  })

  it('009-20: a PDF over 2 MB is rejected before sending', async () => {
    const { cv } = await openCv()
    await choose(pdf('grande.pdf', 3 * 1024 * 1024))
    expect(await screen.findByRole('alert')).toHaveTextContent(t.errors.tooLarge)
    expect(cv.uploads).toHaveLength(0)
  })

  it.each([
    ['NOT_A_PDF', t.errors.NOT_A_PDF],
    ['CORRUPT', t.errors.CORRUPT],
    ['ENCRYPTED', t.errors.ENCRYPTED],
    ['TOO_MANY_PAGES', t.errors.TOO_MANY_PAGES],
    ['NO_TEXT', t.errors.NO_TEXT],
  ])(
    '009-05: the reason %s of a 422 is told in plain words, with the manual way out',
    async (reason, message) => {
      await openCv({ uploadError: { status: 422, code: 'cv-unreadable', extra: { reason } } })
      await choose(pdf())
      const alert = await screen.findByRole('alert')
      expect(alert).toHaveTextContent(message)
      expect(within(alert).getByRole('link', { name: t.manual })).toBeInTheDocument()
    },
  )

  it('009-07: a 429 limit and a server 413 get their own words', async () => {
    await openCv({ uploadError: { status: 429, code: 'cv-limit-reached' } })
    await choose(pdf())
    expect(await screen.findByRole('alert')).toHaveTextContent(t.errors.limit)
  })

  it('009-20: after uploading it says "Analizando tu CV…" in a status region and then shows the review', async () => {
    const { cv } = await openCv({ pollsUntilReady: 1 })
    await choose(pdf())
    const status = await screen.findByText(t.analyzing.analyzing)
    expect(status).toHaveAttribute('role', 'status')
    expect(screen.getByText(t.analyzing.note)).toBeInTheDocument()
    expect(cv.uploads).toEqual([
      expect.objectContaining({ name: 'cv-ana.pdf', type: 'application/pdf', mode: 'BASIC' }),
    ])
    await screen.findByRole('heading', { level: 2, name: t.review.title }, { timeout: 9000 })
  }, 15000)

  it('009-20: polling is soft and stops while the tab is hidden', async () => {
    const { cv } = await openCv({
      open: { status: 'ANALYZING', suggestions: [] },
      pollsUntilReady: 99,
    })
    await screen.findByText(t.analyzing.analyzing)
    const asked = () =>
      cv.requests.filter((request) => request === 'GET /cv/analyses/current').length
    Object.defineProperty(document, 'visibilityState', { configurable: true, get: () => 'hidden' })
    try {
      const before = asked()
      // Longer than the first interval: a visible tab would have asked again by now.
      await new Promise((resolve) => setTimeout(resolve, 2600))
      expect(asked()).toBe(before)
    } finally {
      Reflect.deleteProperty(document, 'visibilityState')
    }
  }, 10000)

  it('009-20: coming back later resumes the open analysis instead of asking for a new upload', async () => {
    await openCv({ open: { status: 'ANALYZING', suggestions: [] }, pollsUntilReady: 99 })
    expect(await screen.findByText(t.analyzing.analyzing)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: t.upload.button })).not.toBeInTheDocument()
  })

  it('009-20: a failed state loads with an alert and "Reintentar" asks again', async () => {
    const { cv, user } = await openCv({ currentFails: true })
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(t.loadError)
    cv.setCurrentFails(false)
    await user.click(within(alert).getByRole('button', { name: es.common.retry }))
    expect(await screen.findByRole('button', { name: t.upload.button })).toBeInTheDocument()
  })

  it('009-20: while it loads there is a skeleton announced as busy', async () => {
    openStudentApp('/perfil/cv', { cv: { readDelayMs: 200 } })
    const heading = await screen.findByRole('heading', { level: 1, name: t.title })
    expect(heading).toBeInTheDocument()
    expect(document.querySelector('[aria-busy="true"] .skeleton')).not.toBeNull()
    expect(screen.getByText(t.loading)).toBeInTheDocument()
    await screen.findByRole('button', { name: t.upload.button })
  })
})

describe('Failed and empty readings (009-20)', () => {
  it('FAILED says why, offers "Reintentar" and the manual way, and retrying reads again', async () => {
    const { cv, user } = await openCv({
      open: { status: 'FAILED', failureReason: 'TIMEOUT', suggestions: [] },
      pollsUntilReady: 0,
    })
    expect(await screen.findByText(t.failed.title)).toBeInTheDocument()
    expect(screen.getByText(t.failed.TIMEOUT)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: t.manual })).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: es.common.retry }))
    await waitFor(() => expect(cv.requests).toContain('POST /cv/analyses/a1/reads'))
    expect(cv.bodies.at(-1)?.body).toEqual({ mode: 'BASIC' })
  })

  it('READY without suggestions is not an error: "No encontramos datos para sugerir" leads to /perfil', async () => {
    await openCv({ open: { suggestions: [] } })
    expect(await screen.findByText(t.empty.title)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: t.empty.toProfile })).toHaveAttribute('href', '/perfil')
  })
})

describe('Review (009-21, 009-22)', () => {
  it('009-21: groups Estudios, Habilidades and Proyectos; each shows its CV phrase and the current profile value', async () => {
    await openReview()
    for (const group of Object.values(t.review.groups)) {
      expect(screen.getByRole('heading', { level: 3, name: group })).toBeInTheDocument()
    }
    expect(screen.getByText(/Cursando el 5\.º ciclo/)).toBeInTheDocument()
    expect(screen.getByText(t.review.current('3'))).toBeInTheDocument()
    expect(screen.getByText(t.review.youDecide)).toBeInTheDocument()
    // Everything starts undecided.
    expect(screen.getAllByText(t.review.state.none)).toHaveLength(SAMPLE_SUGGESTIONS.length)
  })

  it('009-21: a reading by rules says so and does NOT carry the AI label', async () => {
    await openReview()
    expect(screen.getByText(t.review.rulesLabel)).toBeInTheDocument()
    expect(screen.queryByText(es.ai.label)).not.toBeInTheDocument()
  })

  it('009-21: a reading by AI carries the AI label on the block', async () => {
    await openReview({ open: { source: 'AI', level: 'ENHANCED' } })
    expect(screen.getByText(t.review.aiNote)).toBeInTheDocument()
    expect(screen.getAllByText(es.ai.label).length).toBeGreaterThanOrEqual(1)
    expect(screen.queryByText(t.review.rulesLabel)).not.toBeInTheDocument()
  })

  it('009-21: a skill needs a level to be accepted; with it, it is accepted', async () => {
    const { user } = await openReview()
    const java = row('Java')
    await user.click(within(java).getByRole('button', { name: t.review.acceptLabel('Java') }))
    expect(within(java).getByRole('alert')).toHaveTextContent(t.review.levelMissing)
    expect(within(java).getByText(t.review.state.none)).toBeInTheDocument()

    await user.click(within(java).getByRole('radio', { name: es.profile.skills.levels[3] }))
    await user.click(within(java).getByRole('button', { name: t.review.acceptLabel('Java') }))
    expect(within(java).getByText(t.review.state.ACCEPT)).toBeInTheDocument()
    expect(
      within(java).getByRole('button', { name: t.review.acceptLabel('Java') }),
    ).toHaveAttribute('aria-pressed', 'true')
  })

  it('009-21: a skill "sin asociar" must be linked to the catalog before it can be accepted', async () => {
    const { user } = await openReview()
    const kotlin = row('Kotlin')
    expect(within(kotlin).getByText(t.review.unlinked)).toBeInTheDocument()
    await user.click(within(kotlin).getByRole('radio', { name: es.profile.skills.levels[1] }))
    await user.click(within(kotlin).getByRole('button', { name: t.review.acceptLabel('Kotlin') }))
    expect(within(kotlin).getByRole('alert')).toHaveTextContent(t.review.slugMissing)

    // The catalog search opens; the person links it to a catalog skill.
    await user.type(await within(kotlin).findByRole('searchbox'), 'java')
    await user.click(await within(kotlin).findByRole('button', { name: 'Java' }))
    expect(within(kotlin).getByText(t.review.state.EDIT)).toBeInTheDocument()
  })

  it('009-21: editing a study lets the person change the value and marks it as edited', async () => {
    const { user } = await openReview()
    const career = row('Desarrollo de Software')
    await user.click(
      within(career).getByRole('button', { name: t.review.editLabel('Desarrollo de Software') }),
    )
    const field = within(career).getByLabelText(t.review.fields.CAREER)
    await user.clear(field)
    await user.type(field, 'Diseño Web')
    await user.click(within(career).getByRole('button', { name: t.review.editDone }))
    expect(within(career).getByText('Diseño Web')).toBeInTheDocument()
    expect(within(career).getByText(t.review.state.EDIT)).toBeInTheDocument()
  })

  it('009-22: "Guardar" counts only what was accepted, warns about the undecided and confirms ONCE', async () => {
    const { user, cv } = await openReview()
    const save = screen.getByRole('button', { name: t.review.save(0) })
    expect(save).toBeDisabled()
    expect(screen.getByText(t.review.saveNone)).toBeInTheDocument()

    await user.click(
      within(row('Desarrollo de Software')).getByRole('button', {
        name: t.review.acceptLabel('Desarrollo de Software'),
      }),
    )
    const java = row('Java')
    await user.click(within(java).getByRole('radio', { name: es.profile.skills.levels[3] }))
    await user.click(within(java).getByRole('button', { name: t.review.acceptLabel('Java') }))
    await user.click(
      within(row('Kotlin')).getByRole('button', { name: t.review.discardLabel('Kotlin') }),
    )

    expect(screen.getByRole('button', { name: t.review.save(2) })).toBeEnabled()
    expect(screen.getByText(t.review.undecided(2))).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: t.review.save(2) }))
    expect(await screen.findByRole('heading', { level: 2, name: t.done.title })).toBeInTheDocument()
    // The percentage is the one the server answered (studies 15 is not done: career only).
    expect(
      screen.getByRole('progressbar', { name: es.profile.summary.barLabel }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: t.done.viewProfile })).toHaveAttribute(
      'href',
      '/perfil',
    )
    expect(screen.getByRole('link', { name: t.done.keepGoing })).toHaveAttribute(
      'href',
      expect.stringContaining('/perfil'),
    )

    const confirms = cv.bodies.filter((body) => body.path.endsWith('/confirm'))
    expect(confirms).toHaveLength(1)
    expect(confirms[0]?.body).toEqual({
      decisions: [
        { suggestionId: 's-career', action: 'ACCEPT' },
        { suggestionId: 's-java', action: 'ACCEPT', value: { slug: 'java', level: 3 } },
        { suggestionId: 's-kotlin', action: 'DISCARD' },
      ],
    })
  })

  it('009-22: a 422 on a field points at that suggestion and keeps every decision', async () => {
    const { user, cv } = await openReview()
    const java = row('Java')
    await user.click(within(java).getByRole('radio', { name: es.profile.skills.levels[1] }))
    await user.click(within(java).getByRole('button', { name: t.review.acceptLabel('Java') }))
    // Forces the server rule: a level is missing in the body it receives.
    cv.bodies.length = 0
    const original = globalThis.fetch
    vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/confirm') && typeof init?.body === 'string') {
        const body = JSON.parse(init.body) as { decisions: Array<{ value?: { level?: number } }> }
        for (const decision of body.decisions) if (decision.value) delete decision.value.level
        return original(input, { ...init, body: JSON.stringify(body) })
      }
      return original(input, init)
    })

    await user.click(screen.getByRole('button', { name: t.review.save(1) }))
    expect(await within(java).findByText('Elige un nivel.')).toBeInTheDocument()
    expect(screen.getAllByRole('alert').some((a) => a.textContent === t.review.reviewFields)).toBe(
      true,
    )
    expect(within(java).getByText(t.review.state.ACCEPT)).toBeInTheDocument()
  })

  it('009-22: a server failure shows an alert and "Reintentar" saves again without losing decisions', async () => {
    const { user } = await openReview({ confirmFailsOnce: 500 })
    await user.click(
      within(row('Desarrollo de Software')).getByRole('button', {
        name: t.review.acceptLabel('Desarrollo de Software'),
      }),
    )
    await user.click(screen.getByRole('button', { name: t.review.save(1) }))
    expect(await screen.findByText(t.review.saveFailed)).toBeInTheDocument()
    expect(
      within(row('Desarrollo de Software')).getByText(t.review.state.ACCEPT),
    ).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: es.common.retry }))
    expect(await screen.findByRole('heading', { level: 2, name: t.done.title })).toBeInTheDocument()
  })
})

describe('Reading with AI (009-27, 009-28, 009-35)', () => {
  it('009-35: with the AI off there is no "Leer mejor con IA" and nothing about it', async () => {
    await openReview()
    expect(screen.queryByRole('button', { name: t.enhanced.button })).not.toBeInTheDocument()
    expect(screen.queryByText(/lecturas con IA/)).not.toBeInTheDocument()
  })

  it('009-35: when the server offers it, the button shows the AI label and the quota left', async () => {
    await openReview({ usage: { available: true, reason: null, used: 1 } })
    expect(screen.getByRole('button', { name: t.enhanced.button })).toBeInTheDocument()
    expect(screen.getByText(t.upload.enhancedLeft(2, 3))).toBeInTheDocument()
  })

  it('009-27: asking for it first says the text goes to an external AI service, then reads', async () => {
    const { user, cv } = await openReview({
      usage: { available: true, reason: null, used: 0 },
    })
    await user.click(screen.getByRole('button', { name: t.enhanced.button }))
    expect(screen.getByText(t.upload.enhancedSend)).toBeInTheDocument()
    expect(cv.requests.some((request) => request.endsWith('/reads'))).toBe(false)
    await user.click(screen.getByRole('button', { name: t.enhanced.confirmYes }))
    await waitFor(() => expect(cv.bodies.at(-1)).toMatchObject({ body: { mode: 'ENHANCED' } }))
  })

  it('009-28: with the monthly quota used up the upload says when it renews and basic stays', async () => {
    await openCv({ usage: { available: false, reason: 'PERSONAL_LIMIT', used: 3 } })
    await screen.findByRole('button', { name: t.upload.button })
    expect(
      screen.getByText(/Ya usaste tus 3 lecturas con IA de este mes; se renuevan el/),
    ).toBeInTheDocument()
    expect(screen.getByRole('radio', { name: new RegExp(t.upload.basic) })).toBeChecked()
    expect(screen.getByRole('radio', { name: new RegExp(t.upload.enhanced) })).toBeDisabled()
  })

  it('009-35: the upload offers the AI reading with its label and sends the chosen mode', async () => {
    const { user, cv } = await openCv({
      usage: { available: true, reason: null, used: 0 },
      pollsUntilReady: 0,
    })
    await user.click(await screen.findByRole('radio', { name: new RegExp(t.upload.enhanced) }))
    expect(screen.getByText(t.upload.enhancedSend)).toBeInTheDocument()
    await choose(pdf())
    await waitFor(() => expect(cv.uploads[0]?.mode).toBe('ENHANCED'))
  })

  it('009-29: a degraded reading explains it without alarm (no alert role)', async () => {
    await openReview({ open: { degraded: true, degradedReason: 'GLOBAL_LIMIT' } })
    const notice = screen.getByText(t.review.degraded.GLOBAL_LIMIT)
    expect(notice.closest('[role="alert"]')).toBeNull()
  })

  it('009-26 and 009-30: tells about a reused file, a long CV read by AI and what is already in the profile', async () => {
    await openReview({
      open: { reused: true, source: 'AI', inputTruncated: true, alreadyInProfile: 2 },
    })
    expect(screen.getByText(t.review.reused)).toBeInTheDocument()
    expect(screen.getByText(t.review.truncated)).toBeInTheDocument()
    expect(screen.getByText(t.review.alreadyInProfile(2))).toBeInTheDocument()
  })
})

describe('Delete the CV (009-16)', () => {
  it('asks first, says the profile does not change, and then goes back to the upload', async () => {
    const { user, cv } = await openReview()
    await user.click(screen.getByRole('button', { name: t.discard.button }))
    expect(screen.getByText(t.discard.body)).toBeInTheDocument()
    expect(cv.requests.some((request) => request.startsWith('DELETE'))).toBe(false)
    await user.click(screen.getByRole('button', { name: t.discard.yes }))
    expect(await screen.findByRole('button', { name: t.upload.button })).toBeInTheDocument()
    expect(cv.requests).toContain('DELETE /cv/analyses/a1')
  })

  it('"Cancelar" leaves everything as it was', async () => {
    const { user, cv } = await openReview()
    await user.click(screen.getByRole('button', { name: t.discard.button }))
    await user.click(screen.getByRole('button', { name: t.discard.cancel }))
    expect(screen.getByRole('button', { name: t.discard.button })).toBeInTheDocument()
    expect(cv.requests.some((request) => request.startsWith('DELETE'))).toBe(false)
  })
})

describe('Privacy of the content (009-18, RNF-19)', () => {
  it('the CV and its suggestions are never written to the browser storage or the console', async () => {
    const spies = (['log', 'info', 'warn', 'error', 'debug'] as const).map((method) =>
      vi.spyOn(console, method).mockImplementation(() => undefined),
    )
    const { user } = await openReview()
    await user.click(
      within(row('Desarrollo de Software')).getByRole('button', {
        name: t.review.acceptLabel('Desarrollo de Software'),
      }),
    )
    await user.click(screen.getByRole('button', { name: t.review.save(1) }))
    await screen.findByRole('heading', { level: 2, name: t.done.title })
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    for (const spy of spies) expect(spy).not.toHaveBeenCalled()
    spies.forEach((spy) => spy.mockRestore())
  })
})
