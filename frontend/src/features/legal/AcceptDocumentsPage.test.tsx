import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { es } from '../../i18n/es'
import { mockLegalApi, sampleCatalog } from '../../test/fakeLegal'
import { renderAppWithSession } from '../../test/renderApp'
import { acceptDocumentsPath, safeReturnTo } from './paths'

const heading = () => screen.findByRole('heading', { level: 1, name: es.acceptDocuments.title })
const checkbox = () => screen.getByRole('checkbox', { name: es.acceptDocuments.checkbox })
const submit = () => screen.getByRole('button', { name: es.acceptDocuments.submit })

let router: ReturnType<typeof renderAppWithSession>['router']
/** Renders the app with the real session provider, at `path`. */
function open(path: string) {
  const view = renderAppWithSession(path)
  router = view.router
  return view
}
/** Where the app is now: path and query. */
const where = () => router.state.location.pathname + router.state.location.search
const stillHere = () => expect(router.state.location.pathname).toBe('/aceptar-documentos')
/** The alert of the page itself (the header also has a hidden one for a failed sign-out). */
const logoutButton = () =>
  within(screen.getByRole('main')).getByRole('button', { name: es.acceptDocuments.logout })
const pageAlert = () => within(screen.getByRole('main')).findByRole('alert')

beforeEach(() => {
  router = undefined as never
})

