import { screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { es } from '../../i18n/es'
import type { FakeApplicationsOptions } from '../../test/fakeApplications'
import { VACANCY_IDS } from '../../test/fakeBackend'
import { clearCsrfCookie } from '../../test/fakeIdentity'
import { openStudentApp } from '../../test/studentApp'

afterEach(clearCsrfCookie)

const t = es.applications
const FIRST = VACANCY_IDS[0] as string // Practicante Backend Java, Andes Tech
const SECOND = VACANCY_IDS[1] as string
const TITLE = 'Practicante Backend Java'

async function openVacancy(applications: FakeApplicationsOptions = {}, role?: 'COMPANY') {
  const app = openStudentApp(`/oportunidades/${FIRST}`, { applications, role })
  await screen.findByRole('heading', { level: 1, name: TITLE })
  return app
}

describe('Apply from the vacancy detail (008-14 to 008-17)', () => {
  it('008-14: a student with an open vacancy sees "Postular" active', async () => {
    await openVacancy()
    expect(await screen.findByRole('button', { name: es.vacancyDetail.apply })).toBeEnabled()
  })

  it('008-14: a company account is told it cannot apply and gets no button', async () => {
    await openVacancy({}, 'COMPANY')
    expect(await screen.findByText(t.card.notAllowed)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: es.vacancyDetail.apply })).not.toBeInTheDocument()
  })

  it('008-15: the dialog is modal, takes the focus in its title and says what is and is not shared', async () => {
    const { user } = await openVacancy()
    await user.click(await screen.findByRole('button', { name: es.vacancyDetail.apply }))

    const dialog = await screen.findByRole('dialog', { name: t.apply.title(TITLE) })
    expect(dialog).toHaveAttribute('aria-modal', 'true')
    expect(within(dialog).getByRole('heading', { name: t.apply.title(TITLE) })).toHaveFocus()
    expect(within(dialog).getByText(t.apply.company('Andes Tech'))).toBeInTheDocument()
    expect(within(dialog).getByText(t.apply.shared)).toBeInTheDocument()
    expect(within(dialog).getByText(t.apply.notShared)).toBeInTheDocument()
    expect(within(dialog).getByText(t.finalDecision)).toBeInTheDocument()
    // Amendment 1: no "example" or "test" warnings anywhere in the application.
    expect(dialog).not.toHaveTextContent(/ejemplo|prueba|ficticia/i)
  })

  it('008-16: a profile below 100 % gets a neutral note with a link to complete it, and can still apply', async () => {
    const { user } = await openVacancy()
    await user.click(await screen.findByRole('button', { name: es.vacancyDetail.apply }))
    const dialog = await screen.findByRole('dialog')

    expect(await within(dialog).findByText(/Tu perfil está al 0 %/)).toBeInTheDocument()
    expect(within(dialog).getByRole('link', { name: t.apply.completeProfile })).toHaveAttribute(
      'href',
      '/perfil',
    )
    expect(within(dialog).getByRole('button', { name: t.apply.confirm })).toBeEnabled()
  })

  it('008-15: Esc cancels and the focus goes back to "Postular"', async () => {
    const { user } = await openVacancy()
    const apply = await screen.findByRole('button', { name: es.vacancyDetail.apply })
    await user.click(apply)
    await screen.findByRole('dialog')
    await user.keyboard('{Escape}')

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(apply).toHaveFocus()
  })

  it('008-17: confirming shows "Enviando…", closes, announces it and turns the card into "Postulaste el…"', async () => {
    const { user, applications } = await openVacancy({ submitDelayMs: 100 })
    await user.click(await screen.findByRole('button', { name: es.vacancyDetail.apply }))
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText(/Tu perfil está al/)
    await user.click(within(dialog).getByRole('button', { name: t.apply.confirm }))

    expect(await within(dialog).findByRole('button', { name: t.apply.sending })).toBeDisabled()
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    const applyCard = screen.getByRole('region', { name: es.vacancyDetail.apply })
    expect(await within(applyCard).findByText(t.card.sent)).toBeInTheDocument()
    expect(within(applyCard).getByText(/Postulaste el/)).toBeInTheDocument()
    expect(within(applyCard).getByText(t.stages.SUBMITTED)).toBeInTheDocument()
    expect(within(applyCard).getByRole('link', { name: t.card.view })).toHaveAttribute(
      'href',
      '/postulaciones/app-1',
    )
    expect(applications.requests.filter((r) => r === 'POST /applications')).toHaveLength(1)
    expect(within(applyCard).queryByRole('button', { name: es.vacancyDetail.apply })).toBeNull()
  })

  it('008-17: "already exists" and "closed" are explained in words', async () => {
    const first = await openVacancy({
      submitFails: { status: 409, code: 'application-already-exists' },
    })
    await first.user.click(await screen.findByRole('button', { name: es.vacancyDetail.apply }))
    await first.user.click(await screen.findByRole('button', { name: t.apply.confirm }))
    expect(await screen.findByText(t.card.alreadyApplied)).toBeInTheDocument()
  })

  it('008-17: a vacancy that closed meanwhile says so', async () => {
    const { user } = await openVacancy({ submitFails: { status: 409, code: 'vacancy-closed' } })
    await user.click(await screen.findByRole('button', { name: es.vacancyDetail.apply }))
    await user.click(await screen.findByRole('button', { name: t.apply.confirm }))
    expect(await screen.findByText(t.card.closed)).toBeInTheDocument()
  })

  it('008-17: a server error shows an alert, keeps the dialog open and "Reintentar" sends again', async () => {
    const { user } = await openVacancy({ submitFailsOnce: { status: 500, code: 'internal-error' } })
    await user.click(await screen.findByRole('button', { name: es.vacancyDetail.apply }))
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByText(/Tu perfil está al/)
    await user.click(within(dialog).getByRole('button', { name: t.apply.confirm }))

    expect(await within(dialog).findByRole('alert')).toHaveTextContent(t.apply.sendError)
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: es.common.retry }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(await screen.findByText(t.card.sent)).toBeInTheDocument()
  })

  it('008-14: after a withdrawal it says so and offers "Postular de nuevo"', async () => {
    await openVacancy({ existing: [{ vacancyId: FIRST, status: 'WITHDRAWN' }] })
    expect(await screen.findByText(/Retiraste tu postulación el/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: t.card.applyAgain })).toBeEnabled()
  })

  it('008-14: with an active application it shows the date, the state and the link', async () => {
    await openVacancy({ existing: [{ vacancyId: FIRST }] })
    expect(await screen.findByText(/Postulaste el/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: t.card.view })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: es.vacancyDetail.apply })).not.toBeInTheDocument()
  })

  it('a failed check shows an alert with "Reintentar" instead of a wrong button', async () => {
    const { applications, user } = await openVacancy({ listFails: true })
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(t.card.loadError)
    applications.setListFails(false)
    await user.click(within(alert).getByRole('button', { name: es.common.retry }))
    expect(await screen.findByRole('button', { name: es.vacancyDetail.apply })).toBeEnabled()
  })
})

