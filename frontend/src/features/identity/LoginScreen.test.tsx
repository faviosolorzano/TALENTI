import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, describe, expect, it } from 'vitest'
import { es } from '../../i18n/es'
import { mockBackend } from '../../test/fakeBackend'
import { clearCsrfCookie, holdNextRequest, mockIdentityBackend } from '../../test/fakeIdentity'
import { renderAppWithSession } from '../../test/renderApp'

const EMAIL = 'ana.prueba@example.test'
const PASSWORD = 'Clave12345'
const ACCOUNTS = { [EMAIL]: { password: PASSWORD, role: 'STUDENT' as const } }

afterEach(clearCsrfCookie)

const emailField = () => screen.getByLabelText(es.identity.form.emailLabel)
const passwordField = () => screen.getByLabelText(es.identity.form.passwordLabel)
const submit = () => screen.getByRole('button', { name: es.identity.login.submit })

async function openLogin(
  path = '/ingresar',
  options: Parameters<typeof mockIdentityBackend>[0] = { accounts: ACCOUNTS },
) {
  const backend = mockIdentityBackend(options)
  const view = renderAppWithSession(path)
  await screen.findByRole('heading', { level: 1, name: es.identity.login.title })
  return { backend, ...view, user: userEvent.setup() }
}

async function signIn(
  user: ReturnType<typeof userEvent.setup>,
  password = PASSWORD,
  email = EMAIL,
) {
  await user.type(emailField(), email)
  await user.type(passwordField(), password)
  await user.click(submit())
}

describe('Sign-in form (001-22, 001-23)', () => {
  it('has labelled fields with the right autocomplete and a show/hide button', async () => {
    const { user } = await openLogin()

    expect(emailField()).toHaveAttribute('autocomplete', 'username')
    expect(passwordField()).toHaveAttribute('autocomplete', 'current-password')
    expect(passwordField()).toHaveAttribute('type', 'password')
    await user.click(screen.getByRole('button', { name: es.identity.form.showLabel }))
    expect(passwordField()).toHaveAttribute('type', 'text')
  })

  it('001-23: "¿Olvidaste tu contraseña?" leads to an information page that recovers nothing', async () => {
    const { backend, user } = await openLogin()

    await user.click(screen.getByRole('link', { name: es.identity.login.forgot }))

    expect(
      await screen.findByRole('heading', { level: 1, name: es.identity.recover.title }),
    ).toBeVisible()
    expect(screen.getByText(es.identity.recover.body)).toBeVisible()
    expect(screen.queryByLabelText(es.identity.form.emailLabel)).not.toBeInTheDocument()
    expect(backend.requests.filter((request) => request.method === 'POST')).toHaveLength(0)
    expect(screen.getByRole('link', { name: es.identity.recover.back })).toHaveAttribute(
      'href',
      '/ingresar',
    )
  })

  it('offers to create an account', async () => {
    await openLogin()
    const main = screen.getByRole('main')
    expect(
      within(main).getByRole('link', { name: es.identity.login.goToRegister }),
    ).toHaveAttribute('href', '/registro')
  })
})

