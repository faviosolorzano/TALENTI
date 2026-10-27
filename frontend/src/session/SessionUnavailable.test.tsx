import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { es } from '../i18n/es'
import { clearCsrfCookie, holdNextRequest, mockIdentityBackend } from '../test/fakeIdentity'
import { renderAppWithSession } from '../test/renderApp'

const EMAIL = 'ana.prueba@example.test'
// A company account: its page is still /panel (001-21), where these header tests look for "Mi panel".
const COMPANY_ACCOUNT = { [EMAIL]: { password: 'Clave12345', role: 'COMPANY' as const } }

afterEach(clearCsrfCookie)

const loginTitle = () => screen.findByRole('heading', { level: 1, name: es.identity.login.title })
const headerLogout = async () => {
  const banner = within(screen.getByRole('banner'))
  const account = banner.queryByRole('button', { name: es.nav.account })
  if (account?.getAttribute('aria-expanded') === 'false') await userEvent.click(account)
  return banner.getAllByRole('button', { name: es.identity.nav.logout })
}

describe('"Cerrar sesión" when the session cannot be checked (M1)', () => {
  it('unavailable: the header says so, offers "Reintentar" and "Cerrar sesión", and does NOT offer "Ingresar" or "Crear cuenta"', async () => {
    mockIdentityBackend({ signedInAs: EMAIL, accounts: COMPANY_ACCOUNT, meFails: true })
    renderAppWithSession('/')
    const banner = screen.getByRole('banner')

    expect(await within(banner).findByText(es.identity.guard.unavailable)).toBeVisible()
    expect(within(banner).getByRole('button', { name: es.common.retry })).toBeVisible()
    expect((await headerLogout()).length).toBeGreaterThan(0)
    expect(within(banner).queryByRole('link', { name: es.nav.register })).not.toBeInTheDocument()
    expect(within(banner).queryByRole('link', { name: es.nav.login })).not.toBeInTheDocument()
  })

  it('unavailable: "Cerrar sesión" works (POST /auth/logout does not depend on /me) and leads to /ingresar', async () => {
    const backend = mockIdentityBackend({
      signedInAs: EMAIL,
      accounts: COMPANY_ACCOUNT,
      meFails: true,
    })
    const { router } = renderAppWithSession('/')
    const user = userEvent.setup()
    await within(screen.getByRole('banner')).findByText(es.identity.guard.unavailable)

    await user.click((await headerLogout())[0]!)

    await loginTitle()
    expect(backend.calls('POST', '/auth/logout')).toHaveLength(1)
    expect(backend.sessionEmail()).toBeNull()
    expect(router.state.location.pathname).toBe('/ingresar')
    expect(screen.getByText(es.identity.login.notices.signedOut)).toBeInTheDocument()
  })

  it('unavailable: "Reintentar" in the header recovers the session when the server answers again', async () => {
    const backend = mockIdentityBackend({
      signedInAs: EMAIL,
      accounts: COMPANY_ACCOUNT,
      meFails: true,
    })
    renderAppWithSession('/')
    const user = userEvent.setup()
    const banner = screen.getByRole('banner')
    await within(banner).findByText(es.identity.guard.unavailable)

    backend.setOptions({ meFails: false })
    await user.click(within(banner).getByRole('button', { name: es.common.retry }))

    await waitFor(() =>
      expect(
        within(banner).getAllByRole('link', { name: es.nav.dashboard }).length,
      ).toBeGreaterThan(0),
    )
    expect(within(banner).queryByText(es.identity.guard.unavailable)).not.toBeInTheDocument()
  })

  it('a private page with the session unavailable offers "Reintentar" AND "Cerrar sesión"', async () => {
    const backend = mockIdentityBackend({
      signedInAs: EMAIL,
      accounts: COMPANY_ACCOUNT,
      meFails: true,
    })
    renderAppWithSession('/panel')
    const user = userEvent.setup()

    const main = screen.getByRole('main')
    expect(await within(main).findByText(es.identity.guard.unavailable)).toBeVisible()
    expect(within(main).getByRole('button', { name: es.common.retry })).toBeVisible()
    await user.click(within(main).getByRole('button', { name: es.identity.dashboard.logout }))

    await loginTitle()
    expect(backend.sessionEmail()).toBeNull()
  })

  it('while the first answer is slow, the private page already offers "Cerrar sesión"', async () => {
    mockIdentityBackend({ signedInAs: EMAIL, accounts: COMPANY_ACCOUNT })
    const held = holdNextRequest('/auth/me')
    renderAppWithSession('/panel')

    const main = screen.getByRole('main')
    expect(main.querySelector('[aria-busy="true"]')).not.toBeNull()
    expect(within(main).getByRole('button', { name: es.identity.dashboard.logout })).toBeVisible()
    held.release(
      new Response(JSON.stringify({ code: 'unauthenticated' }), {
        status: 401,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    await loginTitle()
  })

  it('while the first answer is slow, the header offers "Cerrar sesión" after a moment (not at once, so visitors see no flash)', async () => {
    mockIdentityBackend({ signedInAs: EMAIL, accounts: COMPANY_ACCOUNT })
    const held = holdNextRequest('/auth/me')
    renderAppWithSession('/')
    const banner = screen.getByRole('banner')

    expect(
      within(banner).queryByRole('button', { name: es.identity.nav.logout }),
    ).not.toBeInTheDocument()
    expect(
      await within(banner).findAllByRole('button', { name: es.identity.nav.logout }),
    ).not.toHaveLength(0)
    expect(within(banner).queryByRole('link', { name: es.nav.login })).not.toBeInTheDocument()
    held.release(new Response(null, { status: 500 }))
  })
})

describe('A failed sign-out from the header is visible (M2)', () => {
  async function signedInAt(path: string) {
    const backend = mockIdentityBackend({ signedInAs: EMAIL, accounts: COMPANY_ACCOUNT })
    const view = renderAppWithSession(path)
    const banner = screen.getByRole('banner')
    await waitFor(() =>
      expect(
        within(banner).getAllByRole('link', { name: es.nav.dashboard }).length,
      ).toBeGreaterThan(0),
    )
    return { backend, banner, ...view, user: userEvent.setup() }
  }

  it('shows a visible note (also an alert) and clears it on the next attempt', async () => {
    const { backend, banner, router, user } = await signedInAt('/oportunidades')

    backend.setOptions({ offline: true })
    await user.click((await headerLogout())[0]!)

    const note = await within(banner).findByRole('alert')
    expect(note).toHaveTextContent(es.identity.dashboard.logoutFailed)
    expect(note).toBeVisible()
    expect(note).not.toHaveClass('visually-hidden')

    backend.setOptions({ offline: false })
    await user.click((await headerLogout())[0]!)
    await loginTitle()
    expect(within(banner).queryByRole('alert')).not.toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/ingresar')
  })

  it('the note disappears when the person goes to another page', async () => {
    const { backend, banner, router, user } = await signedInAt('/oportunidades')
    backend.setOptions({ offline: true })
    await user.click((await headerLogout())[0]!)
    await within(banner).findByRole('alert')

    await router.navigate('/registro')

    await waitFor(() => expect(within(banner).queryByRole('alert')).not.toBeInTheDocument())
  })
})
