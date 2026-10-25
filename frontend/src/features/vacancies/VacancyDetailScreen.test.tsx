import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { es } from '../../i18n/es'
import { mockBackend, VACANCY_IDS } from '../../test/fakeBackend'
import { mockApplicationsBackend } from '../../test/fakeApplications'
import { renderApp } from '../../test/renderApp'

vi.mock('../../session/useSession', async () => {
  const { visitorSession } = await import('../../session/sessionContext')
  return { useSession: vi.fn(() => visitorSession) }
})
import { visitorSession, type SessionState } from '../../session/sessionContext'
import { useSession } from '../../session/useSession'

const signedIn: SessionState = {
  ...visitorSession,
  status: 'authenticated',
  user: { id: '1', email: 'ana.prueba@example.test', role: 'STUDENT' },
  isStudent: true,
}

const FIRST = VACANCY_IDS[0] as string // Practicante Backend Java, Andes Tech, remote
const title = () => screen.findByRole('heading', { level: 1, name: 'Practicante Backend Java' })

describe('Page /oportunidades/:id (004-21)', () => {
  it('shows the company, one h1, the description and the sample-data notes', async () => {
    mockBackend()
    renderApp(`/oportunidades/${FIRST}`)
    await title()

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getAllByText('Andes Tech').length).toBeGreaterThan(0)
    expect(screen.getByText(/Descripción de Practicante Backend Java/)).toBeInTheDocument()
    expect(within(screen.getByRole('main')).queryByText(es.footer.sampleNote)).toBeNull()
    // The tab title is set in an effect after the heading is drawn: wait for it, do not race it.
    await waitFor(() => expect(document.title).toBe(`Practicante Backend Java · ${es.app.name}`))
  })

  it('011-02: has no "Ver perfil de la empresa" entry, no "Próximamente" and no company avatar', async () => {
    mockBackend()
    renderApp(`/oportunidades/${FIRST}`)
    await title()

    expect(screen.queryByText('Ver perfil de la empresa')).toBeNull()
    expect(screen.queryByText('Próximamente')).toBeNull()
    expect(document.querySelector('.company-avatar')).toBeNull()
  })

  it('separates "Obligatorios" and "Deseables", each with a badge and the minimum level in words', async () => {
    mockBackend()
    renderApp(`/oportunidades/${FIRST}`)
    await title()

    const requirements = screen.getByRole('region', { name: es.vacancyDetail.requirements })
    const required = within(requirements).getByRole('heading', {
      level: 3,
      name: es.vacancyDetail.required,
    })
    const desirable = within(requirements).getByRole('heading', {
      level: 3,
      name: es.vacancyDetail.desirable,
    })
    const [requiredList, desirableList] = within(requirements).getAllByRole('list')
    expect(
      required.compareDocumentPosition(desirable) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()

    const requiredItems = within(requiredList as HTMLElement).getAllByRole('listitem')
    expect(
      requiredItems.map((item) => item.querySelector('.vacancy-detail__skill')?.textContent),
    ).toEqual(['Java', 'Spring Boot', 'SQL'])
    expect(requiredItems[0]).toHaveTextContent(es.badge.required)
    expect(requiredItems[0]).toHaveTextContent('Nivel mínimo: intermedio')

    const desirableItems = within(desirableList as HTMLElement).getAllByRole('listitem')
    expect(desirableItems).toHaveLength(2)
    expect(desirableItems[0]).toHaveTextContent(es.badge.desirable)
    expect(desirableItems[0]).toHaveTextContent('Nivel mínimo: básico')
  })

  it('writes the three levels in words', async () => {
    expect(es.vacancies.levels).toEqual({ 1: 'básico', 2: 'intermedio', 3: 'avanzado' })
  })

  it('shows the conditions: modality, place, hours and publication', async () => {
    mockBackend()
    renderApp(`/oportunidades/${FIRST}`)
    await title()
    const conditions = screen.getByRole('region', { name: es.vacancyDetail.conditions })

    expect(conditions).toHaveTextContent(`${es.vacancyDetail.modality}Remoto`)
    expect(conditions).toHaveTextContent(
      `${es.vacancyDetail.location}${es.vacancyDetail.noLocation}`,
    )
    expect(conditions).toHaveTextContent('20 h por semana')
    expect(conditions).toHaveTextContent(/Publicada/)
  })

  it('shows the stages only as information, with the final decision belonging to the company', async () => {
    mockBackend()
    renderApp(`/oportunidades/${FIRST}`)
    await title()
    const process = screen.getByRole('region', { name: es.vacancyDetail.process })
    const stages = within(process).getAllByRole('listitem')

    expect(stages.map((stage) => stage.querySelector('span')?.textContent)).toEqual([
      'Enviada',
      'Examen técnico',
      'Entrevista',
      'Revisión de la empresa',
    ])
    expect(within(process).queryByText('Próximamente')).toBeNull()
    expect(process).toHaveTextContent('La decisión final es de la empresa')
    expect(within(process).queryByRole('button')).not.toBeInTheDocument()
    expect(within(process).queryByRole('link')).not.toBeInTheDocument()
  })

  it('on a closed vacancy says it is no longer open and offers no "Postular"', async () => {
    mockBackend({ closed: [FIRST] })
    renderApp(`/oportunidades/${FIRST}`)
    await title()

    expect(screen.getByText(es.vacancyDetail.closedTitle)).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: es.vacancyDetail.apply })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Postular/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: es.vacancyDetail.process })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: es.vacancyDetail.seeOthers })).toHaveAttribute(
      'href',
      '/oportunidades',
    )
  })
})

