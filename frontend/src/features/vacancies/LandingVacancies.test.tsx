import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { es } from '../../i18n/es'
import { mockBackend } from '../../test/fakeBackend'
import { renderApp } from '../../test/renderApp'

const before = (a: Node, b: Node) =>
  Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)

const featuredRegion = () => screen.findByRole('region', { name: /Vacantes destacadas/ })

describe('Home page: featured vacancies (004-15)', () => {
  it('orders the approved hero, flat vacancy rows and short help with one title', async () => {
    mockBackend()
    renderApp('/')
    const featured = await featuredRegion()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(before(screen.getByRole('heading', { level: 1 }), featured)).toBe(true)
    expect(before(featured, screen.getByRole('region', { name: es.nav.how }))).toBe(true)
    expect(screen.queryByRole('region', { name: es.dora.title })).toBeNull()
  })
  it('shows up to 4 featured vacancies and the real count of open ones in the heading', async () => {
    const api = mockBackend()
    renderApp('/')
    const featured = await featuredRegion()

    expect(within(featured).getAllByRole('article')).toHaveLength(4)
    expect(
      within(featured).getByRole('heading', { level: 2, name: /12 vacantes abiertas/ }),
    ).toBeInTheDocument()
    expect(api.requested()).toContain('/vacancies/featured?limit=4')
    expect(api.requested()).toContain('/vacancies/filters')
  })

  it('links to all the vacancies and says the data are examples', async () => {
    mockBackend()
    renderApp('/')
    const featured = await featuredRegion()

    expect(
      within(featured).getByRole('link', { name: new RegExp(es.landingVacancies.seeAll) }),
    ).toHaveAttribute('href', '/oportunidades')
    expect(within(featured).queryByText(es.footer.sampleNote)).not.toBeInTheDocument()
  })

  it('shows a vacancy with company, city, modality, hours and technologies, and never a percentage', async () => {
    mockBackend()
    renderApp('/')
    const featured = await featuredRegion()

    const card = within(featured).getAllByRole('article')[0] as HTMLElement
    expect(within(card).getByRole('link')).toHaveAccessibleName('Practicante Backend Java')
    expect(card).toHaveTextContent('Andes Tech · Lima')
    expect(card).toHaveTextContent('Remoto')
    expect(card).toHaveTextContent('20 h por semana')
    expect(within(card).getByText('Spring Boot')).toBeInTheDocument()
    expect(within(featured).queryByText(/%/)).not.toBeInTheDocument()
    expect(featured.querySelector('.ring')).toBeNull()
  })

  it('keeps an explicit empty state and the exploration action', async () => {
    mockBackend({ vacancies: [] })
    renderApp('/')
    const featured = await featuredRegion()
    expect(within(featured).getByText(es.home.vacancies.empty)).toBeInTheDocument()
    expect(within(featured).getByRole('link')).toHaveAttribute('href', '/oportunidades')
  })
  it('shows skeletons while loading', () => {
    mockBackend()
    renderApp('/')
    const busy = document.querySelectorAll('[aria-busy="true"]')
    expect(busy.length).toBeGreaterThanOrEqual(1)
  })

  it('a featured error is local and retry recovers without replacing the hero', async () => {
    const api = mockBackend({ failing: { '/vacancies/featured': 500 } })
    renderApp('/')
    const region = await featuredRegion()
    const alert = await within(region).findByRole('alert')
    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument()
    api.setFailing({})
    await userEvent.click(within(alert).getByRole('button', { name: es.common.retry }))
    expect(await within(region).findAllByRole('article')).toHaveLength(4)
  })
})

describe('Home page: concise composition', () => {
  it('does not request the promotional company strip', async () => {
    const api = mockBackend()
    renderApp('/')
    await featuredRegion()
    expect(api.requested().some((p) => p.includes('/companies/featured'))).toBe(false)
  })
  it('preserves a live search action rather than baking it into the scene', async () => {
    mockBackend()
    renderApp('/')
    expect(screen.getByRole('link', { name: es.hero.primaryAction })).toHaveAttribute(
      'href',
      '/oportunidades',
    )
  })
})
