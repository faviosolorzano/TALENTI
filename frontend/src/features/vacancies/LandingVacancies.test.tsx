import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { es } from '../../i18n/es'
import { mockBackend } from '../../test/fakeBackend'
import { renderApp } from '../../test/renderApp'

const before = (a: Node, b: Node) =>
  Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING)

const featuredRegion = () => screen.findByRole('region', { name: /Vacantes destacadas/ })

describe('Home page: featured vacancies (004-15)', () => {
  it('orders hero, card, featured vacancies, "Cómo funciona" and DORA, with one h1', async () => {
    mockBackend()
    renderApp('/')
    const featured = await featuredRegion()
    const h1 = screen.getByRole('heading', { level: 1 })
    const card = screen.getByRole('article', { name: es.card.title })
    const how = screen.getByRole('region', { name: es.how.title })
    const dora = screen.getByRole('region', { name: es.dora.title })

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(before(h1, card)).toBe(true)
    expect(before(card, featured)).toBe(true)
    expect(before(featured, how)).toBe(true)
    expect(before(how, dora)).toBe(true)
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

  it('is hidden when there are no featured vacancies', async () => {
    mockBackend({ vacancies: [] })
    renderApp('/')
    await waitFor(() =>
      expect(screen.queryByRole('region', { name: /Vacantes destacadas/ })).not.toBeInTheDocument(),
    )
    expect(screen.getByRole('region', { name: es.how.title })).toBeInTheDocument()
  })

  it('shows skeletons while loading', () => {
    mockBackend()
    renderApp('/')
    const busy = document.querySelectorAll('[aria-busy="true"]')
    expect(busy.length).toBeGreaterThanOrEqual(1)
  })

  it('shows an error with "Reintentar" only in the section that failed, and retrying recovers', async () => {
    const api = mockBackend({ failing: { '/vacancies/featured': 500 } })
    renderApp('/')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(es.landingVacancies.featuredError)
    // The rest of the page keeps working.
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(screen.getByRole('region', { name: es.how.title })).toBeInTheDocument()

    api.setFailing({})
    await userEvent.click(within(alert).getByRole('button', { name: es.common.retry }))
    expect(await screen.findAllByRole('article', { name: /Practicante/ })).not.toHaveLength(0)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('Home page: no strip of companies (spec 011)', () => {
  it('draws neither the strip of companies nor avatars of initials, and does not ask for the companies', async () => {
    const api = mockBackend()
    renderApp('/')
    await featuredRegion()

    expect(screen.queryByRole('region', { name: 'Empresas que buscan talento' })).toBeNull()
    expect(document.querySelector('.company-avatar')).toBeNull()
    expect(api.requested().some((path) => path.startsWith('/companies'))).toBe(false)
  })
})
