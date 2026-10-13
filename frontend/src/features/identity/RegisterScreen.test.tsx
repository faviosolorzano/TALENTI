import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { es } from '../../i18n/es'
import { clearCsrfCookie, mockIdentityBackend, SAMPLE_LEGAL } from '../../test/fakeIdentity'
import { renderAppWithSession } from '../../test/renderApp'

const EMAIL = 'ana.prueba@example.test'
const PASSWORD = 'Clave12345'

afterEach(clearCsrfCookie)

async function openRegister(options: Parameters<typeof mockIdentityBackend>[0] = {}) {
  const backend = mockIdentityBackend(options)
  const view = renderAppWithSession('/registro')
  // The form is there from the start; wait until the consent text has loaded.
  await screen.findByText(es.identity.consent.summary)
  return { backend, ...view, user: userEvent.setup() }
}

const emailField = () => screen.getByLabelText(es.identity.form.emailLabel)
const passwordField = () => screen.getByLabelText(es.identity.form.passwordLabel)
const consentBox = () => screen.getByRole('checkbox', { name: /Leí y acepto/ })
const submitButton = () => screen.getByRole('button', { name: es.identity.register.submit })

async function fillAndSend(user: ReturnType<typeof userEvent.setup>, accept = true) {
  await user.type(emailField(), EMAIL)
  await user.type(passwordField(), PASSWORD)
  if (accept) await user.click(consentBox())
  await user.click(submitButton())
}

describe('Sign-up form (001-22, 001-26)', () => {
  it('has a label on every field, the consent box unchecked and the right autocomplete', async () => {
    await openRegister()

    expect(emailField()).toHaveAttribute('autocomplete', 'email')
    expect(emailField()).toHaveAttribute('type', 'email')
    expect(passwordField()).toHaveAttribute('autocomplete', 'new-password')
    expect(passwordField()).toHaveAttribute('type', 'password')
    expect(consentBox()).not.toBeChecked()
    expect(
      screen.getByRole('heading', { level: 1, name: es.identity.register.title }),
    ).toBeVisible()
  })

  it('links to the full Terms and Privacy Policy in ANOTHER tab and says so', async () => {
    await openRegister()

    for (const [name, href] of [
      [es.identity.consent.terms, '/terminos'],
      [es.identity.consent.privacy, '/privacidad'],
    ] as const) {
      const link = screen.getByRole('link', { name: new RegExp(name) })
      expect(link).toHaveAttribute('href', href)
      expect(link).toHaveAttribute('target', '_blank')
      expect(link.getAttribute('rel')).toContain('noopener')
      expect(link).toHaveAccessibleName(`${name} ${es.identity.consent.opensInNewTab}`)
    }
    expect(screen.getByText(es.identity.form.newTabHint)).toBeVisible()
  })

  it('shows the consent text from the backend with its version and date, closed until opened', async () => {
    const { user } = await openRegister()

    const details = screen.getByText(es.identity.consent.summary).closest('details')
    expect(details).not.toHaveAttribute('open')
    await user.click(screen.getByText(es.identity.consent.summary))
    expect(details).toHaveAttribute('open')
    expect(screen.getByText('Versión v1, publicada el 11 de octubre de 2026')).toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 3, name: 'Qué datos recogemos' })).toBeVisible()
    expect(
      screen.getByRole('heading', { level: 3, name: /proveedores externos de IA y voz/ }),
    ).toBeVisible()
    expect(screen.getByText('Tu correo y tu contraseña.')).toBeInTheDocument()
  })

  it('shows a skeleton while the consent text loads and keeps the button off', async () => {
    mockIdentityBackend()
    renderAppWithSession('/registro')

    const busy = document.querySelector('.consent[aria-busy="true"]')
    expect(busy).not.toBeNull()
    expect(busy?.querySelector('.skeleton')).not.toBeNull()
    expect(screen.getByText(es.identity.consent.loading)).toBeInTheDocument()
    expect(submitButton()).toBeDisabled()
    await screen.findByText(es.identity.consent.summary)
    expect(submitButton()).toBeEnabled()
  })

  it('without the consent text there is a "Reintentar" button and no sign-up; it works again after retrying', async () => {
    const backend = mockIdentityBackend({ legalFails: true })
    renderAppWithSession('/registro')
    const user = userEvent.setup()

    expect(await screen.findByText(es.identity.consent.loadError)).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent(es.identity.consent.loadError)
    expect(submitButton()).toBeDisabled()
    expect(consentBox()).toBeDisabled()

    backend.setOptions({ legalFails: false })
    await user.click(screen.getByRole('button', { name: es.common.retry }))

    await screen.findByText(es.identity.consent.summary)
    expect(submitButton()).toBeEnabled()
    expect(consentBox()).toBeEnabled()
  })

  it('shows and hides the password with a button that says what it does', async () => {
    const { user } = await openRegister()

    await user.type(passwordField(), PASSWORD)
    await user.click(screen.getByRole('button', { name: es.identity.form.showLabel }))
    expect(passwordField()).toHaveAttribute('type', 'text')
    await user.click(screen.getByRole('button', { name: es.identity.form.hideLabel }))
    expect(passwordField()).toHaveAttribute('type', 'password')
    expect(passwordField()).toHaveValue(PASSWORD)
  })
})

