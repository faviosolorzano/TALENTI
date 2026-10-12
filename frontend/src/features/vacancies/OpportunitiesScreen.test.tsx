import { act, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { es } from '../../i18n/es'
import { mockBackend } from '../../test/fakeBackend'
import { renderApp } from '../../test/renderApp'

const countHeading = (text: string | RegExp) =>
  screen.findByRole('heading', { level: 2, name: text })

const resultsTitle = () => document.getElementById('results-title') as HTMLElement

const searchOf = (router: { state: { location: { search: string } } }) =>
  router.state.location.search

describe('Page /oportunidades: search and filters (004-17)', () => {
  it('has a single h1, the field "¿Qué sabes hacer?", the place selector and "Buscar"', async () => {
    mockBackend()
    renderApp('/oportunidades')
    await countHeading('12 vacantes')

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(es.opportunities.title)
    const form = screen.getByRole('search', { name: es.opportunities.searchLabel })
    expect(within(form).getByRole('textbox', { name: es.opportunities.queryLabel })).toBeVisible()
    expect(
      within(form).getByRole('combobox', { name: es.opportunities.locationLabel }),
    ).toBeVisible()
    expect(within(form).getByRole('button', { name: es.opportunities.submit })).toBeVisible()
  })

  it('lists the cities with their count and filters with modality and technology with counts', async () => {
    mockBackend()
    renderApp('/oportunidades')
    await countHeading('12 vacantes')

    const place = screen.getByRole('combobox', { name: es.opportunities.locationLabel })
    expect(within(place).getByRole('option', { name: 'Lima (5)' })).toBeInTheDocument()
    expect(within(place).getByRole('option', { name: 'Arequipa (3)' })).toBeInTheDocument()

    const panel = screen.getByRole('group', { name: es.opportunities.filters.panelLabel })
    expect(within(panel).getByRole('checkbox', { name: 'Remoto (4)' })).toBeInTheDocument()
    expect(within(panel).getByRole('checkbox', { name: 'Híbrido (5)' })).toBeInTheDocument()
    expect(within(panel).getByRole('checkbox', { name: 'Presencial (3)' })).toBeInTheDocument()
    expect(within(panel).getByRole('checkbox', { name: /^Git \(\d+\)$/ })).toBeInTheDocument()
  })

  it('sends the search with Enter and with "Buscar", not while typing', async () => {
    const api = mockBackend()
    const { router } = renderApp('/oportunidades')
    await countHeading('12 vacantes')
    const field = screen.getByRole('textbox', { name: es.opportunities.queryLabel })

    await userEvent.type(field, 'programacion')
    expect(api.requested().filter((path) => path.includes('q='))).toHaveLength(0)

    await userEvent.type(field, '{Enter}')
    await waitFor(() => expect(searchOf(router)).toBe('?q=programacion'))
    expect(api.requested()).toContain('/vacancies?q=programacion')

    await userEvent.clear(field)
    await userEvent.type(field, 'laravel')
    await userEvent.click(screen.getByRole('button', { name: es.opportunities.submit }))
    await waitFor(() => expect(searchOf(router)).toBe('?q=laravel'))
    expect(await countHeading('1 vacante')).toBeInTheDocument()
  })

  it('searches by place with the selector and "Buscar"', async () => {
    mockBackend()
    const { router } = renderApp('/oportunidades')
    await countHeading('12 vacantes')

    await userEvent.selectOptions(
      screen.getByRole('combobox', { name: es.opportunities.locationLabel }),
      'Arequipa',
    )
    expect(searchOf(router)).toBe('')
    await userEvent.click(screen.getByRole('button', { name: es.opportunities.submit }))
    await waitFor(() => expect(searchOf(router)).toBe('?ubicacion=Arequipa'))
    expect(await countHeading('3 vacantes')).toBeInTheDocument()
  })

  it('applies a filter as soon as it is checked and goes back to the first page', async () => {
    const api = mockBackend()
    const { router } = renderApp('/oportunidades?pagina=2')
    await countHeading('12 vacantes')
    expect(searchOf(router)).toBe('?pagina=2')

    await userEvent.click(screen.getByRole('checkbox', { name: 'Remoto (4)' }))
    await waitFor(() => expect(searchOf(router)).toBe('?modalidad=remoto'))
    expect(api.requested()).toContain('/vacancies?modality=REMOTE')
    expect(await countHeading('4 vacantes')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('checkbox', { name: /^Git \(\d+\)$/ }))
    await waitFor(() => expect(searchOf(router)).toBe('?modalidad=remoto&tecnologia=git'))
  })

  it('folds the filters into "Filtros (n)" with aria-expanded and aria-controls; Esc closes and returns the focus', async () => {
    mockBackend()
    renderApp('/oportunidades?modalidad=remoto&tecnologia=git')
    await countHeading(/vacantes?$/)

    const toggle = screen.getByRole('button', { name: es.opportunities.filters.toggle(2) })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    const panelId = toggle.getAttribute('aria-controls') as string
    expect(document.getElementById(panelId)).toBeInTheDocument()

    await userEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    expect(toggle.closest('.filters')).toHaveAttribute('data-open', 'true')

    await userEvent.click(screen.getByRole('checkbox', { name: /^Remoto/ }))
    await userEvent.keyboard('{Escape}')
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    expect(toggle).toHaveFocus()
  })

  it('shows only the first technologies and expands the rest on request', async () => {
    mockBackend()
    renderApp('/oportunidades')
    await countHeading('12 vacantes')
    const panel = screen.getByRole('group', { name: es.opportunities.filters.panelLabel })

    const initial = within(panel).getAllByRole('checkbox').length
    const more = within(panel).getByRole('button', { name: /^Ver las \d+ tecnologías$/ })
    expect(more).toHaveAttribute('aria-expanded', 'false')
    await userEvent.click(more)
    expect(within(panel).getAllByRole('checkbox').length).toBeGreaterThan(initial)
    expect(
      within(panel).getByRole('button', { name: es.opportunities.filters.showFewerSkills }),
    ).toHaveAttribute('aria-expanded', 'true')
  })

  it('sorts with "Ordenar por"', async () => {
    mockBackend()
    const { router } = renderApp('/oportunidades')
    await countHeading('12 vacantes')
    await userEvent.selectOptions(
      screen.getByRole('combobox', { name: es.opportunities.sort.label }),
      es.opportunities.sort.company,
    )
    await waitFor(() => expect(searchOf(router)).toBe('?orden=empresa'))
    await countHeading('12 vacantes')
    const first = screen.getAllByRole('article')[0] as HTMLElement
    expect(first).toHaveTextContent('Andes Tech')
  })
})

describe('Page /oportunidades: state in the URL (004-18)', () => {
  it('reproduces a shared link', async () => {
    const api = mockBackend()
    renderApp('/oportunidades?q=react&modalidad=remoto&orden=empresa')

    expect(await countHeading('1 vacante')).toBeInTheDocument()
    expect(api.requested()).toContain('/vacancies?q=react&modality=REMOTE&sort=company')
    expect(screen.getByRole('textbox', { name: es.opportunities.queryLabel })).toHaveValue('react')
    expect(screen.getByRole('checkbox', { name: /^Remoto/ })).toBeChecked()
    expect(screen.getByRole('combobox', { name: es.opportunities.sort.label })).toHaveValue(
      'company',
    )
  })

  it('ignores invalid values and corrects the address', async () => {
    const api = mockBackend()
    const { router } = renderApp('/oportunidades?modalidad=volando&pagina=abc&orden=raro&q=laravel')
    await countHeading('1 vacante')
    expect(searchOf(router)).toBe('?q=laravel')
    expect(api.requested().every((path) => !path.includes('volando'))).toBe(true)
  })

  it('leads a page beyond the last one to the last one', async () => {
    mockBackend()
    const { router } = renderApp('/oportunidades?pagina=99')
    await waitFor(() => expect(searchOf(router)).toBe('?pagina=2'))
    expect(await countHeading('12 vacantes')).toBeInTheDocument()
    // The title is the same while page 1 is kept on screen: wait for the cards of the last page.
    await waitFor(() => expect(screen.getAllByRole('article')).toHaveLength(3))
  })

  it('leads to the first page when there are no results but the page is not the first', async () => {
    mockBackend()
    const { router } = renderApp('/oportunidades?q=zzzz&pagina=4')
    await waitFor(() => expect(searchOf(router)).toBe('?q=zzzz'))
    expect(await countHeading('0 vacantes')).toBeInTheDocument()
  })

  it('walks back and forward through the searches', async () => {
    mockBackend()
    const { router } = renderApp('/oportunidades')
    await countHeading('12 vacantes')

    await userEvent.click(screen.getByRole('checkbox', { name: 'Remoto (4)' }))
    await countHeading('4 vacantes')
    expect(screen.getByRole('checkbox', { name: /^Remoto/ })).toBeChecked()

    await act(() => router.navigate(-1))
    await countHeading('12 vacantes')
    expect(screen.getByRole('checkbox', { name: /^Remoto/ })).not.toBeChecked()

    await act(() => router.navigate(1))
    await countHeading('4 vacantes')
    expect(screen.getByRole('checkbox', { name: /^Remoto/ })).toBeChecked()
  })

  it('shows the text of the address in the field when the address changes', async () => {
    mockBackend()
    const { router } = renderApp('/oportunidades?q=laravel')
    await countHeading('1 vacante')
    await act(() => router.navigate('/oportunidades?q=swift'))
    expect(screen.getByRole('textbox', { name: es.opportunities.queryLabel })).toHaveValue('swift')
  })

  it('does not store the searched text anywhere in the browser (principle 5)', async () => {
    mockBackend()
    const setLocal = vi.spyOn(Storage.prototype, 'setItem')
    renderApp('/oportunidades')
    await countHeading('12 vacantes')
    await userEvent.type(
      screen.getByRole('textbox', { name: es.opportunities.queryLabel }),
      'secreto{Enter}',
    )
    await countHeading('0 vacantes')

    expect(setLocal).not.toHaveBeenCalled()
    expect(document.cookie).toBe('')
    expect(JSON.stringify({ ...window.localStorage })).not.toContain('secreto')
    expect(JSON.stringify({ ...window.sessionStorage })).not.toContain('secreto')
    setLocal.mockRestore()
  })
})

describe('Page /oportunidades: states (004-19)', () => {
  it('shows skeletons with aria-busy while loading', () => {
    mockBackend()
    renderApp('/oportunidades')
    const results = screen.getByRole('region', { name: es.opportunities.results.searching })
    expect(results).toHaveAttribute('aria-busy', 'true')
    expect(results.querySelectorAll('.skeleton').length).toBeGreaterThan(0)
  })

  it('announces "{N} vacantes" in a polite region and shows the range and the pagination', async () => {
    mockBackend()
    renderApp('/oportunidades')
    const title = await countHeading('12 vacantes')

    expect(title.closest('[aria-live="polite"]')).not.toBeNull()
    expect(screen.getByText('Mostrando 1–9 de 12')).toBeInTheDocument()
    const nav = screen.getByRole('navigation', { name: es.opportunities.pagination.label })
    expect(within(nav).getByText('1')).toHaveAttribute('aria-current', 'page')
    expect(within(nav).getByRole('link', { name: 'Página 2' })).toBeInTheDocument()
    expect(within(nav).getByRole('link', { name: es.opportunities.pagination.next })).toBeVisible()
    expect(
      within(nav).queryByRole('link', { name: es.opportunities.pagination.previous }),
    ).not.toBeInTheDocument()
  })

  it('moves the focus to the title of the results when the page changes', async () => {
    mockBackend()
    const { router } = renderApp('/oportunidades')
    await countHeading('12 vacantes')
    await userEvent.click(screen.getByRole('link', { name: 'Página 2' }))
    await waitFor(() => expect(searchOf(router)).toBe('?pagina=2'))

    expect(screen.getByRole('heading', { level: 2, name: /vacantes?$/ })).toHaveFocus()
    expect(await screen.findByText('Mostrando 10–12 de 12')).toBeInTheDocument()
    expect(screen.getByText('2')).toHaveAttribute('aria-current', 'page')
    expect(screen.getAllByRole('article')).toHaveLength(3)
  })

  it('does not draw the pagination when everything fits in one page', async () => {
    mockBackend()
    renderApp('/oportunidades?modalidad=presencial')
    await countHeading('3 vacantes')
    expect(
      screen.queryByRole('navigation', { name: es.opportunities.pagination.label }),
    ).not.toBeInTheDocument()
  })

  it('says "No encontramos vacantes con estos filtros" and "Limpiar filtros" clears everything', async () => {
    const api = mockBackend()
    const { router } = renderApp(
      '/oportunidades?modalidad=presencial&tecnologia=laravel&orden=empresa',
    )
    await countHeading('0 vacantes')

    expect(screen.getByText(es.opportunities.results.emptyTitle)).toBeInTheDocument()
    // Without text the old behavior stays: no suggestions section and no request for recents.
    expect(screen.queryByText(es.opportunities.results.recentTitle)).not.toBeInTheDocument()
    expect(api.requested().some((path) => path.includes('size=3'))).toBe(false)
    await userEvent.click(
      screen.getAllByRole('button', { name: es.opportunities.filters.clear }).at(-1) as HTMLElement,
    )
    await waitFor(() => expect(searchOf(router)).toBe('?orden=empresa'))
    expect(await countHeading('12 vacantes')).toBeInTheDocument()
  })

  it('says "Aún no hay vacantes publicadas" without "Limpiar" when there are none and no filters', async () => {
    mockBackend({ vacancies: [] })
    renderApp('/oportunidades')
    expect(await screen.findByText(es.opportunities.results.noneYetTitle)).toBeInTheDocument()
    expect(screen.queryByText(es.opportunities.results.emptyTitle)).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: es.opportunities.filters.clear }),
    ).not.toBeInTheDocument()
  })

  it('shows a role="alert" error with "Reintentar" and recovers', async () => {
    const api = mockBackend({ failing: { '/vacancies': 500 } })
    renderApp('/oportunidades')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(es.opportunities.results.errorTitle)

    api.setFailing({})
    await userEvent.click(within(alert).getByRole('button', { name: es.common.retry }))
    expect(await countHeading('12 vacantes')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('keeps the search usable when only the filters fail, with its own "Reintentar"', async () => {
    const api = mockBackend({ failing: { '/vacancies/filters': 500 } })
    renderApp('/oportunidades')
    await countHeading('12 vacantes')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(es.opportunities.filters.unavailable)

    api.setFailing({})
    await userEvent.click(within(alert).getByRole('button', { name: es.common.retry }))
    expect(await screen.findByRole('checkbox', { name: 'Remoto (4)' })).toBeInTheDocument()
  })

  it('shows the answer of the LAST request when filters change quickly', async () => {
    const api = mockBackend()
    const real = api.fetch.getMockImplementation() as (
      input: RequestInfo | URL,
    ) => Promise<Response>
    let releaseSlow: () => void = () => {}
    api.fetch.mockImplementation(async (input: RequestInfo | URL) => {
      if (String(input).includes('modality=REMOTE')) {
        await new Promise<void>((resolve) => (releaseSlow = resolve))
      }
      return real(input)
    })

    const { router } = renderApp('/oportunidades')
    await countHeading('12 vacantes')

    await act(() => router.navigate('/oportunidades?modalidad=remoto')) // slow, 4 results
    await act(() => router.navigate('/oportunidades?modalidad=presencial')) // fast, 3 results
    expect(await countHeading('3 vacantes')).toBeInTheDocument()

    await act(async () => {
      releaseSlow()
      await new Promise((resolve) => setTimeout(resolve, 20))
    })
    expect(screen.getByRole('heading', { level: 2, name: /vacantes?$/ })).toHaveTextContent(
      '3 vacantes',
    )
    expect(screen.getAllByRole('article')).toHaveLength(3)
  })
})

const recentPath = '/vacancies?sort=recent&size=3'

describe('Page /oportunidades: order (004-30)', () => {
  const sortSelect = () => screen.getByRole('combobox', { name: es.opportunities.sort.label })
  const optionNames = () =>
    within(sortSelect())
      .getAllByRole('option')
      .map((option) => option.textContent)

  it('offers only "Más recientes" and "Empresa (A-Z)" when there is no text (004-30)', async () => {
    mockBackend()
    renderApp('/oportunidades')
    await countHeading('12 vacantes')
    expect(optionNames()).toEqual([es.opportunities.sort.recent, es.opportunities.sort.company])
    expect(sortSelect()).toHaveValue('recent')
  })

  it('offers "Más relevantes" first and selected when there is text, without sending "sort" (004-30)', async () => {
    const api = mockBackend()
    renderApp('/oportunidades?q=java')
    await countHeading(/vacantes?$/)
    expect(optionNames()).toEqual([
      es.opportunities.sort.relevance,
      es.opportunities.sort.recent,
      es.opportunities.sort.company,
    ])
    expect(sortSelect()).toHaveValue('relevance')
    expect(api.requested()).toContain('/vacancies?q=java')
    expect(api.requested().some((path) => path.includes('sort='))).toBe(false)
  })

  it('puts the Java vacancy first when writing "se usar java" (004-30)', async () => {
    const api = mockBackend()
    const { router } = renderApp('/oportunidades')
    await countHeading('12 vacantes')
    await userEvent.type(
      screen.getByRole('textbox', { name: es.opportunities.queryLabel }),
      'se usar java{Enter}',
    )
    await waitFor(() => expect(searchOf(router)).toBe('?q=se+usar+java'))
    await waitFor(() =>
      expect(screen.getAllByRole('article')[0]).toHaveTextContent('Practicante Backend Java'),
    )
    expect(api.requested().some((path) => path.includes('sort='))).toBe(false)
  })

  it('sends and keeps in the address an order chosen explicitly (004-30)', async () => {
    const api = mockBackend()
    const { router } = renderApp('/oportunidades?q=java')
    await countHeading(/vacantes?$/)

    await userEvent.selectOptions(sortSelect(), es.opportunities.sort.recent)
    await waitFor(() => expect(searchOf(router)).toBe('?q=java&orden=recientes'))
    await waitFor(() => expect(api.requested()).toContain('/vacancies?q=java&sort=recent'))
    expect(sortSelect()).toHaveValue('recent')

    await userEvent.selectOptions(sortSelect(), es.opportunities.sort.relevance)
    await waitFor(() => expect(searchOf(router)).toBe('?q=java&orden=relevancia'))
    await waitFor(() => expect(api.requested()).toContain('/vacancies?q=java&sort=relevance'))
  })

  it('reproduces a shared link with "orden=relevancia" and text (004-30, 004-18)', async () => {
    const api = mockBackend()
    const { router } = renderApp('/oportunidades?q=java&orden=relevancia')
    await countHeading(/vacantes?$/)
    expect(searchOf(router)).toBe('?q=java&orden=relevancia')
    expect(sortSelect()).toHaveValue('relevance')
    expect(api.requested()).toContain('/vacancies?q=java&sort=relevance')
  })

  it('ignores "orden=relevancia" without text and corrects the address once (004-18, 004-30)', async () => {
    const api = mockBackend()
    const { router } = renderApp('/oportunidades?orden=relevancia&modalidad=remoto')
    await countHeading('4 vacantes')
    expect(searchOf(router)).toBe('?modalidad=remoto')
    expect(sortSelect()).toHaveValue('recent')
    expect(api.requested().some((path) => path.includes('sort='))).toBe(false)
  })

  it('goes back to the first page when the searched text changes (004-30)', async () => {
    mockBackend()
    const { router } = renderApp('/oportunidades?q=practicante&pagina=2')
    await countHeading('12 vacantes')
    expect(searchOf(router)).toBe('?q=practicante&pagina=2')

    const field = screen.getByRole('textbox', { name: es.opportunities.queryLabel })
    await userEvent.clear(field)
    await userEvent.type(field, 'python{Enter}')
    await waitFor(() => expect(searchOf(router)).toBe('?q=python'))
  })

  it('drops "Más relevantes" from the selector when the text is cleared (004-30)', async () => {
    mockBackend()
    const { router } = renderApp('/oportunidades?q=java&orden=relevancia')
    await countHeading(/vacantes?$/)
    await userEvent.clear(screen.getByRole('textbox', { name: es.opportunities.queryLabel }))
    await userEvent.click(screen.getByRole('button', { name: es.opportunities.submit }))
    await waitFor(() => expect(searchOf(router)).toBe(''))
    await countHeading('12 vacantes')
    expect(optionNames()).toEqual([es.opportunities.sort.recent, es.opportunities.sort.company])
  })
})

describe('Page /oportunidades: search without results (004-33)', () => {
  const noMatchesHeading = () =>
    screen.findByRole('heading', { level: 3, name: /^No encontramos vacantes para/ })

  it('says "No encontramos vacantes para «…»" with suggestions and the 3 recent vacancies (004-33)', async () => {
    const api = mockBackend()
    renderApp('/oportunidades?q=cobol')

    expect(
      await screen.findByRole('heading', {
        level: 3,
        name: es.opportunities.results.noMatchesTitle('cobol'),
      }),
    ).toBeInTheDocument()
    const hints = screen.getByRole('list', { name: es.opportunities.results.suggestionsLabel })
    expect(hints).toHaveTextContent(es.opportunities.results.suggestionSpelling)
    expect(hints).toHaveTextContent(es.opportunities.results.suggestionTechnology)
    // Without filters there is nothing to remove.
    expect(hints).not.toHaveTextContent(es.opportunities.results.suggestionFilters)
    expect(
      screen.queryByRole('button', { name: es.opportunities.results.removeFilters }),
    ).not.toBeInTheDocument()

    expect(
      await screen.findByRole('heading', { level: 3, name: es.opportunities.results.recentTitle }),
    ).toBeInTheDocument()
    const recent = await screen.findByRole('list', {
      name: es.opportunities.results.recentListLabel,
    })
    const cards = within(recent).getAllByRole('article')
    expect(cards).toHaveLength(3)
    expect(cards[0]).toHaveTextContent('Practicante Backend Java') // the newest
    expect(recent).not.toHaveTextContent('%')
    expect(api.requested().filter((path) => path === recentPath)).toHaveLength(1)
  })

  it('suggests removing filters and "Quitar filtros" keeps the text (004-33)', async () => {
    mockBackend()
    const { router } = renderApp('/oportunidades?q=cobol&modalidad=remoto&ubicacion=Lima')
    await noMatchesHeading()

    expect(
      screen.getByRole('list', { name: es.opportunities.results.suggestionsLabel }),
    ).toHaveTextContent(es.opportunities.results.suggestionFilters)
    await userEvent.click(
      screen.getByRole('button', { name: es.opportunities.results.removeFilters }),
    )
    await waitFor(() => expect(searchOf(router)).toBe('?q=cobol'))
    expect(screen.getByRole('textbox', { name: es.opportunities.queryLabel })).toHaveValue('cobol')
    expect(
      screen.queryByRole('button', { name: es.opportunities.results.removeFilters }),
    ).not.toBeInTheDocument()
  })

  it('shows the searched text as plain text and cuts it when it is long (004-33)', async () => {
    mockBackend()
    const long = 'x'.repeat(70)
    renderApp(`/oportunidades?q=${encodeURIComponent(`<b>${long}</b>`)}`)
    const title = await noMatchesHeading()

    expect(title.querySelector('b')).toBeNull()
    expect(title.textContent).toContain('<b>')
    expect(title.textContent).toContain('…')
    expect(title.textContent).not.toContain(long)
  })

  it('does not break the screen nor show the section when the recent vacancies fail (004-33)', async () => {
    const api = mockBackend()
    const real = api.fetch.getMockImplementation() as (
      input: RequestInfo | URL,
    ) => Promise<Response>
    api.fetch.mockImplementation(async (input: RequestInfo | URL) =>
      String(input).includes('size=3')
        ? new Response('{}', { status: 500, headers: { 'Content-Type': 'application/json' } })
        : real(input),
    )
    renderApp('/oportunidades?q=cobol')

    await noMatchesHeading()
    await waitFor(() =>
      expect(api.fetch.mock.calls.some(([input]) => String(input).includes('size=3'))).toBe(true),
    )
    await waitFor(() =>
      expect(screen.queryByText(es.opportunities.results.recentTitle)).not.toBeInTheDocument(),
    )
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: es.opportunities.queryLabel })).toHaveValue('cobol')
    expect(screen.getByRole('checkbox', { name: 'Remoto (4)' })).toBeInTheDocument()
  })

  it('does not ask for recent vacancies when there are results (004-33)', async () => {
    const api = mockBackend()
    renderApp('/oportunidades?q=java')
    await countHeading(/vacantes?$/)
    expect(screen.getAllByRole('article').length).toBeGreaterThan(0)
    expect(screen.queryByText(es.opportunities.results.recentTitle)).not.toBeInTheDocument()
    expect(api.requested().some((path) => path.includes('size=3'))).toBe(false)
  })

  it('does not ask for recent vacancies while a refresh keeps the empty answer on screen (004-33)', async () => {
    const api = mockBackend()
    const real = api.fetch.getMockImplementation() as (
      input: RequestInfo | URL,
    ) => Promise<Response>
    let release: () => void = () => {}
    api.fetch.mockImplementation(async (input: RequestInfo | URL) => {
      if (String(input).includes('q=java')) {
        await new Promise<void>((resolve) => (release = resolve))
      }
      return real(input)
    })
    const { router } = renderApp('/oportunidades?q=cobol')
    await screen.findByText(es.opportunities.results.recentTitle)
    expect(api.requested().filter((path) => path === recentPath)).toHaveLength(1)

    await act(() => router.navigate('/oportunidades?q=java'))
    expect(screen.getByRole('region', { name: '0 vacantes' })).toHaveAttribute(
      'data-refreshing',
      'true',
    )
    // The old answer keeps its own text, and the recent vacancies are not asked for again.
    expect(
      screen.getByRole('heading', {
        level: 3,
        name: es.opportunities.results.noMatchesTitle('cobol'),
      }),
    ).toBeInTheDocument()
    expect(screen.queryByText(es.opportunities.results.recentTitle)).not.toBeInTheDocument()

    await act(async () => release())
    expect(await countHeading(/vacantes?$/)).not.toHaveTextContent('0 vacantes')
    expect(screen.queryByText(es.opportunities.results.recentTitle)).not.toBeInTheDocument()
    expect(api.requested().filter((path) => path === recentPath)).toHaveLength(1)
  })

  it('announces the empty state in the live region and does not steal the focus (004-33)', async () => {
    mockBackend()
    renderApp('/oportunidades')
    await countHeading('12 vacantes')
    const field = screen.getByRole('textbox', { name: es.opportunities.queryLabel })
    await userEvent.type(field, 'cobol{Enter}')

    const title = await countHeading('0 vacantes')
    expect(title.closest('[aria-live="polite"]')).not.toBeNull()
    await screen.findByText(es.opportunities.results.recentTitle)
    expect(field).toHaveFocus()
  })
})

