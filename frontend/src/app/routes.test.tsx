import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { es } from '../i18n/es'
import { renderApp } from '../test/renderApp'

describe('Routes', () => {
  it('route / renders the informative landing page instead of the notice', () => {
    renderApp('/')

    expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(/lo que ya sabes hacer/)
    expect(screen.queryByText(es.placeholder.underConstruction)).not.toBeInTheDocument()
    expect(document.title).toBe(`${es.pages.home.title} · ${es.app.name}`)
  })

  it.each([
    ['/registro', es.identity.register.title, es.pages.register.title],
    ['/ingresar', es.identity.login.title, es.pages.login.title],
    ['/recuperar-cuenta', es.identity.recover.title, es.identity.recover.title],
  ])('route %s renders its real page (no "under construction" notice)', (path, heading, title) => {
    renderApp(path)

    expect(screen.getByRole('heading', { level: 1, name: heading })).toBeInTheDocument()
    expect(screen.queryByText(es.placeholder.underConstruction)).not.toBeInTheDocument()
    expect(document.title).toBe(`${title} · ${es.app.name}`)
  })

  it('route /panel is private: a visitor is taken to /ingresar', async () => {
    const { router } = renderApp('/panel')

    expect(
      await screen.findByRole('heading', { level: 1, name: es.identity.login.title }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/ingresar')
  })

  it('route /aceptar-documentos is the real screen of the legal slice: a visitor is sent to sign in, not to "not found"', async () => {
    const { router } = renderApp('/aceptar-documentos?returnTo=%2Foportunidades')

    expect(
      await screen.findByRole('heading', { level: 1, name: es.identity.login.title }),
    ).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/ingresar')
    expect(router.state.location.search).toBe('?returnTo=%2Foportunidades')
    expect(screen.queryByText(es.placeholder.underConstruction)).not.toBeInTheDocument()
  })

  it('an unknown route shows the "page not found" screen with a way back home', () => {
    renderApp('/no-existe')

    expect(
      screen.getByRole('heading', { level: 1, name: es.pages.notFound.title }),
    ).toBeInTheDocument()
    expect(document.title).toBe(`${es.pages.notFound.title} · ${es.app.name}`)
    expect(screen.getByRole('link', { name: es.pages.notFound.backHome })).toHaveAttribute(
      'href',
      '/',
    )
  })
})
