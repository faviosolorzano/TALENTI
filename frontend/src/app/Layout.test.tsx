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

  it('shows the main navigation with its four links', () => {
    renderApp('/')

    const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })
    const links = within(nav).getAllByRole('link')

    expect(links.map((link) => link.textContent)).toEqual([
      es.nav.home,
      es.nav.register,
      es.nav.login,
      es.nav.dashboard,
    ])
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
    expect(within(nav).getByRole('link', { name: es.nav.home })).not.toHaveAttribute('aria-current')
  })

  it('navigates to another page when a navigation link is used', async () => {
    renderApp('/')

    const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })
    await userEvent.click(within(nav).getByRole('link', { name: es.nav.dashboard }))

    expect(screen.getByRole('heading', { level: 1, name: es.pages.dashboard.title })).toBeVisible()
  })

  it('updates the document title on every route change, not only on the placeholder pages', async () => {
    renderApp('/')
    const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })

    const steps = [
      [es.nav.register, es.pages.register.title],
      [es.nav.login, es.pages.login.title],
      [es.nav.dashboard, es.pages.dashboard.title],
      [es.nav.home, es.pages.home.title],
    ] as const

    for (const [linkName, title] of steps) {
      await userEvent.click(within(nav).getByRole('link', { name: linkName }))
      expect(document.title).toBe(`${title} · ${es.app.name}`)
    }
  })

  it('moves focus to the main content when the route changes', async () => {
    renderApp('/')
    const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })

    await userEvent.click(within(nav).getByRole('link', { name: es.nav.login }))

    expect(screen.getByRole('main')).toHaveFocus()
  })

  it('does not steal focus on the first load of the page', () => {
    renderApp('/panel')

    expect(screen.getByRole('main')).not.toHaveFocus()
  })
})
