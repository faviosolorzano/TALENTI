import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { es } from '../../i18n/es'
import { mockLegalApi } from '../../test/fakeLegal'
import { renderApp } from '../../test/renderApp'

describe('Page /transparencia (002-05, 002-10)', () => {
  // 002-10
  it('002-10: explains data, purposes, sharing, retention, DORA and rights in plain language', () => {
    renderApp('/transparencia')

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(
      screen.getAllByRole('heading', { level: 2 }).map((heading) => heading.textContent),
    ).toEqual([
      'En pocas palabras',
      'Qué datos usamos',
      'Para qué los usamos',
      'Con quién los compartimos',
      'Cuánto tiempo los conservamos',
      'Cómo funciona DORA',
      'Tus derechos y tu control',
    ])
  })

  // 002-10
  it('002-10: says that DORA is an AI, that the AI suggests and the user confirms, and that the system calculates the percentage', () => {
    renderApp('/transparencia')

    const dora = screen.getByRole('region', { name: 'Cómo funciona DORA' })
    expect(dora).toHaveTextContent('inteligencia artificial')
    expect(dora).toHaveTextContent('sugiere')
    expect(dora).toHaveTextContent('confirmas')
    expect(dora).toHaveTextContent('porcentaje de compatibilidad no lo calcula la IA')
    expect(dora).toHaveTextContent('lo calcula el sistema')
  })

  // 002-10
  it('002-10: links every block to its section of the Privacy Policy', () => {
    renderApp('/transparencia')

    const expected: Record<string, string[]> = {
      'En pocas palabras': ['/privacidad'],
      'Qué datos usamos': ['/privacidad#seccion-2', '/privacidad#seccion-3'],
      'Para qué los usamos': ['/privacidad#seccion-4'],
      'Con quién los compartimos': ['/privacidad#seccion-5', '/privacidad#seccion-6'],
      'Cuánto tiempo los conservamos': ['/privacidad#seccion-7'],
      'Cómo funciona DORA': ['/privacidad#seccion-8'],
      'Tus derechos y tu control': ['/privacidad#seccion-10'],
    }
    for (const [heading, hrefs] of Object.entries(expected)) {
      const block = screen.getByRole('region', { name: heading })
      const found = within(block)
        .getAllByRole('link')
        .map((link) => link.getAttribute('href'))
      expect(found).toEqual(hrefs)
    }
  })

  // 002-10 and 002-04
  it('002-10/002-04: opens the linked section of the Privacy Policy, with focus on its heading', async () => {
    mockLegalApi()
    const { router } = renderApp('/transparencia')

    await userEvent.click(screen.getByRole('link', { name: 'Ver todos los datos' }))

    const heading = await screen.findByRole('heading', { level: 2, name: '2. Qué datos tratamos' })
    expect(router.state.location.pathname + router.state.location.hash).toBe(
      '/privacidad#seccion-2',
    )
    await screen.findByRole('heading', { level: 1, name: 'Política de Privacidad de Talenti' })
    expect(heading).toHaveFocus()
  })

  // 002-05
  it('002-05: shows the draft notice', () => {
    renderApp('/transparencia')
    expect(screen.getByText(es.legal.draftNotice)).toBeVisible()
  })

  it('does not call the API: the text is part of the interface', () => {
    const api = mockLegalApi()
    renderApp('/transparencia')
    expect(api.calls()).toEqual([])
  })
})