describe('Page /oportunidades: refreshing keeps the list (004-19)', () => {
  /** Backend whose answers for `slowPart` wait until `release()` is called. */
  function slowBackend(slowPart: string) {
    const api = mockBackend()
    const real = api.fetch.getMockImplementation() as (
      input: RequestInfo | URL,
    ) => Promise<Response>
    let release: () => void = () => {}
    api.fetch.mockImplementation(async (input: RequestInfo | URL) => {
      if (String(input).includes(slowPart)) {
        await new Promise<void>((resolve) => (release = resolve))
      }
      return real(input)
    })
    return { release: () => release() }
  }

  it('keeps the previous list, the same title and no skeletons while a new answer arrives', async () => {
    const slow = slowBackend('modality=REMOTE')
    const { router } = renderApp('/oportunidades')
    await countHeading('12 vacantes')

    await act(() => router.navigate('/oportunidades?modalidad=remoto'))

    const results = screen.getByRole('region', { name: '12 vacantes' })
    expect(results).toHaveAttribute('aria-busy', 'true')
    expect(results).toHaveAttribute('data-refreshing', 'true')
    expect(resultsTitle()).toHaveTextContent('12 vacantes')
    expect(screen.queryByText(es.opportunities.results.searching)).not.toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(9)
    expect(results.querySelectorAll('.skeleton')).toHaveLength(0)

    await act(async () => slow.release())
    expect(await countHeading('4 vacantes')).toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(4)
    expect(screen.getByRole('region', { name: '4 vacantes' })).toHaveAttribute('aria-busy', 'false')
  })

  it('keeps the previous list when the page changes, too', async () => {
    const slow = slowBackend('page=2')
    renderApp('/oportunidades')
    await countHeading('12 vacantes')
    await userEvent.click(screen.getByRole('link', { name: 'Página 2' }))

    expect(resultsTitle()).toHaveTextContent('12 vacantes')
    expect(screen.getAllByRole('article')).toHaveLength(9)
    expect(screen.getByRole('region', { name: '12 vacantes' })).toHaveAttribute('aria-busy', 'true')

    await act(async () => slow.release())
    expect(await screen.findByText('Mostrando 10–12 de 12')).toBeInTheDocument()
    expect(screen.getAllByRole('article')).toHaveLength(3)
  })

  it('still ends with the answer of the LAST request while the old list is kept', async () => {
    const slow = slowBackend('modality=REMOTE')
    const { router } = renderApp('/oportunidades')
    await countHeading('12 vacantes')

    await act(() => router.navigate('/oportunidades?modalidad=remoto')) // slow
    expect(resultsTitle()).toHaveTextContent('12 vacantes')
    await act(() => router.navigate('/oportunidades?modalidad=presencial')) // fast
    expect(await countHeading('3 vacantes')).toBeInTheDocument()

    await act(async () => {
      slow.release()
      await new Promise((resolve) => setTimeout(resolve, 20))
    })
    expect(resultsTitle()).toHaveTextContent('3 vacantes')
    expect(screen.getAllByRole('article')).toHaveLength(3)
  })

  it('shows skeletons only on the first load, and a failed refresh shows the error', async () => {
    const api = mockBackend()
    const { router } = renderApp('/oportunidades')
    expect(
      screen
        .getByRole('region', { name: es.opportunities.results.searching })
        .querySelectorAll('.skeleton').length,
    ).toBeGreaterThan(0)
    await countHeading('12 vacantes')

    api.setFailing({ '/vacancies': 500 })
    await act(() => router.navigate('/oportunidades?modalidad=remoto'))
    expect(await screen.findByRole('alert')).toHaveTextContent(es.opportunities.results.errorTitle)
    expect(screen.queryAllByRole('article')).toHaveLength(0)
  })
})

