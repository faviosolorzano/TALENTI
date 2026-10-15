import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { es } from '../i18n/es'
import { mockLegalApi } from '../test/fakeLegal'
import { renderApp } from '../test/renderApp'

describe('Footer on every screen (002-08)', () => {
  // 002-08
  it.each([
    '/',
    '/oportunidades',
    '/registro',
    '/ingresar',
    '/panel',
    '/no-existe',
    '/transparencia',
  ])('002-08: shows the legal links in order and the cookie preferences button on %s', (path) => {
    mockLegalApi()
    renderApp(path)

    const footer = screen.getByRole('contentinfo')
    const items = within(footer)
      .getAllByRole('listitem')
      .map((item) => item.textContent)
    expect(items).toEqual([
      'Términos y Condiciones',
      'Política de Privacidad',
      'Política de Cookies',
      'Transparencia',
      'Preferencias de cookies',
    ])
    const links = within(footer).getAllByRole('link')
    expect(links.map((link) => link.getAttribute('href'))).toEqual([
      '/terminos',
      '/privacidad',
      '/cookies',
      '/transparencia',
    ])
    expect(
      within(footer).getByRole('button', { name: es.footer.cookiePreferences }),
    ).toBeInTheDocument()
  })

  // 002-08
  it('002-08: has exactly one contentinfo and it comes after the main content', () => {
    renderApp('/panel')

    const footers = screen.getAllByRole('contentinfo')
    expect(footers).toHaveLength(1)
    expect(
      screen.getByRole('main').compareDocumentPosition(footers[0] as HTMLElement) &
        Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy()
  })

  it('shows, only in the footer, that the companies and vacancies are made up', () => {
    renderApp('/')

    const footer = screen.getByRole('contentinfo')
    expect(within(footer).getByText(es.footer.sampleNote)).toBeInTheDocument()
    expect(screen.getAllByText(es.footer.sampleNote)).toHaveLength(1)
  })

  // 002-08
  it('002-08: is operated with the keyboard: Tab reaches each link and Enter opens it without reloading', async () => {
    mockLegalApi()
    const { router } = renderApp('/panel')
    await userEvent.click(screen.getByRole('button', { name: es.cookies.banner.reject }))
    const footer = screen.getByRole('contentinfo')

    within(footer).getByRole('link', { name: es.footer.privacy }).focus()
    await userEvent.keyboard('{Enter}')

    expect(router.state.location.pathname).toBe('/privacidad')
    await screen.findByRole('heading', { level: 1, name: 'Política de Privacidad de Talenti' })
  })

  // 002-08
  it('002-08: opens the cookie preferences with the keyboard from the footer button', async () => {
    renderApp('/panel')
    await userEvent.click(screen.getByRole('button', { name: es.cookies.banner.reject }))

    within(screen.getByRole('contentinfo'))
      .getByRole('button', { name: es.footer.cookiePreferences })
      .focus()
    await userEvent.keyboard('{Enter}')

    expect(screen.getByRole('dialog', { name: es.cookies.panel.title })).toBeInTheDocument()
  })

  // 002-08: the footer and the notice do not depend on the API
  it('002-08: shows the footer and the cookie notice even if the API is down', () => {
    mockLegalApi({ catalogStatus: 500 })
    renderApp('/terminos')

    expect(screen.getByRole('contentinfo')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: es.cookies.banner.label })).toBeInTheDocument()
  })
})
