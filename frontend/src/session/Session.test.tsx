import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { es } from '../i18n/es'
import { mockBackend, VACANCY_IDS } from '../test/fakeBackend'
import { mockProfileBackend } from '../test/fakeProfile'
import { clearCsrfCookie, holdNextRequest, mockIdentityBackend } from '../test/fakeIdentity'
import { renderAppWithSession } from '../test/renderApp'

const EMAIL = 'ana.prueba@example.test'
const PASSWORD = 'Clave12345'
const STUDENT = { [EMAIL]: { password: PASSWORD, role: 'STUDENT' as const } }
// A company has no screens yet: `/panel` is still its page (001-21), so the guard tests use it.
const COMPANY = { [EMAIL]: { password: PASSWORD, role: 'COMPANY' as const } }

afterEach(clearCsrfCookie)

const panelTitle = () => screen.findByRole('heading', { level: 1, name: es.pages.dashboard.title })
const loginTitle = () => screen.findByRole('heading', { level: 1, name: es.identity.login.title })
const homeTitle = () => screen.findByRole('heading', { level: 1, name: es.home.title })
/** The student Home has no "Cerrar sesión" in the page: it is in the header (005-07). */
const headerLogout = () =>
  within(screen.getByRole('banner')).getAllByRole('button', { name: es.identity.nav.logout })[0]!
/** The fakes the student Home needs: vacancies and profile (the identity fake goes after them). */
function mockStudentApis() {
  mockBackend()
  mockProfileBackend()
}
const dashboardLogout = () =>
  within(screen.getByRole('main')).getByRole('button', { name: es.identity.dashboard.logout })

describe('Private routes (001-13, 001-14)', () => {
  it('001-13: a visitor who opens /panel is taken to /ingresar', async () => {
    mockIdentityBackend()
    const { router } = renderAppWithSession('/panel')

    await loginTitle()
    expect(router.state.location.pathname).toBe('/ingresar')
    // The panel is the default destination: no parameter needed.
    expect(router.state.location.search).toBe('')
  })

  it('shows a skeleton and announces it while the session is being checked', async () => {
    mockIdentityBackend({ signedInAs: EMAIL, accounts: COMPANY })
    renderAppWithSession('/panel')

    const busy = document.querySelector('.guard[aria-busy="true"]')
    expect(busy).not.toBeNull()
    expect(busy?.querySelector('.skeleton')).not.toBeNull()
    expect(screen.getByText(es.identity.guard.loading)).toBeInTheDocument()
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    await panelTitle()
  })

  it('005-04: a student who opens /panel is taken to the Home, without the e-mail on screen', async () => {
    mockStudentApis()
    mockIdentityBackend({ signedInAs: EMAIL, accounts: STUDENT })
    const { router } = renderAppWithSession('/panel')

    await homeTitle()
    expect(router.state.location.pathname).toBe('/')
    expect(screen.queryByText(EMAIL, { exact: false })).not.toBeInTheDocument()
    // It is not a fresh sign-up: no "Tu cuenta está lista".
    expect(screen.queryByText(es.home.welcomeTitle)).not.toBeInTheDocument()
  })
  it('001-25 and 005-07: with a session the navigation bar is there and the session does not end', async () => {
    mockStudentApis()
    mockIdentityBackend({ signedInAs: EMAIL, accounts: STUDENT })
    const { router } = renderAppWithSession('/')
    await homeTitle()
    const user = userEvent.setup()

    const nav = screen.getByRole('navigation', { name: es.app.mainNavLabel })
    await user.click(within(nav).getByRole('link', { name: es.nav.opportunities }))

    expect(
      await screen.findByRole('heading', { level: 1, name: es.opportunities.title }),
    ).toBeVisible()
    expect(router.state.location.pathname).toBe('/oportunidades')
    // And the session did not end: the student's menu is still there.
    expect(within(nav).getByRole('link', { name: es.nav.myProfile })).toBeInTheDocument()
    expect(headerLogout()).toBeInTheDocument()
  })
  it('001-14: a session that expired while the tab was idle takes to /ingresar with "Tu sesión expiró"', async () => {
    mockBackend()
    const backend = mockIdentityBackend({ signedInAs: EMAIL, accounts: COMPANY })
    const { router } = renderAppWithSession('/panel')
    await panelTitle()
    const user = userEvent.setup()
    // 30 minutes pass on the server and the person opens the panel again.
    backend.expireSession()
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(Date.now() + 60_000)
    try {
      await router.navigate('/oportunidades')
      await screen.findByRole('heading', { level: 1, name: es.opportunities.title })
      await user.click(
        within(screen.getByRole('banner')).getAllByRole('link', { name: es.nav.dashboard })[0]!,
      )
      await loginTitle()
    } finally {
      vi.useRealTimers()
    }

    expect(router.state.location.pathname).toBe('/ingresar')
    expect(screen.getByText(es.identity.login.notices.expired)).toBeInTheDocument()
  })

  it('a stale cookie when the app opens also ends in the expired notice', async () => {
    mockIdentityBackend({ expiredSession: true })
    renderAppWithSession('/panel')

    await loginTitle()
    expect(screen.getByText(es.identity.login.notices.expired)).toBeInTheDocument()
  })

  it('001-30: with a legal acceptance pending, private routes lead to the acceptance screen', async () => {
    mockIdentityBackend({
      signedInAs: EMAIL,
      accounts: { [EMAIL]: { password: PASSWORD, role: 'STUDENT', legalAcceptancePending: true } },
    })
    const { router } = renderAppWithSession('/panel')

    await screen.findByRole('heading', { level: 1, name: es.acceptDocuments.title })
    expect(router.state.location.pathname).toBe('/aceptar-documentos')
    expect(router.state.location.search).toBe('?returnTo=%2Fpanel')
  })

  it('if the session cannot be checked it says so and lets the person retry', async () => {
    const backend = mockIdentityBackend({ offline: true, signedInAs: EMAIL, accounts: COMPANY })
    renderAppWithSession('/panel')
    const user = userEvent.setup()

    const main = screen.getByRole('main')
    expect(await within(main).findByRole('alert')).toHaveTextContent(es.identity.guard.unavailable)
    backend.setOptions({ offline: false })
    await user.click(within(main).getByRole('button', { name: es.common.retry }))

    await panelTitle()
  })
})