describe('Sign-in result (001-08, 001-09, 001-10, 001-11)', () => {
  it('001-08: with the right data it opens the panel', async () => {
    const { backend, user } = await openLogin()

    await signIn(user)

    expect(
      await screen.findByRole('heading', { level: 1, name: es.pages.dashboard.title }),
    ).toBeVisible()
    expect(screen.getByText(es.identity.dashboard.greeting(EMAIL))).toBeInTheDocument()
    expect(backend.calls('POST', '/auth/login')[0]?.body).toEqual({
      email: EMAIL,
      password: PASSWORD,
    })
  })

  it('turns the button off and announces the progress while signing in', async () => {
    const { user } = await openLogin()
    const held = holdNextRequest('/auth/login')
    await user.type(emailField(), EMAIL)
    await user.type(passwordField(), PASSWORD)
    await user.click(submit())

    const busy = await screen.findByRole('button', { name: es.identity.login.submitting })
    expect(busy).toBeDisabled()
    expect(
      screen
        .getAllByRole('status')
        .some((node) => node.textContent === es.identity.login.submitting),
    ).toBe(true)

    held.release(
      new Response(JSON.stringify({ code: 'too-many-requests' }), {
        status: 429,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    expect(await screen.findByRole('alert')).toBeInTheDocument()
    expect(submit()).toBeEnabled()
  })

  it('001-09: a wrong password and an unknown e-mail show the SAME generic message', async () => {
    const { user } = await openLogin()

    await signIn(user, 'ClaveEquivocada1')
    const wrongPassword = (await screen.findByRole('alert')).textContent
    await user.clear(emailField())
    await user.type(emailField(), 'nadie@example.test')
    await user.type(passwordField(), 'ClaveEquivocada1')
    await user.click(submit())
    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent(es.identity.login.invalidCredentials),
    )
    const unknownEmail = screen.getByRole('alert').textContent

    expect(wrongPassword).toBe(es.identity.login.invalidCredentials)
    expect(unknownEmail).toBe(wrongPassword)
    expect(wrongPassword).not.toMatch(/no existe|no está registrado|bloquead/i)
    expect(screen.getByRole('heading', { level: 1, name: es.identity.login.title })).toBeVisible()
  })

  it('moves focus to the message and empties only the password after a failure', async () => {
    const { user } = await openLogin()

    await signIn(user, 'ClaveEquivocada1')

    const alert = await screen.findByRole('alert')
    await waitFor(() => expect(alert).toHaveFocus())
    expect(emailField()).toHaveValue(EMAIL)
    expect(passwordField()).toHaveValue('')
  })

  it('001-10: after 5 failures even the right password shows the same generic message', async () => {
    const { backend, user } = await openLogin('/ingresar', {
      accounts: ACCOUNTS,
      lockAfterFailures: 5,
    })

    const send = async (password: string) => {
      fireEvent.change(emailField(), { target: { value: EMAIL } })
      fireEvent.change(passwordField(), { target: { value: password } })
      await user.click(submit())
    }
    for (let attempt = 1; attempt <= 5; attempt += 1) {
      await send('ClaveEquivocada1')
      await waitFor(() => expect(backend.calls('POST', '/auth/login')).toHaveLength(attempt))
      await screen.findByRole('alert')
    }
    await send(PASSWORD) // the right one

    await waitFor(() => expect(backend.calls('POST', '/auth/login')).toHaveLength(6))
    expect(await screen.findByRole('alert')).toHaveTextContent(es.identity.login.invalidCredentials)
    expect(backend.sessionEmail()).toBeNull()
  })

  it('001-11: when the rate limit answers it says "Demasiados intentos, prueba más tarde."', async () => {
    const { user } = await openLogin('/ingresar', { accounts: ACCOUNTS, rateLimited: true })

    await signIn(user)

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Demasiados intentos, prueba más tarde.')
    expect(alert.textContent).not.toMatch(/correo|cuenta existe/i)
  })

  it('empty fields show the backend rule under each field', async () => {
    const { user } = await openLogin()

    await user.click(submit())

    await waitFor(() => expect(emailField()).toHaveFocus())
    expect(emailField()).toHaveAttribute('aria-invalid', 'true')
    expect(passwordField()).toHaveAccessibleDescription('Este campo es obligatorio.')
  })

  it('a network failure shows the connection text', async () => {
    const { backend, user } = await openLogin()
    backend.setOptions({ offline: true })

    await signIn(user)

    expect(await screen.findByRole('alert')).toHaveTextContent(es.errors.network)
  })
})

describe('returnTo (001, edge cases)', () => {
  it('goes back to the page asked for after signing in', async () => {
    mockBackend()
    const { user } = await openLogin('/ingresar?returnTo=%2Foportunidades')

    expect(screen.getByText(es.identity.login.continueHint)).toBeInTheDocument()
    await signIn(user)

    expect(
      await screen.findByRole('heading', { level: 1, name: es.opportunities.title }),
    ).toBeVisible()
  })

  it.each([
    ['an external address', 'https%3A%2F%2Fmalo.example%2Frobo'],
    ['a protocol-relative address', '%2F%2Fmalo.example'],
    ['a backslash trick', '%2F%5Cmalo.example'],
    ['the sign-in page itself', '%2Fingresar'],
    ['the sign-up page', '%2Fregistro'],
  ])('ignores %s and goes to the panel', async (_name, encoded) => {
    const { router, user } = await openLogin(`/ingresar?returnTo=${encoded}`)

    await signIn(user)

    await screen.findByRole('heading', { level: 1, name: es.pages.dashboard.title })
    expect(router.state.location.pathname).toBe('/panel')
  })

  it('says why one is here: session expired, or signed out', async () => {
    mockIdentityBackend({ accounts: ACCOUNTS })
    const { router } = renderAppWithSession('/ingresar')
    await screen.findByRole('heading', { level: 1, name: es.identity.login.title })
    expect(screen.queryByText(es.identity.login.notices.expired)).not.toBeInTheDocument()

    await router.navigate('/ingresar', { state: { notice: 'expired' } })
    expect(await screen.findByText(es.identity.login.notices.expired)).toBeInTheDocument()
  })

  it('a person who already has a session is taken to returnTo', async () => {
    mockBackend()
    mockIdentityBackend({ accounts: ACCOUNTS, signedInAs: EMAIL })
    const { router } = renderAppWithSession('/ingresar?returnTo=%2Foportunidades')

    await screen.findByRole('heading', { level: 1, name: es.opportunities.title })
    expect(router.state.location.pathname).toBe('/oportunidades')
  })
})
