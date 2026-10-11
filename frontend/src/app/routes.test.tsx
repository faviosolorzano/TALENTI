import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { es } from '../i18n/es'
import { renderApp } from '../test/renderApp'

describe('Provisional routes', () => {
  it.each([
    ['/', es.pages.home.title],
    ['/registro', es.pages.register.title],
    ['/ingresar', es.pages.login.title],
    ['/panel', es.pages.dashboard.title],
  ])('route %s renders its page with the "under construction" notice', (path, title) => {
    renderApp(path)

    expect(screen.getByRole('heading', { level: 1, name: title })).toBeInTheDocument()
    expect(screen.getByText(es.placeholder.underConstruction)).toBeInTheDocument()
    expect(document.title).toBe(`${title} · ${es.app.name}`)
  })

  it('an unknown route shows the "page not found" screen with a way back home', () => {
    renderApp('/no-existe')

    expect(
      screen.getByRole('heading', { level: 1, name: es.pages.notFound.title }),
    ).toBeInTheDocument()
    expect(screen.getByRole('link', { name: es.pages.notFound.backHome })).toHaveAttribute(
      'href',
      '/',
    )
  })
})
