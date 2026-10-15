import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { ProgressBar } from '../../components/ProgressBar'
import { TagField } from '../../components/TagField'
import { ChoiceChips } from '../../components/ChoiceChips'
import { es } from '../../i18n/es'
import { clearCsrfCookie } from '../../test/fakeIdentity'
import { openStudentApp } from '../../test/studentApp'

afterEach(clearCsrfCookie)
const t = es.home

const homeHeading = (name: string) => screen.findByRole('heading', { level: 1, name })

describe('Home of the student (005-01, 005-06, 005-09 to 005-14)', () => {
  it('shows "Hola de nuevo" and exactly the four blocks, no landing sections', async () => {
    openStudentApp('/')
    await homeHeading(t.title)

    for (const title of [
      t.profile.title,
      t.vacancies.forYouTitle,
      t.applications.title,
      t.practice.title,
    ]) {
      expect(screen.getByRole('region', { name: title })).toBeInTheDocument()
    }
    expect(screen.queryByText(es.how.title)).not.toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  })

  it('says "Hola, {primer nombre}" with the first word of the first names, as plain text', async () => {
    openStudentApp('/', {
      profile: {
        profile: {
          about: {
            firstNames: '<i>Ana</i> María',
            lastNames: 'Quispe',
            contactEmail: null,
            phone: null,
            about: null,
          },
        },
      },
    })
    expect(await homeHeading('Hola, <i>Ana</i>')).toBeInTheDocument()
  })

  it('says "Hola de nuevo" when there are no first names', async () => {
    openStudentApp('/')
    expect(await homeHeading(es.home.title)).toBeInTheDocument()
  })

  it('Perfil: the percentage and ONE next step come from the server and lead to its section', async () => {
    openStudentApp('/')
    const block = await screen.findByRole('region', { name: t.profile.title })

    await within(block).findByRole('progressbar')
    expect(within(block).getByRole('progressbar')).toHaveAttribute('aria-valuenow', '0')
    const link = within(block).getByRole('link', { name: es.profile.steps.SKILLS })
    expect(link).toHaveAttribute('href', '/perfil?seccion=habilidades')
    expect(within(block).getAllByRole('link')).toHaveLength(1)
  })

  it('Vacantes para ti (007-22): up to 3 with the percentage the server sent, the title is the only link, plus "Ver todas las recomendadas"', async () => {
    openStudentApp('/')
    const block = await screen.findByRole('region', { name: t.vacancies.forYouTitle })
    const list = await within(block).findByRole('list', { name: t.vacancies.forYouListLabel })
    const items = within(list).getAllByRole('listitem')
    expect(items).toHaveLength(3)
    expect(within(list).getAllByRole('link')).toHaveLength(3)
    // 90, 83 and 76: invented by the fake server, in the order it sent them.
    expect(
      within(items[0] as HTMLElement).getByText(es.compatibility.badge(90)),
    ).toBeInTheDocument()
    expect(
      within(items[2] as HTMLElement).getByText(es.compatibility.badge(76)),
    ).toBeInTheDocument()
    expect(
      within(block).getByRole('link', { name: new RegExp(t.vacancies.seeAllForYou) }),
    ).toHaveAttribute('href', '/oportunidades?vista=para-ti')
  })

  it('Vacantes (007-22): with an insufficient profile it shows the featured ones with no percentage and one line to the profile', async () => {
    const { compatibility } = openStudentApp('/', { compatibility: { sufficient: false } })
    const block = await screen.findByRole('region', { name: t.vacancies.featuredTitle })
    const list = await within(block).findByRole('list', { name: t.vacancies.listLabel })
    expect(within(list).getAllByRole('listitem').length).toBeLessThanOrEqual(3)
    expect(within(block).queryByText(/de compatibilidad/)).not.toBeInTheDocument()
    expect(
      within(block).getByText(t.vacancies.completeProfile, { exact: false }),
    ).toBeInTheDocument()
    expect(
      within(block).getByRole('link', { name: t.vacancies.completeProfileAction }),
    ).toHaveAttribute('href', '/perfil?seccion=habilidades')
    expect(
      within(block).getByRole('link', { name: new RegExp(t.vacancies.seeAll) }),
    ).toHaveAttribute('href', '/oportunidades')
    expect(
      compatibility.requests.some((path) => path.startsWith('/compatibility/recommended?size=3')),
    ).toBe(true)
  })

  it('005-14: one failing block does not affect the others and "Reintentar" repeats only its call', async () => {
    const { profile, user } = openStudentApp('/', { profile: { failing: { '/profile': 500 } } })
    const block = await screen.findByRole('region', { name: t.profile.title })
    const alert = await within(block).findByRole('alert')
    expect(alert).toHaveTextContent(t.profile.error)
    expect(
      await screen.findByRole('list', { name: t.vacancies.forYouListLabel }),
    ).toBeInTheDocument()

    profile.setFailing({})
    await user.click(within(alert).getByRole('button', { name: es.common.retry }))
    expect(await within(block).findByRole('progressbar')).toBeInTheDocument()
  })

  it('005-13: DORA says "Próximamente", carries the AI label and the privacy note; Postulaciones is real (008-21)', async () => {
    openStudentApp('/')
    const dora = await screen.findByRole('region', { name: t.practice.title })
    expect(within(dora).getByText(es.ai.label)).toBeInTheDocument()
    expect(within(dora).getByText(t.practice.privacy)).toBeInTheDocument()
    expect(within(dora).queryByRole('button')).not.toBeInTheDocument()
    const applications = screen.getByRole('region', { name: t.applications.title })
    expect(within(applications).queryByText(t.soon)).not.toBeInTheDocument()
  })
})

