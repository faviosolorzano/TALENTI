/// <reference types="node" />
import { readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { ApiError } from '../../api/client'
import { es } from '../../i18n/es'
import { loginErrors, registerErrors } from './errors'

const problem = (status: number, body: Record<string, unknown>) =>
  new ApiError(status, { status, ...body })

describe('Messages for a failed sign-up (001-02 to 001-05)', () => {
  it('maps the 422 fields of the backend to the fields of the screen, joining several rules', () => {
    const failure = registerErrors(
      problem(422, {
        code: 'validation-failed',
        errors: [
          { field: 'password', rule: 'needs-letter', message: 'Debe incluir una letra.' },
          { field: 'password', rule: 'needs-digit', message: 'Debe incluir un número.' },
          { field: 'legalAccepted', rule: 'must-be-accepted', message: 'Debes aceptar.' },
          { field: 'legalVersion', rule: 'required', message: 'Falta la versión.' },
          { field: 'role', rule: 'invalid', message: 'No cuenta.' },
        ],
      }),
    )

    expect(failure.fields.password).toBe('Debe incluir una letra. Debe incluir un número.')
    expect(failure.fields.consent).toBe('Debes aceptar. Falta la versión.')
    expect(failure.fields.email).toBeUndefined()
    expect(failure.general).toBeNull()
  })

  it('a 422 without usable fields shows the general "revisa los campos" text', () => {
    const failure = registerErrors(problem(422, { code: 'validation-failed' }))
    expect(failure.general).toBe(es.identity.form.reviewFields)
  })

  it('distinguishes the two 409 answers by their stable code', () => {
    expect(registerErrors(problem(409, { code: 'email-already-registered' }))).toMatchObject({
      emailTaken: true,
      fields: { email: es.identity.register.emailTaken },
    })
    expect(registerErrors(problem(409, { code: 'legal-version-outdated' }))).toMatchObject({
      legalOutdated: true,
      general: es.identity.register.versionOutdated,
    })
  })

  it('handles 429, network failures and anything else without exposing backend texts', () => {
    expect(registerErrors(problem(429, { code: 'too-many-requests' })).general).toBe(
      es.identity.register.tooManyRequests,
    )
    expect(registerErrors(new ApiError(0, null, es.errors.network)).general).toBe(es.errors.network)
    expect(registerErrors(problem(500, { detail: 'java.lang.NullPointerException' })).general).toBe(
      es.errors.generic,
    )
    expect(registerErrors(new Error('boom')).general).toBe(es.errors.generic)
  })
})

describe('Messages for a failed sign-in (001-09, 001-10, 001-11)', () => {
  it('every 401 is the same generic text, whatever the backend says', () => {
    const a = loginErrors(problem(401, { code: 'invalid-credentials', detail: 'Cuenta bloqueada' }))
    const b = loginErrors(problem(401, { code: 'unauthenticated' }))
    expect(a.general).toBe(es.identity.login.invalidCredentials)
    expect(b.general).toBe(a.general)
    expect(a.emailTaken).toBeUndefined()
  })

  it('429 is "Demasiados intentos, prueba más tarde."', () => {
    expect(loginErrors(problem(429, { code: 'too-many-requests' })).general).toBe(
      'Demasiados intentos, prueba más tarde.',
    )
  })
})

function withoutComments(file: string): string {
  return readFileSync(file, 'utf-8')
    .split('\n')
    .filter((line) => {
      const text = line.trim()
      return !text.startsWith('*') && !text.startsWith('//') && !text.startsWith('/*')
    })
    .join('\n')
}

describe('Texts and storage in the identity screens', () => {
  const dir = import.meta.dirname
  const shared = ['TextField', 'PasswordField', 'CheckboxField', 'Notice', 'Header'].map((name) =>
    join(dir, '..', '..', 'components', `${name}.tsx`),
  )
  const components = [...readdirSync(dir).map((file) => join(dir, file)), ...shared].filter(
    (file) => file.endsWith('.tsx') && !/\.test\./.test(file),
  )

  it('finds the components to check', () => {
    expect(components.length).toBeGreaterThan(8)
  })

  it('no component writes Spanish text by hand: it all comes from src/i18n/es.ts', () => {
    const literal = /<[^>]*>\s*[A-ZÁÉÍÓÚÑa-záéíóúñ¿¡][^<>{}]*[a-záéíóúñ][^<>{}]*\s*<\//
    const offenders = components.filter((file) => {
      const source = readFileSync(file, 'utf-8')
        .split('\n')
        .filter((line) => !line.trim().startsWith('*') && !line.trim().startsWith('//'))
        .join('\n')
      return literal.test(source)
    })
    expect(offenders).toEqual([])
  })

  it('nothing stores data in the browser or writes to the console', () => {
    const files = [
      ...components,
      join(dir, '..', '..', 'session', 'SessionProvider.tsx'),
      join(dir, '..', '..', 'session', 'useSignOut.ts'),
      join(dir, '..', '..', 'api', 'identity.ts'),
    ]
    const offenders = files.filter((file) =>
      /localStorage|sessionStorage|indexedDB|console\./.test(withoutComments(file)),
    )
    expect(offenders).toEqual([])
  })
})