describe('Page /aceptar-documentos (002-18, 002-19)', () => {
  // 002-18
  it('002-18: says the documents changed and shows the version, the date and the links to the texts', async () => {
    mockLegalApi({ pending: true })
    open('/aceptar-documentos')
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
    open('/aceptar-documentos')

    expect(await screen.findByText('Versión v1 · Fecha de publicación pendiente')).toBeVisible()
  })

  // 002-18 and T9
  it('002-18: starts with the box unticked and shows "Aceptar y continuar" and "Cerrar sesión"', async () => {
    mockLegalApi({ pending: true })
    open('/aceptar-documentos')
    await screen.findByRole('checkbox')

    expect(checkbox()).not.toBeChecked()
    expect(submit()).toBeEnabled()
    expect(logoutButton()).toBeEnabled()
  })

  // 002-18
  it('002-18: shows nothing and goes on to the page asked for when nothing is pending', async () => {
    mockLegalApi({ pending: false })
    open('/aceptar-documentos?returnTo=%2Foportunidades')

    await waitFor(() => expect(where()).toBe('/oportunidades'))
    expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })

  // 002-19
  it('002-19: without the box ticked it does not continue and explains why', async () => {
    const api = mockLegalApi({ pending: true })
    open('/aceptar-documentos')
    await screen.findByRole('checkbox')

    await userEvent.click(submit())

    expect(screen.getByRole('alert')).toHaveTextContent(es.acceptDocuments.mustAccept)
    expect(checkbox()).toHaveAttribute('aria-invalid', 'true')
    expect(checkbox()).toHaveFocus()
    expect(api.acceptBodies()).toEqual([])
    stillHere()
  })

  // 002-19
  it('002-19: sends the accepted version and goes back to the page asked for (returnTo)', async () => {
    const api = mockLegalApi({ pending: true })
    open('/aceptar-documentos?returnTo=%2Foportunidades%3Fq%3Djava')
    await screen.findByRole('checkbox')

    await userEvent.click(checkbox())
    await userEvent.click(submit())

    await waitFor(() => expect(where()).toBe('/oportunidades?q=java'))
    expect(api.acceptBodies()).toEqual([{ legalAccepted: true, legalVersion: 'v1' }])
  })

  // 002-19
  it('002-19: goes to the Home when there is no returnTo (005-05)', async () => {
    mockLegalApi({ pending: true })
    open('/aceptar-documentos')
    await screen.findByRole('checkbox')

    await userEvent.click(checkbox())
    await userEvent.click(submit())

    await waitFor(() => expect(where()).toBe('/'))
  })

  // 002-19
  it.each([
    'https://evil.example/robo',
    '//evil.example',
    '/\\evil.example',
    'javascript:alert(1)',
    'panel',
    '/aceptar-documentos',
    '/aceptar-documentos/',
    '/Aceptar-Documentos',
    '/%61ceptar-documentos',
    '/./aceptar-documentos',
    '/ingresar',
    '/\t/evil.example',
  ])('002-19: ignores an outside returnTo (%j) and goes to the Home', async (value) => {
    mockLegalApi({ pending: true })
    open(`/aceptar-documentos?returnTo=${encodeURIComponent(value)}`)
    await screen.findByRole('checkbox')

    await userEvent.click(checkbox())
    await userEvent.click(submit())

    await waitFor(() => expect(where()).toBe('/'))
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
    open('/aceptar-documentos')
    await screen.findByRole('checkbox')
    await userEvent.click(checkbox())

    await userEvent.click(submit())

    const busy = screen.getByRole('button', { name: es.acceptDocuments.saving })
    expect(busy).toBeDisabled()
    finish()
    await waitFor(() => expect(where()).not.toBe('/aceptar-documentos'))
  })

  // 001-03 / edge case: outdated version
  it('001-03: on 409 loads the current text again, unticks the box and asks again', async () => {
    let call = 0
    const api = mockLegalApi({
      pending: true,
      acceptStatus: 409,
      catalog: () => sampleCatalog({ version: ++call === 1 ? 'v1' : 'v2' }),
    })
    open('/aceptar-documentos')
    await screen.findByText(/Versión v1/)
    await userEvent.click(checkbox())

    await userEvent.click(submit())

    expect(await screen.findByText(/Versión v2/)).toBeVisible()
    expect(screen.getByRole('alert')).toHaveTextContent(es.acceptDocuments.outdated)
    expect(checkbox()).not.toBeChecked()
    expect(api.acceptBodies()).toEqual([{ legalAccepted: true, legalVersion: 'v1' }])
    stillHere()
  })

  // 002-19
  it('002-19: on 422 explains that the documents must be accepted', async () => {
    mockLegalApi({ pending: true, acceptStatus: 422 })
    open('/aceptar-documentos')
    await screen.findByRole('checkbox')
    await userEvent.click(checkbox())

    await userEvent.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent(es.acceptDocuments.mustAccept)
    stillHere()
  })

  it('on a server error says it could not save and lets the student try again', async () => {
    mockLegalApi({ pending: true, acceptStatus: 500 })
    open('/aceptar-documentos')
    await screen.findByRole('checkbox')
    await userEvent.click(checkbox())

    await userEvent.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent(es.acceptDocuments.failed)
    expect(submit()).toBeEnabled()
  })

  // 001-14: an expired session
  it('001-14: goes to the sign-in page, coming back here afterwards, when the session has expired', async () => {
    mockLegalApi({ pending: null })
    open('/aceptar-documentos?returnTo=%2Fpanel')

    await waitFor(() => expect(router.state.location.pathname).toBe('/ingresar'))
    // The panel is where one goes anyway: no parameter needed.
    expect(router.state.location.search).toBe('')
  })

  it('shows an error with "Reintentar" when the texts cannot be loaded', async () => {
    mockLegalApi({ pending: true, catalogStatus: 500 })
    open('/aceptar-documentos')

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(es.acceptDocuments.errorMessage)
    expect(within(alert).getByRole('button', { name: es.common.retry })).toBeInTheDocument()
  })

  // T8
  it('"Cerrar sesión" signs out and leaves to the sign-in page saying so', async () => {
    const api = mockLegalApi({ pending: true })
    open('/aceptar-documentos')
    await screen.findByRole('checkbox')

    await userEvent.click(logoutButton())

    await waitFor(() => expect(where()).toBe('/ingresar'))
    expect(api.calls()).toContain('POST /api/v1/auth/logout')
    // Same destination and message as the header and the panel (the shared sign-out).
    expect(await screen.findByText(es.identity.login.notices.signedOut)).toBeInTheDocument()
  })

  // M2: a stale CSRF token (403) is retried once with a new token
  it('on a 403 it asks for a new CSRF token and repeats the request once, then continues', async () => {
    const api = mockLegalApi({ pending: true, forbiddenFirst: 1 })
    open('/aceptar-documentos?returnTo=%2Foportunidades')
    await screen.findByRole('checkbox')
    await userEvent.click(checkbox())

    await userEvent.click(submit())

    await waitFor(() => expect(where()).toBe('/oportunidades'))
    expect(api.calls().filter((call) => call === 'POST /api/v1/legal/accept')).toHaveLength(2)
    const posts = api.calls().filter((call) => call.startsWith('POST') || call.endsWith('/csrf'))
    expect(posts.slice(-3)).toEqual([
      'POST /api/v1/legal/accept',
      'GET /api/v1/auth/csrf',
      'POST /api/v1/legal/accept',
    ])
  })

  it('on a second 403 it does not try again and shows the error', async () => {
    const api = mockLegalApi({ pending: true, forbiddenFirst: 5 })
    open('/aceptar-documentos')
    await screen.findByRole('checkbox')
    await userEvent.click(checkbox())

    await userEvent.click(submit())

    expect(await screen.findByRole('alert')).toHaveTextContent(es.acceptDocuments.failed)
    expect(api.calls().filter((call) => call === 'POST /api/v1/legal/accept')).toHaveLength(2)
    stillHere()
    expect(submit()).toBeEnabled()
  })

  // T8 with a stale CSRF token
  it('"Cerrar sesión" also retries once after a 403 and then leaves', async () => {
    const api = mockLegalApi({ pending: true, forbiddenFirst: 1 })
    open('/aceptar-documentos')
    await screen.findByRole('checkbox')

    await userEvent.click(logoutButton())

    await waitFor(() => expect(where()).toBe('/ingresar'))
    expect(api.calls().filter((call) => call === 'POST /api/v1/auth/logout')).toHaveLength(2)
  })

  it('"Cerrar sesión" stays available after two 403: it says so and can be tried again', async () => {
    const api = mockLegalApi({ pending: true, forbiddenFirst: 2 })
    open('/aceptar-documentos')
    await screen.findByRole('checkbox')

    await userEvent.click(logoutButton())

    expect(await pageAlert()).toHaveTextContent(es.acceptDocuments.logoutFailed)
    stillHere()
    const again = logoutButton()
    expect(again).toBeEnabled()
    await userEvent.click(again)
    await waitFor(() => expect(where()).toBe('/ingresar'))
    expect(api.calls().filter((call) => call === 'POST /api/v1/auth/logout')).toHaveLength(3)
  })

  // T8
  it('says so, and stays on the screen, when signing out fails', async () => {
    mockLegalApi({ pending: true, logoutStatus: 500 })
    open('/aceptar-documentos')
    await screen.findByRole('checkbox')

    await userEvent.click(logoutButton())

    expect(await pageAlert()).toHaveTextContent(es.acceptDocuments.logoutFailed)
    stillHere()
  })

  // 002-18: documents stay public
  it('002-18: does not block reading the documents', async () => {
    mockLegalApi({ pending: true })
    open('/terminos')
    await screen.findByRole('heading', { level: 1, name: 'Términos y Condiciones de Talenti' })
  })
})