describe('Roles without screens (001-21)', () => {
  it('a company account sees "Esta cuenta aún no tiene pantallas" and "Cerrar sesión"', async () => {
    const empresa = 'empresa.prueba@example.test'
    mockIdentityBackend({
      signedInAs: empresa,
      accounts: { [empresa]: { password: PASSWORD, role: 'COMPANY' } },
    })
    renderAppWithSession('/panel')

    expect(await screen.findByText(es.identity.dashboard.noScreensTitle)).toBeVisible()
    expect(dashboardLogout()).toBeVisible()
    expect(
      screen.queryByRole('link', { name: es.identity.dashboard.explore }),
    ).not.toBeInTheDocument()
  })
})

describe('Sign-out (001-12)', () => {
  it('signs out on the server, goes to /ingresar saying so and the panel asks to sign in again', async () => {
    const backend = mockIdentityBackend({ signedInAs: EMAIL, accounts: COMPANY })
    const { router } = renderAppWithSession('/panel')
    await panelTitle()
    const user = userEvent.setup()

    await user.click(dashboardLogout())

    await loginTitle()
    expect(screen.getByText(es.identity.login.notices.signedOut)).toBeInTheDocument()
    expect(backend.calls('POST', '/auth/logout')).toHaveLength(1)
    expect(backend.sessionEmail()).toBeNull()
    expect(router.state.location.pathname).toBe('/ingresar')

    await router.navigate('/panel')
    await waitFor(() => expect(router.state.location.pathname).toBe('/ingresar'))
    await loginTitle()
  })

  it('sends the CSRF token that is in the cookie NOW, also after it rotated on sign-in and sign-out', async () => {
    const backend = mockIdentityBackend({ accounts: COMPANY })
    renderAppWithSession('/ingresar')
    const user = userEvent.setup()
    await loginTitle()

    await user.type(screen.getByLabelText(es.identity.form.emailLabel), EMAIL)
    await user.type(screen.getByLabelText(es.identity.form.passwordLabel), PASSWORD)
    await user.click(screen.getByRole('button', { name: es.identity.login.submit }))
    await panelTitle()
    await user.click(dashboardLogout())
    await loginTitle()
    await user.type(screen.getByLabelText(es.identity.form.emailLabel), EMAIL)
    await user.type(screen.getByLabelText(es.identity.form.passwordLabel), PASSWORD)
    await user.click(screen.getByRole('button', { name: es.identity.login.submit }))
    await panelTitle()

    const posts = backend.requests.filter((request) => request.method === 'POST')
    expect(posts.map((request) => request.path)).toEqual([
      '/auth/login',
      '/auth/logout',
      '/auth/login',
    ])
    for (const post of posts) {
      expect(post.csrfHeader).not.toBeNull()
      expect(post.csrfHeader).toBe(post.csrfCookie)
    }
    // Three different tokens: one per request, because it rotates.
    expect(new Set(posts.map((post) => post.csrfHeader)).size).toBe(3)
  })

  it('turns the button off while signing out', async () => {
    mockIdentityBackend({ signedInAs: EMAIL, accounts: COMPANY })
    renderAppWithSession('/panel')
    await panelTitle()
    const user = userEvent.setup()
    // The token cookie must exist for the held request to be the logout itself.
    const held = holdNextRequest('/auth/logout')

    await user.click(dashboardLogout())

    expect(
      await screen.findByRole('button', { name: es.identity.dashboard.loggingOut }),
    ).toBeDisabled()
    held.release(new Response(null, { status: 204 }))
    await loginTitle()
  })

  it('if the server cannot be reached the person stays in and is told', async () => {
    const backend = mockIdentityBackend({ signedInAs: EMAIL, accounts: COMPANY })
    renderAppWithSession('/panel')
    await panelTitle()
    const user = userEvent.setup()
    backend.setOptions({ offline: true })

    await user.click(dashboardLogout())

    expect(await screen.findAllByRole('alert')).not.toHaveLength(0)
    expect(screen.getAllByText(es.identity.dashboard.logoutFailed).length).toBeGreaterThan(0)
    expect(screen.getByRole('heading', { level: 1, name: es.pages.dashboard.title })).toBeVisible()
  })
})

