import { screen, within } from '@testing-library/react'
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

describe('Review before applying (008-14 to 008-17, redesign E5)', () => {
  async function review(options: FakeApplicationsOptions = {}) {
    const app = openStudentApp(`/oportunidades/${FIRST}/postular`, { applications: options })
    await screen.findByRole('button', { name: es.workflows.applyConfirm })
    return app
  }
  it('008-14: an open vacancy leads to a dedicated review', async () => {
    await openVacancy()
    expect(await screen.findByRole('link', { name: es.vacancyDetail.apply })).toHaveAttribute(
      'href',
      `/oportunidades/${FIRST}/postular`,
    )
  })
  it('008-14: company accounts cannot apply', async () => {
    await openVacancy({}, 'COMPANY')
    expect(await screen.findByText(t.card.notAllowed)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: es.vacancyDetail.apply })).toBeNull()
  })
  it('008-15: identifies the vacancy and current profile, with an honest demonstration notice', async () => {
    await review()
    expect(
      screen.getByRole('heading', { level: 1, name: es.workflows.applyTitle }),
    ).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2, name: TITLE })).toBeInTheDocument()
    expect(screen.getByText('Andes Tech')).toBeInTheDocument()
    expect(screen.getByText(es.workflows.applyDemo)).toBeInTheDocument()
    expect(screen.queryByRole('dialog')).toBeNull()
  })
  it('008-16: an incomplete profile can apply and its correction preserves the chosen vacancy', async () => {
    await review()
    expect(screen.getByRole('button', { name: es.workflows.applyConfirm })).toBeEnabled()
    expect(screen.getByRole('link', { name: es.workflows.editProfile })).toHaveAttribute(
      'href',
      expect.stringContaining('returnTo=' + encodeURIComponent(`/oportunidades/${FIRST}/postular`)),
    )
  })
  it('008-15: returning to the vacancy does not submit', async () => {
    const { user, applications } = await review()
    await user.click(screen.getByRole('link', { name: es.workflows.back }))
    await screen.findByRole('heading', { level: 1, name: TITLE })
    expect(applications.requests.filter((r) => r === 'POST /applications')).toHaveLength(0)
  })
  it('008-17: double confirmation submits once, locks while waiting and shows the real date and destination', async () => {
    const { user, applications } = await review({ submitDelayMs: 100 })
    await user.click(screen.getByRole('button', { name: es.workflows.applyConfirm }))
    expect(await screen.findByRole('button', { name: es.workflows.working })).toBeDisabled()
    expect(await screen.findByRole('link', { name: es.workflows.viewApplication })).toHaveAttribute(
      'href',
      '/postulaciones/app-1',
    )
    expect(applications.requests.filter((r) => r === 'POST /applications')).toHaveLength(1)
  })
  it('008-17: a duplicate conflict is explained without claiming a new submission', async () => {
    const { user } = await review({
      submitFails: { status: 409, code: 'application-already-exists' },
    })
    await user.click(screen.getByRole('button', { name: es.workflows.applyConfirm }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      es.workflows.errors['application-already-exists'],
    )
    expect(screen.queryByRole('link', { name: es.workflows.viewApplication })).toBeNull()
  })
  it('008-17: a vacancy closed meanwhile is explained', async () => {
    const { user } = await review({ submitFails: { status: 409, code: 'vacancy-closed' } })
    await user.click(screen.getByRole('button', { name: es.workflows.applyConfirm }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      es.workflows.errors['vacancy-closed'],
    )
  })
  it('008-17: failure preserves review and retry confirms successfully', async () => {
    const { user } = await review({ submitFailsOnce: { status: 500, code: 'internal-error' } })
    await user.click(screen.getByRole('button', { name: es.workflows.applyConfirm }))
    expect(await screen.findByRole('alert')).toHaveTextContent(es.workflows.error)
    await user.click(screen.getByRole('button', { name: es.workflows.applyConfirm }))
    expect(
      await screen.findByRole('link', { name: es.workflows.viewApplication }),
    ).toBeInTheDocument()
  })
  it('008-14: withdrawal permits a new review', async () => {
    await openVacancy({ existing: [{ vacancyId: FIRST, status: 'WITHDRAWN' }] })
    expect(await screen.findByRole('link', { name: t.card.applyAgain })).toHaveAttribute(
      'href',
      `/oportunidades/${FIRST}/postular`,
    )
  })
  it('008-14: an active application shows its date, actual state and detail link', async () => {
    await openVacancy({ existing: [{ vacancyId: FIRST }] })
    expect(await screen.findByText(/Postulaste el/)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: t.card.view })).toBeInTheDocument()
  })
  it('a failed duplicate check can be retried without a misleading action', async () => {
    const { applications, user } = await openVacancy({ listFails: true })
    const alert = await screen.findByRole('alert')
    applications.setListFails(false)
    await user.click(within(alert).getByRole('button', { name: es.common.retry }))
    expect(await screen.findByRole('link', { name: es.vacancyDetail.apply })).toBeInTheDocument()
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

  it.each(['INTERVIEW_PENDING', 'INTERVIEW_DONE'] as const)(
    'muestra el estado real %s de la entrevista',
    async (status) => {
      open({ existing: [{ vacancyId: FIRST, status }] })
      await screen.findByRole('heading', { level: 1, name: TITLE })
      expect(screen.getAllByText(t.stages[status]).length).toBeGreaterThan(0)
    },
  )

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