describe('Vacancy card in the list (004-20)', () => {
  it('has the title as the only link and shows company, city, modality, place, hours and date', async () => {
    mockBackend()
    renderApp('/oportunidades?q=ios')
    await countHeading('1 vacante')

    const card = screen.getByRole('article')
    const links = within(card).getAllByRole('link')
    expect(links).toHaveLength(1)
    expect(links[0]).toHaveAccessibleName('Practicante iOS')
    expect(links[0]?.getAttribute('href')).toMatch(/^\/oportunidades\/[\w-]+$/)
    expect(card).toHaveTextContent('Ruta Labs · Lima')
    expect(card).toHaveTextContent('Presencial')
    expect(card).toHaveTextContent('20 h por semana')
    expect(card).toHaveTextContent(/Publicada/)
    expect(card).toHaveTextContent('RL')
  })

  it('shows at most 4 technologies and "+N" for the rest', async () => {
    mockBackend()
    renderApp('/oportunidades?q=backend')
    await countHeading('1 vacante')
    const card = screen.getByRole('article')
    const tags = within(card).getByRole('list', { name: es.vacancies.skillsLabel })
    expect(within(tags).getAllByRole('listitem')).toHaveLength(5) // 4 + "+1"
    expect(within(tags).getByLabelText('y 1 tecnología más')).toHaveTextContent('+1')
    expect(within(tags).getByText('Java')).toBeInTheDocument()
    expect(within(tags).queryByText('Docker')).not.toBeInTheDocument()
  })

  it('never shows a percentage or a ring', async () => {
    mockBackend()
    renderApp('/oportunidades')
    await countHeading('12 vacantes')
    const list = screen.getByRole('list', { name: es.opportunities.results.listLabel })
    expect(list).not.toHaveTextContent('%')
    expect(list.querySelector('.ring')).toBeNull()
  })

  it('says the data are examples at the top and at the bottom of the list', async () => {
    mockBackend()
    renderApp('/oportunidades')
    await countHeading('12 vacantes')
    expect(screen.getByText(es.vacancies.sampleData)).toBeInTheDocument()
    expect(screen.getByText(es.vacancies.sampleFootnote)).toBeInTheDocument()
  })
})
