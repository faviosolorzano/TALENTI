import { act, render, screen, waitFor } from '@testing-library/react'
import { useEffect } from 'react'
import { afterEach, describe, expect, it } from 'vitest'
import { clearCsrfCookie, holdNextRequest, mockIdentityBackend } from '../test/fakeIdentity'
import { SessionProvider } from './SessionProvider'
import { useSession, type SessionState } from './useSession'

const EMAIL = 'ana.prueba@example.test'
const ACCOUNTS = { [EMAIL]: { password: 'Clave12345', role: 'STUDENT' as const } }

afterEach(clearCsrfCookie)

/** The session as the probe last saw it (set in an effect, never while rendering). */
const seen: { current: SessionState | null } = { current: null }
const current = (): SessionState => seen.current as SessionState

function Probe() {
  const session = useSession()
  useEffect(() => {
    seen.current = session
  })
  return <p data-testid="status">{session.status}</p>
}

const status = () => screen.getByTestId('status').textContent

function renderProvider() {
  return render(
    <SessionProvider>
      <Probe />
    </SessionProvider>,
  )
}

const json = (code: string, httpStatus: number) =>
  new Response(JSON.stringify({ code }), {
    status: httpStatus,
    headers: { 'Content-Type': 'application/json' },
  })

describe('Session provider: the last request wins and a failed check is honest', () => {
  it('a slow answer of /auth/me that arrives AFTER signing out does not bring the person back in', async () => {
    mockIdentityBackend({ signedInAs: EMAIL, accounts: ACCOUNTS })
    renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))

    // A new check starts and its answer is held back...
    const slow = holdNextRequest('/auth/me')
    let slowRefresh: Promise<void> = Promise.resolve()
    act(() => {
      slowRefresh = current().refresh()
    })
    // ...while the person signs out and the server confirms it.
    await act(async () => {
      expect(await current().signOut()).toBe(true)
    })
    expect(status()).toBe('visitor')

    // The old answer ("still signed in") finally arrives: it must be ignored.
    slow.release(
      new Response(
        JSON.stringify({
          id: '1',
          email: EMAIL,
          role: 'STUDENT',
          legalAcceptancePending: false,
        }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    await act(async () => {
      await slowRefresh
    })
    expect(status()).toBe('visitor')
    expect(current().user).toBeNull()
  })

  it('a slower, OLDER check never replaces the answer of a newer one', async () => {
    mockIdentityBackend({ signedInAs: EMAIL, accounts: ACCOUNTS })
    renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))

    const old = holdNextRequest('/auth/me')
    let oldRefresh: Promise<void> = Promise.resolve()
    act(() => {
      oldRefresh = current().refresh()
    })
    // A newer check answers first: the server says there is no session.
    const newer = holdNextRequest('/auth/me')
    let newerRefresh: Promise<void> = Promise.resolve()
    act(() => {
      newerRefresh = current().refresh()
    })
    newer.release(json('unauthenticated', 401))
    await act(async () => {
      await newerRefresh
    })
    expect(status()).toBe('visitor')

    old.release(
      new Response(
        JSON.stringify({ id: '1', email: EMAIL, role: 'STUDENT', legalAcceptancePending: false }),
        { status: 200, headers: { 'Content-Type': 'application/json' } },
      ),
    )
    await act(async () => {
      await oldRefresh
    })
    expect(status()).toBe('visitor')
  })

  it('a visitor whose check fails (server error) is "unavailable", not a signed-in person', async () => {
    const backend = mockIdentityBackend({})
    renderProvider()
    await waitFor(() => expect(status()).toBe('visitor'))

    backend.setOptions({ meFails: true })
    await act(async () => {
      await current().refresh()
    })

    expect(status()).toBe('unavailable')
    expect(current().user).toBeNull()
    expect(current().isStudent).toBe(false)
  })

  it('a signed-in person whose later check fails (network) stays signed in: it does not pretend to know', async () => {
    const backend = mockIdentityBackend({ signedInAs: EMAIL, accounts: ACCOUNTS })
    renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))

    backend.setOptions({ offline: true })
    await act(async () => {
      await current().refresh()
    })

    expect(status()).toBe('authenticated')
    expect(current().user?.email).toBe(EMAIL)
  })

  it('"unavailable" recovers to "visitor" or "authenticated" when the next check answers', async () => {
    const backend = mockIdentityBackend({ meFails: true })
    renderProvider()
    await waitFor(() => expect(status()).toBe('unavailable'))

    backend.setOptions({ meFails: false })
    await act(async () => {
      await current().refresh()
    })
    expect(status()).toBe('visitor')
  })

  it('signing out when the check is unavailable still works', async () => {
    mockIdentityBackend({ signedInAs: EMAIL, accounts: ACCOUNTS, meFails: true })
    renderProvider()
    await waitFor(() => expect(status()).toBe('unavailable'))

    await act(async () => {
      expect(await current().signOut()).toBe(true)
    })

    expect(status()).toBe('visitor')
  })

  it('a failed sign-out (network) leaves the person signed in and answers false', async () => {
    const backend = mockIdentityBackend({ signedInAs: EMAIL, accounts: ACCOUNTS })
    renderProvider()
    await waitFor(() => expect(status()).toBe('authenticated'))

    backend.setOptions({ offline: true })
    await act(async () => {
      expect(await current().signOut()).toBe(false)
    })

    expect(status()).toBe('authenticated')
  })
})