describe('"Postular" and the session (004-22)', () => {
  it('without a session is a link to /ingresar that keeps the vacancy, with the notice and no percentage', async () => {
    mockBackend()
    renderApp(`/oportunidades/${FIRST}`)
    await title()

    const apply = screen.getByRole('link', { name: es.vacancyDetail.apply })
    expect(apply).toHaveAttribute('href', `/ingresar?returnTo=%2Foportunidades%2F${FIRST}`)
    expect(screen.getByText(es.vacancyDetail.applyLoginNotice)).toBeInTheDocument()
    expect(screen.queryByText(/\d\s?%/)).not.toBeInTheDocument()
    expect(screen.queryByText(/% de compatibilidad/)).not.toBeInTheDocument()
  })

  it('008-14: with a session "Postular" is active and there is no sign-in notice', async () => {
    vi.mocked(useSession).mockReturnValue(signedIn)
    try {
      mockBackend()
      mockApplicationsBackend()
      renderApp(`/oportunidades/${FIRST}`)
      await title()

      const button = await screen.findByRole('button', { name: es.vacancyDetail.apply })
      expect(button).toBeEnabled()
      expect(screen.queryByRole('link', { name: es.vacancyDetail.apply })).not.toBeInTheDocument()
      expect(screen.queryByText(es.vacancyDetail.applyLoginNotice)).not.toBeInTheDocument()
    } finally {
      vi.mocked(useSession).mockReturnValue(visitorSession)
    }
  })
})

describe('Detail states and "Volver" (004-23)', () => {
  it('shows a skeleton while loading', () => {
    mockBackend()
    renderApp(`/oportunidades/${FIRST}`)
    const busy = document.querySelector('[aria-busy="true"]')
    expect(busy).not.toBeNull()
    expect(busy?.querySelector('.skeleton')).not.toBeNull()
  })

  it('keeps one h1 in the page while the detail skeleton is shown', () => {
    mockBackend()
    renderApp(`/oportunidades/${FIRST}`)
    const titles = screen.getAllByRole('heading', { level: 1 })
    expect(titles).toHaveLength(1)
    expect(titles[0]).toHaveTextContent(es.pages.vacancy.title)
  })

  it('on a missing vacancy says it does not exist and links to /oportunidades', async () => {
    mockBackend()
    renderApp('/oportunidades/no-existe')
    const heading = await screen.findByRole('heading', {
      level: 1,
      name: es.vacancyDetail.notFoundTitle,
    })
    expect(heading).toBeInTheDocument()
    expect(screen.getByRole('link', { name: es.vacancyDetail.notFoundLink })).toHaveAttribute(
      'href',
      '/oportunidades',
    )
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('on a failure shows a role="alert" error with "Reintentar" and recovers', async () => {
    const api = mockBackend({ failing: { [`/vacancies/${FIRST}`]: 500 } })
    renderApp(`/oportunidades/${FIRST}`)
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(es.vacancyDetail.errorTitle)

    api.setFailing({})
    await userEvent.click(within(alert).getByRole('button', { name: es.common.retry }))
    await title()
  })

  it('"Volver a los resultados" goes to the list as it was when the person came from it', async () => {
    mockBackend()
    const { router } = renderApp('/oportunidades?q=backend&modalidad=remoto')
    await userEvent.click(await screen.findByRole('link', { name: 'Practicante Backend Java' }))
    await title()
    expect(router.state.location.pathname).toBe(`/oportunidades/${FIRST}`)

    await userEvent.click(screen.getByRole('link', { name: new RegExp(es.vacancyDetail.back) }))
    await waitFor(() => expect(router.state.location.pathname).toBe('/oportunidades'))
    expect(router.state.location.search).toBe('?q=backend&modalidad=remoto')
  })

  it('"Volver a los resultados" goes to /oportunidades when the person arrived directly', async () => {
    mockBackend()
    renderApp(`/oportunidades/${FIRST}`)
    await title()
    expect(screen.getByRole('link', { name: new RegExp(es.vacancyDetail.back) })).toHaveAttribute(
      'href',
      '/oportunidades',
    )
  })

  it('does not follow a "from" that points outside /oportunidades', async () => {
    mockBackend()
    const { router } = renderApp('/')
    await act(() =>
      router.navigate(`/oportunidades/${FIRST}`, { state: { from: 'https://example.com/x' } }),
    )
    await title()
    expect(screen.getByRole('link', { name: new RegExp(es.vacancyDetail.back) })).toHaveAttribute(
      'href',
      '/oportunidades',
    )
  })
})

describe('Header: Vacantes (004-24, spec 011)', () => {
  it.each(['/oportunidades', `/oportunidades/${FIRST}`])(
    'marks "Vacantes" as the current section in %s',
    async (path) => {
      mockBackend()
      renderApp(path)
      const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })
      expect(within(nav).getByRole('link', { name: es.nav.opportunities })).toHaveAttribute(
        'aria-current',
        'page',
      )
    },
  )

  it('"Vacantes" in the header opens the list, whose title is "Vacantes" (the address stays /oportunidades)', async () => {
    mockBackend()
    const { router } = renderApp('/')
    const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })

    await userEvent.click(within(nav).getByRole('link', { name: es.nav.opportunities }))

    expect(await screen.findByRole('heading', { level: 1, name: 'Vacantes' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/oportunidades')
  })
})
