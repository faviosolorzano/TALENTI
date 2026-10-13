import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { es } from '../../i18n/es'
import { mockLegalApi, sampleCatalog } from '../../test/fakeLegal'
import { renderApp } from '../../test/renderApp'
import { leaveTo } from './navigation'
import { acceptDocumentsPath, safeReturnTo } from './paths'

vi.mock('./navigation', () => ({ leaveTo: vi.fn() }))

const heading = () => screen.findByRole('heading', { level: 1, name: es.acceptDocuments.title })
const checkbox = () => screen.getByRole('checkbox', { name: es.acceptDocuments.checkbox })
const submit = () => screen.getByRole('button', { name: es.acceptDocuments.submit })

beforeEach(() => {
  vi.mocked(leaveTo).mockClear()
})

describe('Page /aceptar-documentos (002-18, 002-19)', () => {
  // 002-18
  it('002-18: says the documents changed and shows the version, the date and the links to the texts', async () => {
    mockLegalApi({ pending: true })
    renderApp('/aceptar-documentos')
    await heading()
    await screen.findByText('Versión v1 · Publicada el 11 de octubre de 2026')

    expect(screen.getByText(es.acceptDocuments.lead)).toBeInTheDocument()
    const links = within(screen.getByRole('main')).getAllByRole('link')
    expect(links.map((link) => link.getAttribute('href'))).toEqual(['/terminos', '/privacidad'])
    for (const link of links) {
      expect(link).toHaveAttribute('target', '_blank')
      expect(link).toHaveAccessibleName(expect.stringContaining('(se abre en otra pestaña)'))
    }
    expect(screen.getByText(es.legal.draftNotice)).toBeVisible()
  })

  // 002-02 (same rule on this screen)
  it('002-02: says the date is pending when the backend has none, and invents none', async () => {
    mockLegalApi({ pending: true, catalog: sampleCatalog({ publishedAt: null }) })
    renderApp('/aceptar-documentos')

    expect(await screen.findByText('Versión v1 · Fecha de publicación pendiente')).toBeVisible()
  })

  // 002-18 and T9
  it('002-18: starts with the box unticked and shows "Aceptar y continuar" and "Cerrar sesión"', async () => {
    mockLegalApi({ pending: true })
    renderApp('/aceptar-documentos')
    await screen.findByRole('checkbox')

    expect(checkbox()).not.toBeChecked()
    expect(submit()).toBeEnabled()
    expect(screen.getByRole('button', { name: es.acceptDocuments.logout })).toBeEnabled()
  })

  // 002-18
  it('002-18: shows nothing and goes on to the page asked for when nothing is pending', async () => {
    mockLegalApi({ pending: false })
    renderApp('/aceptar-documentos?returnTo=%2Foportunidades')

    await waitFor(() => expect(leaveTo).toHaveBeenCalledWith('/oportunidades'))
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  // 002-19
  it('002-19: without the box ticked it does not continue and explains why', async () => {
    const api = mockLegalApi({ pending: true })
    renderApp('/aceptar-documentos')
    await screen.findByRole('checkbox')

    await userEvent.click(submit())

    expect(screen.getByRole('alert')).toHaveTextContent(es.acceptDocuments.mustAccept)
    expect(checkbox()).toHaveAttribute('aria-invalid', 'true')
    expect(checkbox()).toHaveFocus()
    expect(api.acceptBodies()).toEqual([])
    expect(leaveTo).not.toHaveBeenCalled()
  })

  // 002-19
  it('002-19: sends the accepted version and goes back to the page asked for (returnTo)', async () => {
    const api = mockLegalApi({ pending: true })
    renderApp('/aceptar-documentos?returnTo=%2Foportunidades%3Fq%3Djava')
    await screen.findByRole('checkbox')

    await userEvent.click(checkbox())
    await userEvent.click(submit())

    await waitFor(() => expect(leaveTo).toHaveBeenCalledWith('/oportunidades?q=java'))
    expect(api.acceptBodies()).toEqual([{ legalAccepted: true, legalVersion: 'v1' }])
  })

  // 002-19
  it('002-19: goes to the panel when there is no returnTo', async () => {
    mockLegalApi({ pending: true })
    renderApp('/aceptar-documentos')
    await screen.findByRole('checkbox')

    await userEvent.click(checkbox())
    await userEvent.click(submit())

    await waitFor(() => expect(leaveTo).toHaveBeenCalledWith('/panel'))
  })

  // 002-19
  it.each([
    'https://evil.example/robo',
    '//evil.example',
    '/\\evil.example',
    'javascript:alert(1)',
    'panel',
    '/aceptar-documentos',
    '/\t/evil.example',
  ])('002-19: ignores an outside returnTo (%j) and goes to the panel', async (value) => {
    mockLegalApi({ pending: true })
    renderApp(`/aceptar-documentos?returnTo=${encodeURIComponent(value)}`)
    await screen.findByRole('checkbox')

    await userEvent.click(checkbox())
    await userEvent.click(submit())

    await waitFor(() => expect(leaveTo).toHaveBeenCalledWith('/panel'))
  })

  it('shows "Guardando…" and disables the button while the request is in flight', async () => {
    let finish: () => void = () => {}
    const gate = new Promise<void>((resolve) => (finish = resolve))
    const api = mockLegalApi({ pending: true })
    const original = api.fetch.getMockImplementation() as (
      input: RequestInfo | URL,
      init?: RequestInit,
    ) => Promise<Response>
    api.fetch.mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      if (String(input).endsWith('/legal/accept')) await gate
      return original(input, init)
    })
    renderApp('/aceptar-documentos')
    await screen.findByRole('checkbox')
    await userEvent.click(checkbox())

    await userEvent.click(submit())

    const busy = screen.getByRole('button', { name: es.acceptDocuments.saving })
    expect(busy).toBeDisabled()
    finish()
    await waitFor(() => expect(leaveTo).toHaveBeenCalled())
  })

  // 001-03 / edge case: outdated version
  it('001-03: on 409 loads the current text again, unticks the box and asks again', async () => {
    let call = 0
    const api = mockLegalApi({
      pending: true,
      acceptStatus: 409,
      catalog: () => sampleCatalog({ version: ++call === 1 ? 'v1' : 'v2' }),
    })
    renderApp('/aceptar-documentos')
    await screen.findByText(/Versión v1/)
    await userEvent.click(checkbox())

    await userEvent.click(submit())

    expect(await screen.findByText(/Versión v2/)).toBeVisible()
    expect(screen.getByRole('alert')).toHaveTextContent(es.acceptDocuments.outdated)
    expect(checkbox()).not.toBeChecked()
    expect(api.acceptBodies()).toEqual([{ legalAccepted: true, legalVersion: 'v1' }])
    expect(leaveTo).not.toHaveBeenCalled()
  })

  // 002-19
  it('002-19: on 422 explains that the documents must be accepted', async () => {
    mockLegalApi({ pending: true, acceptStatus: 422 })
    renderApp('/aceptar-documentos')
    await screen.findByRole('checkbox')
    await userEvent.click(checkbox())

    await userEvent.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent(es.acceptDocuments.mustAccept)
    expect(leaveTo).not.toHaveBeenCalled()
  })

  it('on a server error says it could not save and lets the student try again', async () => {
    mockLegalApi({ pending: true, acceptStatus: 500 })
    renderApp('/aceptar-documentos')
    await screen.findByRole('checkbox')
    await userEvent.click(checkbox())

    await userEvent.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent(es.acceptDocuments.failed)
    expect(submit()).toBeEnabled()
  })

  // 001-14: an expired session
  it('001-14: goes to the sign-in page, coming back here afterwards, when the session has expired', async () => {
    mockLegalApi({ pending: null })
    const { router } = renderApp('/aceptar-documentos?returnTo=%2Fpanel')

    await waitFor(() => expect(router.state.location.pathname).toBe('/ingresar'))
    expect(router.state.location.search).toBe('?returnTo=%2Fpanel')
  })

  it('shows an error with "Reintentar" when the texts cannot be loaded', async () => {
    mockLegalApi({ pending: true, catalogStatus: 500 })
    renderApp('/aceptar-documentos')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(es.acceptDocuments.errorMessage)
    expect(within(alert).getByRole('button', { name: es.common.retry })).toBeInTheDocument()
  })

  // T8
  it('"Cerrar sesión" signs out and leaves to the home page', async () => {
    const api = mockLegalApi({ pending: true })
    renderApp('/aceptar-documentos')
    await screen.findByRole('checkbox')

    await userEvent.click(screen.getByRole('button', { name: es.acceptDocuments.logout }))

    await waitFor(() => expect(leaveTo).toHaveBeenCalledWith('/'))
    expect(api.calls()).toContain('POST /api/v1/auth/logout')
  })

  // T8
  it('says so, and stays on the screen, when signing out fails', async () => {
    mockLegalApi({ pending: true, logoutStatus: 500 })
    renderApp('/aceptar-documentos')
    await screen.findByRole('checkbox')

    await userEvent.click(screen.getByRole('button', { name: es.acceptDocuments.logout }))

    expect(await screen.findByRole('alert')).toHaveTextContent(es.acceptDocuments.logoutFailed)
    expect(leaveTo).not.toHaveBeenCalled()
  })

  // 002-18: documents stay public
  it('002-18: does not block reading the documents', async () => {
    mockLegalApi({ pending: true })
    renderApp('/terminos')
    await screen.findByRole('heading', { level: 1, name: 'Términos y Condiciones de Talenti' })
  })
})

describe('returnTo validation (001 edge case, 002-19)', () => {
  it.each(['/panel', '/oportunidades?q=java', '/oportunidades/abc#seccion', '/'])(
    'accepts the internal route %s',
    (value) => {
      expect(safeReturnTo(value)).toBe(value)
    },
  )

  it.each([
    null,
    undefined,
    '',
    'panel',
    '//evil.example',
    '///evil.example',
    '/\\evil.example',
    'https://evil.example',
    'javascript:alert(1)',
    '/panel\n/x',
    '/aceptar-documentos',
    '/aceptar-documentos?returnTo=%2Fpanel',
  ])('rejects %j', (value) => {
    expect(safeReturnTo(value)).toBeNull()
  })

  it('builds the address of the screen with a safe returnTo only', () => {
    expect(acceptDocumentsPath()).toBe('/aceptar-documentos')
    expect(acceptDocumentsPath('/panel')).toBe('/aceptar-documentos?returnTo=%2Fpanel')
    expect(acceptDocumentsPath('//evil.example')).toBe('/aceptar-documentos')
  })
})