describe('Page /postulaciones (008-18)', () => {
  it('empty: says so and offers "Ver oportunidades"', async () => {
    openStudentApp('/postulaciones')
    expect(await screen.findByRole('heading', { level: 1, name: t.title })).toBeInTheDocument()
    expect(await screen.findByText(t.empty)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: t.seeOpportunities })).toHaveAttribute(
      'href',
      '/oportunidades',
    )
  })

  it('loading: a skeleton announced as busy', async () => {
    openStudentApp('/postulaciones', { applications: { listDelayMs: 150 } })
    const heading = await screen.findByRole('heading', { level: 1, name: t.title })
    expect(heading.closest('[aria-busy="true"]')).not.toBeNull()
    await screen.findByText(t.empty)
  })

  it('lists newest first with title as the only link, company, state in words and the date', async () => {
    openStudentApp('/postulaciones', {
      applications: {
        existing: [{ vacancyId: FIRST }, { vacancyId: SECOND, status: 'WITHDRAWN' }],
      },
    })
    const list = await screen.findByRole('list', { name: t.listLabel })
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    // The second application was made later, so it is the first one shown.
    expect(within(items[0] as HTMLElement).getByText(t.stages.WITHDRAWN)).toBeInTheDocument()
    expect(within(items[1] as HTMLElement).getByText(t.stages.SUBMITTED)).toBeInTheDocument()
    expect(within(items[1] as HTMLElement).getAllByRole('link')).toHaveLength(1)
    expect(within(items[1] as HTMLElement).getByRole('link', { name: TITLE })).toHaveAttribute(
      'href',
      '/postulaciones/app-1',
    )
    expect(within(items[1] as HTMLElement).getByText(/Andes Tech/)).toBeInTheDocument()
  })

  it('error: an alert with "Reintentar"', async () => {
    const { applications, user } = openStudentApp('/postulaciones', {
      applications: { listFails: true },
    })
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(t.loadError)
    applications.setListFails(false)
    await user.click(within(alert).getByRole('button', { name: es.common.retry }))
    expect(await screen.findByText(t.empty)).toBeInTheDocument()
  })

  it('008-21: "Mis postulaciones" in the menu is an active link', async () => {
    openStudentApp('/postulaciones')
    await screen.findByRole('heading', { level: 1, name: t.title })
    const link = screen.getByRole('link', { name: es.nav.myApplications })
    expect(link).toHaveAttribute('href', '/postulaciones')
  })
})