describe('Session shared with the rest of the app (no page reload)', () => {
  it('after accepting, the session is read again and the private page opens without going back to this screen', async () => {
    const api = mockLegalApi({ pending: true })
    open('/panel')
    await heading() // /panel sent the student here: the acceptance is pending
    expect(where()).toBe('/aceptar-documentos?returnTo=%2Fpanel')
    const before = api.calls().filter((call) => call === 'GET /api/v1/auth/me').length

    await userEvent.click(await screen.findByRole('checkbox'))
    await userEvent.click(submit())

    // /panel is a student's old address: it leads to the Home (005-04).
    expect(await screen.findByRole('heading', { level: 1, name: es.home.title })).toBeVisible()
    expect(where()).toBe('/')
    const after = api.calls().filter((call) => call === 'GET /api/v1/auth/me').length
    expect(after).toBeGreaterThan(before)
  })

  it('after signing out the header shows the visitor links', async () => {
    mockLegalApi({ pending: true })
    open('/aceptar-documentos')
    await screen.findByRole('checkbox')
    expect(
      within(screen.getByRole('banner')).queryByRole('link', { name: es.nav.register }),
    ).not.toBeInTheDocument()

    await userEvent.click(logoutButton())

    await waitFor(() => expect(where()).toBe('/ingresar'))
    expect(
      await within(screen.getByRole('banner')).findByRole('link', { name: es.nav.login }),
    ).toBeVisible()
  })
})

describe('returnTo validation (001 edge case, 002-19)', () => {
  it.each([
    '/panel',
    '/oportunidades?q=java',
    '/oportunidades/abc#seccion',
    '/',
    '/aceptar-documentos-viejos',
  ])('accepts the internal route %s', (value) => {
    expect(safeReturnTo(value)).toBe(value)
  })

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
    '/aceptar-documentos/',
    '/aceptar-documentos//',
    '/ACEPTAR-DOCUMENTOS',
    '/Aceptar-Documentos?x=1#y',
    '/%61ceptar-documentos',
    '/%2561ceptar-documentos',
    '/./aceptar-documentos',
    '/panel/../aceptar-documentos',
    '/%2e%2e/aceptar-documentos',
    '/%2e/aceptar-documentos',
    '/ingresar',
    '/Ingresar/',
    '/registro',
    '/%',
  ])('rejects %j', (value) => {
    expect(safeReturnTo(value)).toBeNull()
  })

  it('builds the address of the screen with a safe returnTo only', () => {
    expect(acceptDocumentsPath()).toBe('/aceptar-documentos')
    expect(acceptDocumentsPath('/panel')).toBe('/aceptar-documentos?returnTo=%2Fpanel')
    expect(acceptDocumentsPath('//evil.example')).toBe('/aceptar-documentos')
  })
})
