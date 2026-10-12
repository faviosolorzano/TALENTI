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
  it('orders hero, card, featured vacancies, companies, "Cómo funciona" and DORA, with one h1', async () => {
    mockBackend()
    renderApp('/')
    const featured = await featuredRegion()
    const companies = await screen.findByRole('region', {
      name: es.landingVacancies.companiesTitle,
    })
    const h1 = screen.getByRole('heading', { level: 1 })
    const card = screen.getByRole('article', { name: es.card.title })
    const how = screen.getByRole('region', { name: es.how.title })
    const dora = screen.getByRole('region', { name: es.dora.title })

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(before(h1, card)).toBe(true)
    expect(before(card, featured)).toBe(true)
    expect(before(featured, companies)).toBe(true)
    expect(before(companies, how)).toBe(true)
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
    expect(within(featured).getByText(es.vacancies.sampleData)).toBeInTheDocument()
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

  it('is hidden when there are no featured vacancies and no companies', async () => {
    mockBackend({ vacancies: [] })
    renderApp('/')
    await waitFor(() =>
      expect(
        screen.queryByRole('region', { name: es.landingVacancies.companiesTitle }),
      ).not.toBeInTheDocument(),
    )
    expect(screen.queryByRole('region', { name: /Vacantes destacadas/ })).not.toBeInTheDocument()
    expect(screen.getByRole('region', { name: es.how.title })).toBeInTheDocument()
  })

  it('shows skeletons while loading', () => {
    mockBackend()
    renderApp('/')
    const busy = document.querySelectorAll('[aria-busy="true"]')
    expect(busy.length).toBeGreaterThanOrEqual(2)
  })

  it('shows an error with "Reintentar" only in the section that failed, and retrying recovers', async () => {
    const api = mockBackend({ failing: { '/vacancies/featured': 500 } })
    renderApp('/')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(es.landingVacancies.featuredError)
    // The strip of companies and the rest of the page keep working.
    expect(
      await screen.findByRole('region', { name: es.landingVacancies.companiesTitle }),
    ).toBeInTheDocument()
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(screen.getByRole('region', { name: es.how.title })).toBeInTheDocument()

    api.setFailing({})
    await userEvent.click(within(alert).getByRole('button', { name: es.common.retry }))
    expect(await screen.findAllByRole('article', { name: /Practicante/ })).not.toHaveLength(0)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('Home page: strip of companies (004-16)', () => {
  it('shows each company with avatar of initials, name, city and number of vacancies', async () => {
    mockBackend()
    renderApp('/')
    const strip = await screen.findByRole('region', { name: es.landingVacancies.companiesTitle })
    const items = within(strip).getAllByRole('link')

    expect(items).toHaveLength(4)
    const nexa = items.find((link) => link.textContent?.includes('Nexa Digital')) as HTMLElement
    expect(nexa).toHaveTextContent('ND')
    expect(nexa).toHaveTextContent('Lima')
    expect(nexa).toHaveTextContent('2 vacantes')
    const andes = items.find((link) => link.textContent?.includes('Andes Tech')) as HTMLElement
    expect(andes).toHaveTextContent('1 vacante')
    expect(andes).not.toHaveTextContent('1 vacantes')
  })

  it('leads each company to /oportunidades?q=name', async () => {
    mockBackend()
    renderApp('/')
    const strip = await screen.findByRole('region', { name: es.landingVacancies.companiesTitle })
    expect(within(strip).getByRole('link', { name: /Nexa Digital/ })).toHaveAttribute(
      'href',
      '/oportunidades?q=Nexa+Digital',
    )
    expect(within(strip).getByRole('link', { name: /Cóndor Datos/ })).toHaveAttribute(
      'href',
      '/oportunidades?q=C%C3%B3ndor+Datos',
    )
  })

  it('loads no image and gives the avatar the tone of the company, always the same', async () => {
    mockBackend()
    const { unmount } = renderApp('/')
    const strip = await screen.findByRole('region', { name: es.landingVacancies.companiesTitle })
    expect(strip.querySelector('img')).toBeNull()
    const tones = [...strip.querySelectorAll('.company-avatar')].map((el) =>
      el.getAttribute('data-tone'),
    )
    expect(tones.every((tone) => ['1', '2', '3'].includes(tone ?? ''))).toBe(true)
    unmount()

    renderApp('/')
    const again = await screen.findByRole('region', { name: es.landingVacancies.companiesTitle })
    expect(
      [...again.querySelectorAll('.company-avatar')].map((el) => el.getAttribute('data-tone')),
    ).toEqual(tones)
  })

  it('shows its own error with "Reintentar" without affecting the featured vacancies', async () => {
    mockBackend({ failing: { '/companies/featured': 500 } })
    renderApp('/')
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(es.landingVacancies.companiesError)
    expect(within(alert).getByRole('button', { name: es.common.retry })).toBeInTheDocument()
    expect(await featuredRegion()).toBeInTheDocument()
  })
})