describe('Sign-up result (001-01, 001-02, 001-03, 001-04, 001-05)', () => {
  it('001-01: sends the data and the current legal version, then opens the panel with "Tu cuenta está lista"', async () => {
    const { backend, user } = await openRegister()

    await fillAndSend(user)

    expect(
      await screen.findByRole('heading', { level: 1, name: es.pages.dashboard.title }),
    ).toBeVisible()
    expect(screen.getByText(es.identity.dashboard.welcomeTitle)).toBeInTheDocument()
    expect(screen.getByText(es.identity.dashboard.greeting(EMAIL))).toBeInTheDocument()
    const [call] = backend.calls('POST', '/auth/register')
    expect(call?.body).toEqual({
      email: EMAIL,
      password: PASSWORD,
      legalAccepted: true,
      legalVersion: 'v1',
    })
  })

  it('announces the progress and turns the button off while the account is created', async () => {
    const { user } = await openRegister()
    let release: (response: Response) => void = () => undefined
    const real = globalThis.fetch
    vi.stubGlobal(
      'fetch',
      vi.fn((input: RequestInfo | URL, init?: RequestInit) =>
        String(input).endsWith('/auth/register')
          ? new Promise<Response>((resolve) => (release = resolve))
          : real(input, init),
      ),
    )

    await user.type(emailField(), EMAIL)
    await user.type(passwordField(), PASSWORD)
    await user.click(consentBox())
    await user.click(submitButton())

    const busy = await screen.findByRole('button', { name: es.identity.register.submitting })
    expect(busy).toBeDisabled()
    expect(
      screen
        .getAllByRole('status')
        .some((node) => node.textContent === es.identity.register.submitting),
    ).toBe(true)

    release(
      new Response(JSON.stringify({ code: 'too-many-requests' }), {
        status: 429,
        headers: { 'Content-Type': 'application/json' },
      }),
    )
    await screen.findByRole('alert')
    expect(submitButton()).toBeEnabled()
  })

  it('001-02: without the box ticked the backend says so under the box and focus goes there', async () => {
    const { backend, user } = await openRegister()

    await fillAndSend(user, false)

    const box = await screen.findByRole('checkbox', { name: /Leí y acepto/ })
    await waitFor(() => expect(box).toHaveFocus())
    expect(box).toHaveAttribute('aria-invalid', 'true')
    const message =
      'Debes aceptar los Términos y Condiciones y la Política de Privacidad para continuar.'
    expect(box).toHaveAccessibleDescription(expect.stringContaining(message))
    expect(backend.sessionEmail()).toBeNull()
    expect(
      screen.getByRole('heading', { level: 1, name: es.identity.register.title }),
    ).toBeVisible()
  })

  it('001-05: a weak password shows the rule written by the backend, linked to the field', async () => {
    const { user } = await openRegister()

    await user.type(emailField(), EMAIL)
    await user.type(passwordField(), 'corta')
    await user.click(consentBox())
    await user.click(submitButton())

    await waitFor(() => expect(passwordField()).toHaveFocus())
    expect(passwordField()).toHaveAttribute('aria-invalid', 'true')
    expect(passwordField()).toHaveAccessibleDescription(
      expect.stringContaining('La contraseña debe tener al menos 8 caracteres.'),
    )
    // The hint stays: it is read together with the error.
    expect(passwordField()).toHaveAccessibleDescription(
      expect.stringContaining(es.identity.register.passwordHint),
    )
    expect(emailField()).not.toHaveAttribute('aria-invalid')
  })

  it('001-05: a badly written e-mail shows the rule under the e-mail field and focus goes there', async () => {
    const { user } = await openRegister()

    await user.type(emailField(), 'sin-arroba')
    await user.type(passwordField(), PASSWORD)
    await user.click(consentBox())
    await user.click(submitButton())

    await waitFor(() => expect(emailField()).toHaveFocus())
    expect(emailField()).toHaveAccessibleDescription(
      expect.stringContaining('Escribe un correo válido'),
    )
  })

  it('001-04: a repeated e-mail says so and offers to sign in, without revealing anything else', async () => {
    const { user } = await openRegister({
      accounts: { [EMAIL]: { password: 'OtraClave99', role: 'STUDENT' } },
    })

    await fillAndSend(user)

    await waitFor(() => expect(emailField()).toHaveFocus())
    expect(emailField()).toHaveAccessibleDescription(
      expect.stringContaining(es.identity.register.emailTaken),
    )
    const alert = emailField().closest('.field')
    expect(
      within(alert as HTMLElement).getByRole('link', {
        name: es.identity.register.emailTakenAction,
      }),
    ).toHaveAttribute('href', '/ingresar')
    // What was typed stays.
    expect(emailField()).toHaveValue(EMAIL)
  })

  it('001-03: an outdated legal version asks to read and accept again, and loads the text again', async () => {
    const backend = mockIdentityBackend()
    renderAppWithSession('/registro')
    const user = userEvent.setup()
    await screen.findByText(es.identity.consent.summary)
    const first = backend.calls('GET', '/legal/current').length

    await user.type(emailField(), EMAIL)
    await user.type(passwordField(), PASSWORD)
    await user.click(consentBox())
    backend.setOptions({ legal: { ...SAMPLE_LEGAL, version: 'v2' } })
    // The page still holds "v1": the backend now only accepts "v2".
    await user.click(submitButton())

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(es.identity.register.versionOutdated)
    await waitFor(() => expect(alert).toHaveFocus())
    await screen.findByText(es.identity.consent.summary)
    expect(backend.calls('GET', '/legal/current').length).toBeGreaterThan(first)
    expect(consentBox()).not.toBeChecked()
    expect(backend.sessionEmail()).toBeNull()
  })

  it('shows "Demasiados intentos, prueba más tarde." when the rate limit answers', async () => {
    const { user } = await openRegister({ rateLimited: true })

    await fillAndSend(user)

    expect(await screen.findByRole('alert')).toHaveTextContent(es.identity.register.tooManyRequests)
  })

  it('tells about a network failure with the generic connection text', async () => {
    const { backend, user } = await openRegister()
    await user.type(emailField(), EMAIL)
    await user.type(passwordField(), PASSWORD)
    await user.click(consentBox())

    backend.setOptions({ offline: true })
    await user.click(submitButton())

    expect(await screen.findByRole('alert')).toHaveTextContent(es.errors.network)
  })

  it('a person who already has a session is taken to the panel', async () => {
    mockIdentityBackend({
      signedInAs: EMAIL,
      accounts: { [EMAIL]: { password: PASSWORD, role: 'STUDENT' } },
    })
    renderAppWithSession('/registro')

    expect(
      await screen.findByRole('heading', { level: 1, name: es.pages.dashboard.title }),
    ).toBeVisible()
  })
})

describe('Sign-up and the CSRF token (001-15)', () => {
  beforeEach(clearCsrfCookie)

  it('asks for the token when there is no cookie and sends the one in the cookie', async () => {
    const { backend, user } = await openRegister()
    expect(backend.calls('GET', '/auth/csrf')).toHaveLength(0)

    await fillAndSend(user)
    await screen.findByText(es.identity.dashboard.welcomeTitle)

    expect(backend.calls('GET', '/auth/csrf')).toHaveLength(1)
    const [call] = backend.calls('POST', '/auth/register')
    expect(call?.csrfHeader).not.toBeNull()
    expect(call?.csrfHeader).toBe(call?.csrfCookie)
  })
})