describe('Page /postulaciones/:id (008-19, 008-20)', () => {
  const open = (applications: FakeApplicationsOptions = { existing: [{ vacancyId: FIRST }] }) =>
    openStudentApp('/postulaciones/app-1', { applications })

  it('shows the vacancy, the state, the line of stages and the two reassurance phrases', async () => {
    open()
    expect(await screen.findByRole('heading', { level: 1, name: TITLE })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: t.detail.viewVacancy })).toHaveAttribute(
      'href',
      `/oportunidades/${FIRST}`,
    )
    const stages = within(screen.getByRole('region', { name: t.detail.process })).getAllByRole(
      'listitem',
    )
    expect(stages.map((stage) => stage.querySelector('span')?.textContent)).toEqual(['Enviada'])
    expect(screen.getByText(t.finalDecision)).toBeInTheDocument()
    expect(screen.getByText(t.practicePrivate)).toBeInTheDocument()
  })

  it('a withdrawn application shows "Enviada" then "Retirada" and no future stages or button', async () => {
    open({ existing: [{ vacancyId: FIRST, status: 'WITHDRAWN' }] })
    await screen.findByRole('heading', { level: 1, name: TITLE })
    const stages = within(screen.getByRole('region', { name: t.detail.process })).getAllByRole(
      'listitem',
    )
    expect(stages.map((stage) => stage.querySelector('span')?.textContent)).toEqual([
      'Enviada',
      'Retirada',
    ])
    expect(screen.queryByRole('button', { name: t.detail.withdraw })).not.toBeInTheDocument()
  })

  it('008-21: an unknown state never invents a hiring stage', async () => {
    open({ existing: [{ vacancyId: FIRST, status: 'FUTURE_UNKNOWN' }] })
    await screen.findByRole('heading', { level: 1, name: TITLE })
    expect(screen.getAllByText(t.stages.unknown).length).toBeGreaterThan(0)
  })

  it('008-20: withdrawing asks first, then the page becomes "Retirada" and announces it', async () => {
    const { user } = open()
    await user.click(await screen.findByRole('button', { name: t.detail.withdraw }))
    const dialog = await screen.findByRole('dialog', { name: t.withdraw.title(TITLE) })
    expect(within(dialog).getByText(t.withdraw.body)).toBeInTheDocument()
    await user.click(within(dialog).getByRole('button', { name: t.withdraw.yes }))

    expect(await screen.findByText(t.detail.withdrawn)).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: t.detail.withdraw })).not.toBeInTheDocument()
    expect(screen.getAllByText(t.stages.WITHDRAWN).length).toBeGreaterThan(0)
  })

  it('008-20: "No, mantenerla" changes nothing', async () => {
    const { user, applications } = open()
    await user.click(await screen.findByRole('button', { name: t.detail.withdraw }))
    await user.click(await screen.findByRole('button', { name: t.withdraw.keep }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(applications.requests.some((r) => r.endsWith('/withdraw'))).toBe(false)
  })

  it('008-20: an error shows an alert and "Reintentar" withdraws', async () => {
    const { user } = open({
      existing: [{ vacancyId: FIRST }],
      withdrawFailsOnce: { status: 500, code: 'internal-error' },
    })
    await user.click(await screen.findByRole('button', { name: t.detail.withdraw }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: t.withdraw.yes }))
    expect(await within(dialog).findByRole('alert')).toHaveTextContent(t.withdraw.error)
    await user.click(within(dialog).getByRole('button', { name: es.common.retry }))
    expect(await screen.findByText(t.detail.withdrawn)).toBeInTheDocument()
  })

  it('a missing application says so with a way back to the list', async () => {
    openStudentApp('/postulaciones/no-existe')
    expect(
      await screen.findByRole('heading', { level: 1, name: t.detail.notFoundTitle }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: t.detail.notFoundLink })).toHaveAttribute(
      'href',
      '/postulaciones',
    )
  })

  it('nothing about the applications is written to the browser storage', async () => {
    const { user } = open()
    await user.click(await screen.findByRole('button', { name: t.detail.withdraw }))
    await user.click(await screen.findByRole('button', { name: t.withdraw.yes }))
    await screen.findByText(t.detail.withdrawn)
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
  })
})

describe('Home block "Tus postulaciones" (005-12, 008-21)', () => {
  const home = es.home.applications

  it('without applications it says so and offers the vacancies', async () => {
    openStudentApp('/')
    const block = await screen.findByRole('region', { name: home.title })
    expect(await within(block).findByText(home.none)).toBeInTheDocument()
    expect(within(block).getByRole('link', { name: home.seeOpportunities })).toBeInTheDocument()
    expect(within(block).queryByText(es.home.soon)).not.toBeInTheDocument()
  })

  it('with applications it shows the active count, the latest and the link to all', async () => {
    openStudentApp('/', {
      applications: { existing: [{ vacancyId: FIRST }, { vacancyId: SECOND }] },
    })
    const block = await screen.findByRole('region', { name: home.title })
    expect(await within(block).findByText(home.active(2))).toBeInTheDocument()
    expect(within(block).getByRole('link', { name: home.seeAll })).toHaveAttribute(
      'href',
      '/postulaciones',
    )
    expect(within(block).getByText(home.latest, { exact: false })).toBeInTheDocument()
  })
})