describe('Who sees what on "/" (005-02, 005-04)', () => {
  it('a visitor and a company see the public landing', async () => {
    openStudentApp('/', { signedOut: true })
    expect(
      await screen.findByRole('heading', { level: 1, name: new RegExp(es.hero.titleStart) }),
    ).toBeInTheDocument()
  })

  it('a company opening "/" sees the landing, and /panel keeps its notice', async () => {
    const { router } = openStudentApp('/panel', { role: 'COMPANY' })
    expect(await screen.findByText(es.identity.dashboard.noScreensTitle)).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/panel')
  })
})

describe('Menu by role (005-07, 005-08)', () => {
  it('a student sees Inicio, Oportunidades, Mi perfil, Mis postulaciones and DORA, only DORA disabled', async () => {
    openStudentApp('/perfil')
    await screen.findByRole('heading', { level: 1, name: es.profile.title })
    const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })

    expect(within(nav).getByRole('link', { name: es.nav.myProfile })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(nav).getByRole('link', { name: es.nav.opportunities })).toHaveAttribute(
      'href',
      '/oportunidades',
    )
    expect(within(nav).getByRole('link', { name: es.nav.myApplications })).toHaveAttribute(
      'href',
      '/postulaciones',
    )
    for (const label of [es.nav.dora]) {
      const item = within(nav).getByText(label, {
        exact: false,
        selector: '[aria-disabled="true"]',
      })
      expect(item).toHaveAttribute('aria-disabled', 'true')
      expect(item).toHaveAccessibleDescription(es.nav.soonNote)
    }
    expect(within(nav).queryByText(es.nav.how)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: es.nav.dashboard })).not.toBeInTheDocument()
    expect(
      within(screen.getByRole('banner')).getAllByRole('button', { name: es.identity.nav.logout })
        .length,
    ).toBeGreaterThan(0)
  })

  it('a visitor keeps the public menu with "Cómo funciona" and DORA as links', async () => {
    openStudentApp('/', { signedOut: true })
    const nav = await screen.findByRole('navigation', { name: es.app.mainNavLabel })
    expect(within(nav).getByRole('link', { name: es.nav.how })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: es.nav.dora })).toBeInTheDocument()
    expect(within(nav).queryByRole('link', { name: es.nav.myProfile })).not.toBeInTheDocument()
  })
})

describe('New components', () => {
  it('ProgressBar exposes its value and clamps it', () => {
    render(<ProgressBar value={140} label="Avance" />)
    const bar = screen.getByRole('progressbar', { name: 'Avance' })
    expect(bar).toHaveAttribute('aria-valuenow', '100')
    expect(bar).toHaveAttribute('aria-valuetext', '100 %')
  })

  it('ChoiceChips are native checkboxes in a fieldset and keep the order of the options', async () => {
    const seen: string[][] = []
    const options = [
      { value: 'a', label: 'Uno' },
      { value: 'b', label: 'Dos' },
    ]
    render(
      <ChoiceChips legend="Elige" options={options} value={['b']} onChange={(v) => seen.push(v)} />,
    )
    await userEvent.click(screen.getByRole('checkbox', { name: 'Uno' }))
    expect(screen.getByRole('group', { name: 'Elige' })).toBeInTheDocument()
    expect(seen[0]).toEqual(['a', 'b'])
  })

  it('TagField adds with Enter, ignores repeats and removes with its button', async () => {
    const calls: string[][] = []
    render(
      <TagField
        label="Tecs"
        values={['React']}
        onChange={(v) => calls.push(v)}
        addLabel="Agregar"
        removeLabel={(tag) => `Quitar ${tag}`}
        listLabel="Lista"
      />,
    )
    const input = screen.getByRole('textbox', { name: 'Tecs' })
    await userEvent.type(input, 'react{Enter}')
    expect(calls).toHaveLength(0)
    await userEvent.type(input, 'Node{Enter}')
    expect(calls[0]).toEqual(['React', 'Node'])
    await userEvent.click(screen.getByRole('button', { name: 'Quitar React' }))
    expect(calls[1]).toEqual([])
  })
})
