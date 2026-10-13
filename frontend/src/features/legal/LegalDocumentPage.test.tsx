import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it } from 'vitest'
import { es } from '../../i18n/es'
import { mockLegalApi, sampleCatalog } from '../../test/fakeLegal'
import { renderApp } from '../../test/renderApp'

const privacyTitle = () =>
  screen.findByRole('heading', { level: 1, name: 'Política de Privacidad de Talenti' })

describe('Legal pages /terminos, /privacidad, /cookies (002-01 to 002-07)', () => {
  // 002-01
  it('002-01: shows the title as the only h1 and every section in the order received', async () => {
    mockLegalApi()
    renderApp('/privacidad')
    await privacyTitle()

    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    const headings = screen.getAllByRole('heading', { level: 2 }).map((h) => h.textContent)
    expect(headings).toEqual(['1. Quién es el responsable', '2. Qué datos tratamos', '3. Cookies'])
  })

  // 002-01
  it('002-01: shows each answer as it is, with another number of sections, without changing code', async () => {
    const catalog = sampleCatalog()
    catalog.documents[0] = {
      key: 'terms',
      title: 'Otros términos',
      sections: [
        { heading: 'Única', body: 'Texto único.' },
        { heading: 'Segunda', body: 'Texto dos.' },
        { heading: 'Tercera', body: 'Texto tres.' },
        { heading: 'Cuarta', body: 'Texto cuatro.' },
        { heading: 'Quinta', body: 'Texto cinco.' },
      ],
    }
    mockLegalApi({ catalog })
    renderApp('/terminos')

    await screen.findByRole('heading', { level: 1, name: 'Otros términos' })
    expect(screen.getAllByRole('heading', { level: 2 })).toHaveLength(5)
    expect(screen.getByText('Texto cinco.')).toBeInTheDocument()
  })

  // 002-01
  it('002-01: serves the three documents from their own route', async () => {
    mockLegalApi()
    const { unmount } = renderApp('/terminos')
    await screen.findByRole('heading', { level: 1, name: 'Términos y Condiciones de Talenti' })
    unmount()
    renderApp('/cookies')
    await screen.findByRole('heading', { level: 1, name: 'Política de Cookies de Talenti' })
  })

  // 002-02
  it('002-02: shows "Versión {version} · Publicada el {fecha}"', async () => {
    mockLegalApi()
    renderApp('/privacidad')
    await privacyTitle()

    expect(screen.getByText('Versión v1 · Publicada el 11 de octubre de 2026')).toBeInTheDocument()
  })

  // 002-02
  it.each([null, undefined, 'no es una fecha'])(
    '002-02: says the date is pending, and invents none, when publishedAt is %s',
    async (publishedAt) => {
      mockLegalApi({ catalog: sampleCatalog({ publishedAt }) })
      renderApp('/privacidad')
      await privacyTitle()

      expect(screen.getByText('Versión v1 · Fecha de publicación pendiente')).toBeInTheDocument()
      expect(screen.queryByText(/Publicada el/)).not.toBeInTheDocument()
    },
  )

  // 002-03
  it('002-03: shows lists, tables, bold text and links of the Markdown body', async () => {
    mockLegalApi()
    renderApp('/privacidad')
    await privacyTitle()

    expect(screen.getAllByRole('listitem').map((li) => li.textContent)).toEqual(
      expect.arrayContaining(['Tu correo', 'Tu perfil']),
    )
    expect(screen.getByRole('table')).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: 'Ejemplos' })).toBeInTheDocument()
    expect(screen.getByText('muy importante').tagName).toBe('STRONG')
    expect(screen.getByRole('link', { name: 'guía (se abre en otra pestaña)' })).toHaveAttribute(
      'href',
      'https://example.org/guia',
    )
  })

  // 002-03
  it('002-03: opens an internal link of the body without reloading the page', async () => {
    mockLegalApi()
    const { router } = renderApp('/privacidad')
    await privacyTitle()
    const marker = document.createElement('div')
    document.body.append(marker) // survives a SPA navigation, not a reload

    await userEvent.click(
      within(screen.getByRole('main')).getByRole('link', { name: 'Política de Cookies' }),
    )

    await screen.findByRole('heading', { level: 1, name: 'Política de Cookies de Talenti' })
    expect(router.state.location.pathname).toBe('/cookies')
    expect(marker.isConnected).toBe(true)
    marker.remove()
  })

  // 002-03
  it('002-03: does not interpret or show raw HTML or comments', async () => {
    const catalog = sampleCatalog()
    catalog.documents[1] = {
      key: 'privacy',
      title: 'Política de Privacidad de Talenti',
      sections: [
        {
          heading: '1. Prueba',
          body: [
            'Antes <!-- comentario secreto --> después.',
            '',
            '<script>window.__pwned = true</script>',
            '',
            '<img src="x" onerror="window.__pwned = true">',
            '',
            '<div class="html-crudo">bloque html</div>',
            '',
            '[enlace malo](javascript:window.__pwned=true)',
            '',
            '![imagen](https://example.org/x.png)',
          ].join('\n'),
        },
      ],
    }
    mockLegalApi({ catalog })
    renderApp('/privacidad')
    await privacyTitle()

    const main = screen.getByRole('main')
    expect(main.querySelector('script')).toBeNull()
    expect(main.querySelector('img')).toBeNull()
    expect(main.querySelector('.html-crudo')).toBeNull()
    expect(main.querySelector('[onerror]')).toBeNull()
    expect(main.querySelector('a[href^="javascript"]')).toBeNull()
    expect(main).not.toHaveTextContent('comentario secreto')
    expect(main).not.toHaveTextContent('<script>')
    expect(main).not.toHaveTextContent('bloque html')
    expect((window as unknown as { __pwned?: boolean }).__pwned).toBeUndefined()
    expect(screen.getByText(/Antes/)).toBeInTheDocument()
  })

  // 002-04
  it('002-04: has an index with one link per section; activating it moves focus to that heading', async () => {
    mockLegalApi()
    renderApp('/privacidad')
    await privacyTitle()

    await userEvent.click(screen.getByRole('button', { name: es.legal.toc }))
    const nav = screen.getByRole('navigation', { name: es.legal.toc })
    const links = within(nav).getAllByRole('link')
    expect(links.map((link) => link.textContent)).toEqual([
      '1. Quién es el responsable',
      '2. Qué datos tratamos',
      '3. Cookies',
    ])

    await userEvent.click(links[1] as HTMLElement)

    const heading = screen.getByRole('heading', { level: 2, name: '2. Qué datos tratamos' })
    expect(heading).toHaveAttribute('id', 'seccion-2')
    expect(heading).toHaveFocus()
    expect(heading.scrollIntoView).toHaveBeenCalled()
  })

  // 002-04
  it('002-04: lets the keyboard activate an index link with Enter', async () => {
    mockLegalApi()
    renderApp('/privacidad')
    await privacyTitle()

    await userEvent.click(screen.getByRole('button', { name: es.legal.toc }))
    const nav = screen.getByRole('navigation', { name: es.legal.toc })
    within(nav).getByRole('link', { name: '3. Cookies' }).focus()
    await userEvent.keyboard('{Enter}')

    expect(screen.getByRole('heading', { level: 2, name: '3. Cookies' })).toHaveFocus()
  })

  // 002-04
  it('002-04: folds the index behind a "Contenido" button that announces its state', async () => {
    mockLegalApi()
    renderApp('/privacidad')
    await privacyTitle()

    const toggle = screen.getByRole('button', { name: es.legal.toc })
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
    await userEvent.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')
    await userEvent.click(
      within(screen.getByRole('navigation', { name: es.legal.toc })).getByRole('link', {
        name: '3. Cookies',
      }),
    )
    expect(toggle).toHaveAttribute('aria-expanded', 'false')
  })

  // 002-04
  it('002-04: opens in the section named by #seccion-N once the text has loaded', async () => {
    mockLegalApi()
    renderApp('/privacidad#seccion-2')

    const heading = await screen.findByRole('heading', { level: 2, name: '2. Qué datos tratamos' })
    await waitFor(() => expect(heading).toHaveFocus())
    expect(heading.scrollIntoView).toHaveBeenCalled()
  })

  // 002-04
  it('002-04: stays at the top when #seccion-N does not exist', async () => {
    mockLegalApi()
    renderApp('/privacidad#seccion-99')
    await privacyTitle()

    expect(document.activeElement).toBe(document.body)
    expect(document.getElementById('seccion-99')).toBeNull()
  })

  // 002-04
  it('002-04: numbers only the sections with a heading, so "seccion-N" matches the numbered titles', async () => {
    mockLegalApi()
    renderApp('/privacidad')
    await privacyTitle()

    // The opening text (no heading) is not seccion-1: "1. Quién es el responsable" is.
    expect(screen.getByRole('heading', { name: '1. Quién es el responsable' })).toHaveAttribute(
      'id',
      'seccion-1',
    )
  })

  // 002-05
  it('002-05: shows the draft notice and the bracketed placeholders as they are', async () => {
    mockLegalApi()
    renderApp('/privacidad')
    await privacyTitle()

    expect(screen.getByText(es.legal.draftNotice)).toBeVisible()
    expect(screen.getByText(/\[correo de contacto\]/)).toBeInTheDocument()
    expect(screen.getByText(/\[fecha de publicación\]/)).toBeInTheDocument()
  })

  // 002-06
  it('002-06: shows a skeleton announced with aria-busy while loading, and the draft notice already', () => {
    mockLegalApi()
    renderApp('/privacidad')

    expect(
      screen.getByRole('heading', { level: 1, name: es.legal.fallbackTitles.privacy }),
    ).toBeVisible()
    expect(screen.getByText(es.legal.loading)).toHaveAttribute('role', 'status')
    expect(document.querySelector('[aria-busy="true"]')).not.toBeNull()
    expect(document.querySelectorAll('.skeleton').length).toBeGreaterThan(0)
    expect(screen.getByText(es.legal.draftNotice)).toBeVisible()
  })

  // 002-06
  it('002-06: shows an alert with "Reintentar" when the request fails, and retries without reloading', async () => {
    let status = 500
    const api = mockLegalApi({ catalogStatus: () => status })
    renderApp('/privacidad')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(es.legal.errorMessage)
    expect(document.querySelector('[aria-busy="true"]')).toBeNull()

    status = 200
    await userEvent.click(within(alert).getByRole('button', { name: es.common.retry }))

    await privacyTitle()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(api.calls().filter((call) => call === 'GET /api/v1/legal/current')).toHaveLength(2)
  })

  // 002-06
  it('002-06: treats a document missing from the answer as an error', async () => {
    const catalog = sampleCatalog()
    catalog.documents = catalog.documents.filter((document) => document.key !== 'privacy')
    mockLegalApi({ catalog })
    renderApp('/privacidad')

    expect(await screen.findByRole('alert')).toHaveTextContent(es.legal.errorMessage)
    expect(screen.getByRole('button', { name: es.common.retry })).toBeInTheDocument()
  })

  // 002-06
  it('002-06: treats a network failure as an error', async () => {
    mockLegalApi({ catalogStatus: 0 })
    renderApp('/terminos')
    expect(await screen.findByRole('alert')).toBeInTheDocument()
  })

  // 002-07
  it('002-07: makes a wide table reachable with the keyboard', async () => {
    mockLegalApi()
    renderApp('/privacidad')
    await privacyTitle()

    const scroller = screen.getByRole('group', { name: es.legal.tableLabel })
    expect(scroller).toHaveAttribute('tabindex', '0')
    expect(within(scroller).getByRole('table')).toBeInTheDocument()
  })

  it('sets the tab title from the route', async () => {
    mockLegalApi()
    renderApp('/privacidad')
    await privacyTitle()
    await waitFor(() =>
      expect(document.title).toBe(`${es.legal.fallbackTitles.privacy} · ${es.app.name}`),
    )
  })
})
