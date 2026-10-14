import userEvent from '@testing-library/user-event'
import { mockCvBackend, type FakeCvOptions } from './fakeCv'
import { mockBackend, type FakeBackendOptions } from './fakeBackend'
import { mockCompatibilityBackend, type FakeCompatibilityOptions } from './fakeCompatibility'
import { mockIdentityBackend, type FakeIdentityOptions } from './fakeIdentity'
import { mockProfileBackend, type FakeProfileOptions } from './fakeProfile'
import { renderAppWithSession } from './renderApp'

export const STUDENT_EMAIL = 'ana.prueba@example.test'
export const STUDENT_PASSWORD = 'Clave12345'

interface StudentAppOptions {
  profile?: FakeProfileOptions
  vacancies?: FakeBackendOptions
  compatibility?: FakeCompatibilityOptions
  cv?: FakeCvOptions
  identity?: FakeIdentityOptions
  /** Role of the account that is signed in (default: a student). */
  role?: 'STUDENT' | 'COMPANY' | 'ADMIN'
  /** Open the app without a session. */
  signedOut?: boolean
}

/**
 * The whole app in memory with a signed-in student: the vacancies fake, the profile fake and
 * the identity fake (installed last: each one hands the paths it does not know to the one
 * installed before). Returns the handles of the fakes, the router and a `user`.
 */
export function openStudentApp(path: string, options: StudentAppOptions = {}) {
  const vacancies = mockBackend(options.vacancies)
  const profile = mockProfileBackend(options.profile)
  const compatibility = mockCompatibilityBackend(options.compatibility)
  const cv = mockCvBackend(options.cv)
  const identity = mockIdentityBackend({
    signedInAs: options.signedOut ? undefined : STUDENT_EMAIL,
    accounts: {
      [STUDENT_EMAIL]: { password: STUDENT_PASSWORD, role: options.role ?? 'STUDENT' },
    },
    ...options.identity,
  })
  const view = renderAppWithSession(path)
  return { vacancies, profile, compatibility, cv, identity, ...view, user: userEvent.setup() }
}
