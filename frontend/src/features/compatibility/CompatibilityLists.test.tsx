import { screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { es } from '../../i18n/es'
import { clearCsrfCookie } from '../../test/fakeIdentity'
import { openStudentApp } from '../../test/studentApp'

afterEach(clearCsrfCookie)

const t = es.compatibility
const o = es.opportunities
const summaryCalls = (requests: string[]) =>
  requests.filter((path) => path.startsWith('/compatibility/summaries'))
const cards = () => screen.findAllByRole('article')

describe('Cards of /oportunidades (007-23)', () => {
  it('asks for the compatibility of the page ONCE and draws a badge on each card', async () => {
    const { compatibility } = openStudentApp('/oportunidades')
    const list = await cards()
    expect(list).toHaveLength(9)
    await waitFor(() => expect(screen.getAllByText(/\d+ % de compatibilidad/)).toHaveLength(9))

    const calls = summaryCalls(compatibility.requests)
    expect(calls).toHaveLength(1)
    expect(new URL(`http://x${calls[0]}`).searchParams.getAll('vacancyId')).toHaveLength(9)
    expect(within(list[0] as HTMLElement).getByText(t.badge(90))).toBeInTheDocument()
  })

  it('asks again only when the page of results changes', async () => {
    const { compatibility, user } = openStudentApp('/oportunidades')
    await waitFor(() => expect(summaryCalls(compatibility.requests)).toHaveLength(1))
    await user.click(await screen.findByRole('link', { name: o.pagination.page(2) }))
    await waitFor(() => expect(summaryCalls(compatibility.requests)).toHaveLength(2))
    await waitFor(() => expect(screen.getAllByRole('article')).toHaveLength(3))
    await waitFor(() => expect(screen.getAllByText(/\d+ % de compatibilidad/)).toHaveLength(3))
  })

  it('with an insufficient profile there is ONE line over the list and no badges', async () => {
    openStudentApp('/oportunidades', { compatibility: { sufficient: false } })
    await cards()
    const line = await screen.findByText(o.completeLine, { exact: false })
    expect(screen.getAllByText(o.completeLine, { exact: false })).toHaveLength(1)
    expect(
      within(line.parentElement as HTMLElement).getByRole('link', { name: o.completeAction }),
    ).toHaveAttribute('href', '/perfil?seccion=habilidades')
    expect(screen.queryByText(/de compatibilidad/)).not.toBeInTheDocument()
  })

  it('if the call fails the cards show with no badge and no error', async () => {
    const { compatibility } = openStudentApp('/oportunidades', {
      compatibility: { failing: { '/compatibility/summaries': 500 } },
    })
    await cards()
    await waitFor(() => expect(summaryCalls(compatibility.requests)).toHaveLength(1))
    expect(screen.queryByText(/de compatibilidad/)).not.toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('a visitor sees nothing new: no badge, no switch and no call', async () => {
    const { compatibility } = openStudentApp('/oportunidades', { signedOut: true })
    await cards()
    expect(screen.queryByText(/de compatibilidad/)).not.toBeInTheDocument()
    expect(screen.queryByRole('navigation', { name: o.view.label })).not.toBeInTheDocument()
    expect(compatibility.requests).toEqual([])
  })
})

describe('"Para ti" view (007-24)', () => {
  it('a student sees the switch with "Para ti" selected and the recommended ones, highest first, with badges', async () => {
    openStudentApp('/oportunidades?vista=para-ti')
    const switchNav = await screen.findByRole('navigation', { name: o.view.label })
    expect(within(switchNav).getByRole('link', { name: o.view.forYou })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(switchNav).getByRole('link', { name: o.view.all })).not.toHaveAttribute(
      'aria-current',
    )

    const list = await screen.findByRole('list', { name: o.forYou.listLabel })
    const items = within(list).getAllByRole('article')
    expect(items).toHaveLength(9)
    expect(within(items[0] as HTMLElement).getByText(t.badge(90))).toBeInTheDocument()
    expect(within(items[1] as HTMLElement).getByText(t.badge(83))).toBeInTheDocument()
  })

  it('does not combine with search and filters, and says so', async () => {
    openStudentApp('/oportunidades?vista=para-ti')
    await screen.findByRole('list', { name: o.forYou.listLabel })
    expect(screen.getByText(o.forYou.notCombined)).toBeInTheDocument()
    expect(screen.queryByRole('search')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: o.submit })).not.toBeInTheDocument()
    expect(screen.queryByText(o.filters.title)).not.toBeInTheDocument()
  })

  it('is paginated and the card links go back to this view', async () => {
    const { user, router } = openStudentApp('/oportunidades?vista=para-ti')
    await screen.findByRole('list', { name: o.forYou.listLabel })
    await user.click(screen.getByRole('link', { name: o.pagination.page(2) }))
    await waitFor(() => expect(router.state.location.search).toBe('?vista=para-ti&pagina=2'))
    await waitFor(() => expect(screen.getAllByRole('article')).toHaveLength(3))

    await user.click(screen.getAllByRole('link', { name: /Practicante/ })[0] as HTMLElement)
    const back = await screen.findByRole('link', { name: es.vacancyDetail.back })
    expect(back).toHaveAttribute('href', '/oportunidades?vista=para-ti&pagina=2')
  })

  it('goes to the last page when the page asked is beyond it', async () => {
    const { router } = openStudentApp('/oportunidades?vista=para-ti&pagina=9')
    await waitFor(() => expect(router.state.location.search).toBe('?vista=para-ti&pagina=2'))
  })

  it('with an insufficient profile it shows the message and "Completar mi perfil" instead of the list', async () => {
    openStudentApp('/oportunidades?vista=para-ti', { compatibility: { sufficient: false } })
    expect(await screen.findByText(o.forYou.insufficientTitle)).toBeInTheDocument()
    expect(screen.getByRole('link', { name: o.forYou.action })).toHaveAttribute(
      'href',
      '/perfil?seccion=habilidades',
    )
    expect(screen.queryByRole('list', { name: o.forYou.listLabel })).not.toBeInTheDocument()
  })

  it('without open vacancies it says "Todavía no hay vacantes abiertas"', async () => {
    openStudentApp('/oportunidades?vista=para-ti', { vacancies: { vacancies: [] } })
    expect(await screen.findByText(o.forYou.emptyTitle)).toBeInTheDocument()
  })

  it('an error is an alert in the list only and "Reintentar" repeats it', async () => {
    const { compatibility, user } = openStudentApp('/oportunidades?vista=para-ti', {
      compatibility: { failing: { '/compatibility/recommended': 500 } },
    })
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(o.forYou.error)
    compatibility.setFailing({})
    await user.click(within(alert).getByRole('button', { name: es.common.retry }))
    expect(await screen.findByRole('list', { name: o.forYou.listLabel })).toBeInTheDocument()
  })

  it('a visitor: the parameter is ignored and the address is corrected', async () => {
    const { router, compatibility } = openStudentApp('/oportunidades?vista=para-ti', {
      signedOut: true,
    })
    expect(await screen.findByRole('list', { name: o.results.listLabel })).toBeInTheDocument()
    await waitFor(() => expect(router.state.location.search).toBe(''))
    expect(screen.queryByRole('navigation', { name: o.view.label })).not.toBeInTheDocument()
    expect(compatibility.requests).toEqual([])
  })

  it('the switch goes back to "Todas" with the usual list', async () => {
    const { user } = openStudentApp('/oportunidades?vista=para-ti')
    await screen.findByRole('list', { name: o.forYou.listLabel })
    await user.click(screen.getByRole('link', { name: o.view.all }))
    expect(await screen.findByRole('list', { name: o.results.listLabel })).toBeInTheDocument()
    expect(screen.getByRole('search')).toBeInTheDocument()
  })
})

describe('Privacy of the result (007-26)', () => {
  it('nothing about the compatibility goes to the browser storage', async () => {
    openStudentApp('/oportunidades')
    await waitFor(() => expect(screen.getAllByText(/\d+ % de compatibilidad/)).toHaveLength(9))
    expect(JSON.stringify({ ...localStorage })).not.toMatch(/compatib|percent/i)
    expect(JSON.stringify({ ...sessionStorage })).not.toMatch(/compatib|percent/i)
  })
})
