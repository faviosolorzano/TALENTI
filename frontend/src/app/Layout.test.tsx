import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { es } from '../i18n/es'
import { renderApp } from '../test/renderApp'

describe('Layout', () => {
  it('shows the "skip to content" link pointing to the main area', () => {
    renderApp('/')

    const skipLink = screen.getByRole('link', { name: es.app.skipToContent })
    expect(skipLink).toHaveAttribute('href', '#contenido')
    expect(screen.getByRole('main')).toHaveAttribute('id', 'contenido')
  })

  it('makes the skip link the first element reachable with the keyboard', async () => {
    renderApp('/')

    await userEvent.tab()

    expect(screen.getByRole('link', { name: es.app.skipToContent })).toHaveFocus()
  })

  it('shows the main navigation: Inicio, Oportunidades, Cómo funciona and DORA (and Ingresar in the folded menu)', () => {
    renderApp('/')

    const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })
    const links = within(nav).getAllByRole('link')

    expect(links.map((link) => link.textContent)).toEqual([
      es.nav.opportunities,
      es.nav.how,
      es.nav.login,
    ])
  })

  it('shows "Ingresar" and "Crear cuenta" at the right of the header', () => {
    renderApp('/')

    const banner = screen.getByRole('banner')
    expect(within(banner).getByRole('link', { name: es.nav.login })).toHaveAttribute(
      'href',
      '/ingresar',
    )
    expect(within(banner).getAllByRole('link', { name: es.nav.login })[0]).toHaveAttribute(
      'href',
      '/ingresar',
    )
  })

  it('shows the logo in the top bar', () => {
    renderApp('/')

    const banner = screen.getByRole('banner')
    expect(within(banner).getByRole('img', { name: es.app.logoAlt })).toBeInTheDocument()
  })

  it('marks the link of the current page for assistive technology', () => {
    renderApp('/ingresar')

    const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })
    expect(within(nav).getByRole('link', { name: es.nav.login })).toHaveAttribute(
      'aria-current',
      'page',
    )
    expect(within(nav).getByRole('link', { name: es.nav.opportunities })).not.toHaveAttribute(
      'aria-current',
    )
  })

  it('navigates to another page when a navigation link is used', async () => {
    renderApp('/')

    const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })
    await userEvent.click(within(nav).getByRole('link', { name: es.nav.login }))

    expect(screen.getByRole('heading', { level: 1, name: es.identity.login.title })).toBeVisible()
  })

  it('updates the document title on every route change, not only on the placeholder pages', async () => {
    renderApp('/')
    const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })
    const banner = screen.getByRole('banner')

    await userEvent.click(within(banner).getByRole('link', { name: es.nav.login }))
    await userEvent.click(
      within(screen.getByRole('main')).getByRole('link', { name: es.identity.login.goToRegister }),
    )
    expect(document.title).toBe(`${es.pages.register.title} · ${es.app.name}`)

    await userEvent.click(within(nav).getByRole('link', { name: es.nav.login }))
    expect(document.title).toBe(`${es.pages.login.title} · ${es.app.name}`)

    await userEvent.click(within(banner).getByRole('link', { name: es.app.homeLink }))
    expect(document.title).toBe(`${es.pages.home.title} · ${es.app.name}`)
  })

  it('scrolls to the section of a hash link and moves focus to it', async () => {
    renderApp('/')
    const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })

    await userEvent.click(within(nav).getByRole('link', { name: es.nav.how }))

    const section = document.getElementById('como-funciona')
    expect(section?.scrollIntoView).toHaveBeenCalled()
    expect(section).toHaveFocus()
  })

  it('moves focus to the main content when the route changes', async () => {
    renderApp('/')
    const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })

    await userEvent.click(within(nav).getByRole('link', { name: es.nav.login }))

    expect(screen.getByRole('main')).toHaveFocus()
  })

  it('does not steal focus on the first load of the page', () => {
    renderApp('/oportunidades')

    expect(screen.getByRole('main')).not.toHaveFocus()
  })
})