describe('Header with and without a session (003-09)', () => {
  it('a visitor sees "Ingresar" and "Crear cuenta"', async () => {
    mockIdentityBackend()
    renderAppWithSession('/')
    const banner = screen.getByRole('banner')

    await waitFor(() =>
      expect(within(banner).getByRole('link', { name: es.nav.register })).toBeInTheDocument(),
    )
    expect(within(banner).getAllByRole('link', { name: es.nav.login }).length).toBeGreaterThan(0)
    expect(within(banner).queryByRole('link', { name: es.nav.dashboard })).not.toBeInTheDocument()
    expect(
      within(banner).queryByRole('button', { name: es.identity.nav.logout }),
    ).not.toBeInTheDocument()
  })

  it('with a session it shows "Mi panel" and "Cerrar sesión", also inside the folded menu', async () => {
    mockIdentityBackend({ signedInAs: EMAIL, accounts: COMPANY })
    renderAppWithSession('/')
    const banner = screen.getByRole('banner')

    await waitFor(() =>
      expect(
        within(banner).getAllByRole('link', { name: es.nav.dashboard }).length,
      ).toBeGreaterThan(0),
    )
    expect(within(banner).queryByRole('link', { name: es.nav.register })).not.toBeInTheDocument()
    expect(within(banner).queryByRole('link', { name: es.nav.login })).not.toBeInTheDocument()
    const menu = within(banner).getByRole('navigation', { name: es.app.mainNavLabel })
    expect(within(menu).getByRole('link', { name: es.nav.dashboard })).toHaveAttribute(
      'href',
      '/panel',
    )
    expect(within(menu).getByRole('button', { name: es.identity.nav.logout })).toBeInTheDocument()
  })

  it('shows neither group while the session is being checked (no flash of "Ingresar")', async () => {
    mockIdentityBackend({ signedInAs: EMAIL, accounts: COMPANY })
    renderAppWithSession('/')
    const banner = screen.getByRole('banner')

    expect(within(banner).queryByRole('link', { name: es.nav.login })).not.toBeInTheDocument()
    expect(within(banner).queryByRole('link', { name: es.nav.register })).not.toBeInTheDocument()
    await waitFor(() =>
      expect(
        within(banner).getAllByRole('link', { name: es.nav.dashboard }).length,
      ).toBeGreaterThan(0),
    )
  })

  it('"Cerrar sesión" from the folded menu signs out and leads to /ingresar', async () => {
    const backend = mockIdentityBackend({ signedInAs: EMAIL, accounts: COMPANY })
    const { router } = renderAppWithSession('/')
    const user = userEvent.setup()
    const banner = screen.getByRole('banner')
    await waitFor(() =>
      expect(
        within(banner).getAllByRole('link', { name: es.nav.dashboard }).length,
      ).toBeGreaterThan(0),
    )

    await user.click(within(banner).getByRole('button', { name: es.nav.menu }))
    const menu = within(banner).getByRole('navigation', { name: es.app.mainNavLabel })
    await user.click(within(menu).getByRole('button', { name: es.identity.nav.logout }))

    await loginTitle()
    expect(backend.sessionEmail()).toBeNull()
    expect(router.state.location.pathname).toBe('/ingresar')
    expect(within(banner).getByRole('link', { name: es.nav.register })).toBeInTheDocument()
  })
})

