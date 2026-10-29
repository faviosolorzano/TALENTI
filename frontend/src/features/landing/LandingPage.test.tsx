import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { es } from '../../i18n/es'
import { mockBackend } from '../../test/fakeBackend'
import { renderApp } from '../../test/renderApp'

describe('Inicio aprobado — E2', () => {
  it('tiene un título real, apoyo y una acción principal hacia Vacantes', async () => {
    mockBackend()
    const { router } = renderApp('/')
    const title = screen.getByRole('heading', { level: 1 })
    expect(title).toHaveTextContent('Tu primera práctica.')
    expect(screen.getByText('Con lo que ya sabes.')).toBeVisible()
    const hero = title.closest('section')!
    const action = within(hero).getByRole('link', { name: /Explorar vacantes/ })
    expect(action).toHaveAttribute('href', '/oportunidades')
    expect(within(hero).getAllByRole('link')).toHaveLength(1)
    await userEvent.click(action)
    expect(router.state.location.pathname).toBe('/oportunidades')
  })
  it('usa ilustraciones independientes y etiqueta el ejemplo sin inventar un resultado personal', () => {
    renderApp('/')
    const scene = screen.getByRole('img', { name: es.hero.sceneDescription })
    expect(scene).toHaveAttribute('src', '/illustrations/hero-desktop.webp')
    expect(scene.closest('picture')?.querySelector('source')).toHaveAttribute(
      'srcset',
      '/illustrations/hero-movil.webp',
    )
    expect(screen.getByText('Ejemplo', { exact: true })).toBeVisible()
    expect(screen.queryByRole('article', { name: es.card.title })).not.toBeInTheDocument()
  })
  it('mantiene ayuda breve y no promociona una sesión de voz inexistente en la portada', () => {
    renderApp('/')
    expect(screen.getByRole('region', { name: es.nav.how })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: es.dora.mic.start })).not.toBeInTheDocument()
  })
  it('carga vacantes reales del servicio sin requerir cuenta', async () => {
    const backend = mockBackend()
    renderApp('/')
    expect(await screen.findByRole('heading', { name: /Vacantes destacadas/ })).toBeVisible()
    expect(await screen.findAllByRole('article')).toHaveLength(4)
    expect(backend.requested()).toContain('/vacancies/featured?limit=4')
  })
})