describe('"Postular" and the session (004-22)', () => {
  const FIRST = VACANCY_IDS[0] as string

  it('with a session the button is off, says "Próximamente" and the sign-in notice is gone', async () => {
    mockBackend()
    mockIdentityBackend({ signedInAs: EMAIL, accounts: STUDENT })
    renderAppWithSession(`/oportunidades/${FIRST}`)

    const button = await screen.findByRole('button', { name: es.vacancyDetail.apply })
    expect(button).toBeDisabled()
    const applyCard = screen.getByRole('region', { name: es.vacancyDetail.apply })
    await waitFor(() =>
      expect(within(applyCard).getByText(es.vacancyDetail.applySoon)).toBeInTheDocument(),
    )
    expect(button).toHaveAccessibleDescription(es.vacancyDetail.applySoon)
    expect(screen.queryByText(es.vacancyDetail.applyLoginNotice)).not.toBeInTheDocument()
    expect(screen.queryByRole('link', { name: es.vacancyDetail.apply })).not.toBeInTheDocument()
  })

  it('as a visitor it is still the link to /ingresar that comes back to the vacancy', async () => {
    mockBackend()
    mockIdentityBackend()
    renderAppWithSession(`/oportunidades/${FIRST}`)

    const link = await screen.findByRole('link', { name: es.vacancyDetail.apply })
    expect(link).toHaveAttribute('href', `/ingresar?returnTo=%2Foportunidades%2F${FIRST}`)
    expect(screen.getByText(es.vacancyDetail.applyLoginNotice)).toBeInTheDocument()
  })

  it('a visitor who signs in from a vacancy comes back to that vacancy (returnTo)', async () => {
    mockBackend()
    mockIdentityBackend({ accounts: STUDENT })
    const { router } = renderAppWithSession(`/oportunidades/${FIRST}`)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('link', { name: es.vacancyDetail.apply }))
    await loginTitle()
    await user.type(screen.getByLabelText(es.identity.form.emailLabel), EMAIL)
    await user.type(screen.getByLabelText(es.identity.form.passwordLabel), PASSWORD)
    await user.click(screen.getByRole('button', { name: es.identity.login.submit }))

    const applyCard = await screen.findByRole('region', { name: es.vacancyDetail.apply })
    expect(await within(applyCard).findByText(es.vacancyDetail.applySoon)).toBeInTheDocument()
    expect(router.state.location.pathname).toBe(`/oportunidades/${FIRST}`)
  })
})

describe('Nothing secret is kept in the browser (RNF-01 to 05)', () => {
  it('a full sign-up, sign-out and sign-in writes nothing to localStorage or sessionStorage and nothing to the console', async () => {
    const consoleCalls: unknown[][] = []
    for (const method of ['log', 'info', 'warn', 'error', 'debug'] as const) {
      vi.spyOn(console, method).mockImplementation((...args: unknown[]) => {
        consoleCalls.push(args)
      })
    }
    const setItem = vi.spyOn(Storage.prototype, 'setItem')
    mockStudentApis()
    mockIdentityBackend()
    renderAppWithSession('/registro')
    const user = userEvent.setup()
    await screen.findByText(es.identity.consent.summary)

    await user.type(screen.getByLabelText(es.identity.form.emailLabel), EMAIL)
    await user.type(screen.getByLabelText(es.identity.form.passwordLabel), PASSWORD)
    await user.click(screen.getByRole('checkbox', { name: /Leí y acepto/ }))
    await user.click(screen.getByRole('button', { name: es.identity.register.submit }))
    await homeTitle()
    await user.click(headerLogout())
    await loginTitle()
    await user.type(screen.getByLabelText(es.identity.form.emailLabel), EMAIL)
    await user.type(screen.getByLabelText(es.identity.form.passwordLabel), PASSWORD)
    await user.click(screen.getByRole('button', { name: es.identity.login.submit }))
    await homeTitle()

    expect(setItem).not.toHaveBeenCalled()
    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    const logged = JSON.stringify(consoleCalls)
    expect(logged).not.toContain(EMAIL)
    expect(logged).not.toContain(PASSWORD)
    // The only cookie the page can read is the CSRF token: the session is HttpOnly and the
    // password never goes into a cookie.
    expect(document.cookie.split('; ').map((cookie) => cookie.split('=')[0])).toEqual([
      'XSRF-TOKEN',
    ])
    expect(document.cookie).not.toContain(PASSWORD)
    vi.restoreAllMocks()
  })
})
