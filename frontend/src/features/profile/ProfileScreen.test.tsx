import { screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { es } from '../../i18n/es'
import { clearCsrfCookie } from '../../test/fakeIdentity'
import type { FakeProfileOptions } from '../../test/fakeProfile'
import { openStudentApp } from '../../test/studentApp'

afterEach(clearCsrfCookie)

const t = es.profile

const pageTitle = () => screen.findByRole('heading', { level: 1 })
const card = (title: string) => screen.getByRole('region', { name: title })
const editButton = (title: string) =>
  within(card(title)).getByRole('button', { name: t.actions.editSection(title) })

/** Opens the page and waits until the profile has loaded. */
async function openProfile(
  data: NonNullable<FakeProfileOptions['profile']> = {},
  path = '/perfil',
  options: FakeProfileOptions = {},
) {
  const app = openStudentApp(path, { profile: { ...options, profile: data } })
  await pageTitle()
  await screen.findByRole('navigation', { name: es.profile.title })
  return app
}

const FULL_ACADEMIC = {
  academic: { career: 'Desarrollo de Software', institute: 'Instituto Andino', cycle: 5 },
}
const THREE_SKILLS = {
  skills: [
    { slug: 'java', name: 'Java', level: 2 as const, evidence: 'DECLARED' as const },
    { slug: 'sql', name: 'SQL', level: 1 as const, evidence: 'DECLARED' as const },
    { slug: 'git', name: 'Git', level: 1 as const, evidence: 'DECLARED' as const },
  ],
}

describe('Page /perfil: states and progress (006-14, 006-20)', () => {
  it('006-20: shows a skeleton announced as busy while the profile loads', async () => {
    openStudentApp('/perfil', { profile: { readDelayMs: 150 } })

    const title = await pageTitle()
    expect(title.closest('[aria-busy="true"]')).not.toBeNull()
    expect(document.querySelector('.skeleton')).not.toBeNull()
    expect(screen.getByText(t.loading)).toBeInTheDocument()
    await screen.findByRole('region', { name: t.about.title })
    expect(document.querySelector('[aria-busy="true"]')).toBeNull()
  })

  it('006-20: a failed load shows an alert with "Reintentar" that asks again', async () => {
    const { profile, user } = openStudentApp('/perfil', {
      profile: { failing: { '/profile': 500 } },
    })

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(t.loadError)
    profile.setFailing({})
    await user.click(within(alert).getByRole('button', { name: es.common.retry }))

    expect(await screen.findByRole('region', { name: t.about.title })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('shows five sections in top navigation and exactly one editor', async () => {
    const { user } = await openProfile()
    const nav = screen.getByRole('navigation', { name: es.profile.title })
    expect(within(nav).getAllByRole('button')).toHaveLength(5)
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument()
    expect(card(t.about.title)).toBeInTheDocument()
    await user.click(within(nav).getByRole('button', { name: t.skills.title }))
    expect(screen.queryByRole('region', { name: t.about.title })).not.toBeInTheDocument()
    expect(screen.getByRole('searchbox', { name: t.skills.pickerLabel })).toBeInTheDocument()
  })
  it('CV and GitHub lead to real screens without promising a PDF attachment', async () => {
    await openProfile()
    expect(screen.getByRole('link', { name: es.redesign.useCv })).toHaveAttribute(
      'href',
      '/perfil/cv',
    )
    expect(screen.getByRole('link', { name: es.redesign.github })).toHaveAttribute(
      'href',
      '/perfil/github',
    )
  })
  it('a complete profile still permits editing and has no completion gate', async () => {
    const { user } = await openProfile({ ...FULL_ACADEMIC, ...THREE_SKILLS })
    await user.click(screen.getByRole('button', { name: t.academic.title }))
    expect(screen.getByLabelText(t.academic.career)).toHaveValue('Desarrollo de Software')
    expect(screen.getByRole('button', { name: t.actions.save })).toBeEnabled()
  })
})

describe('?seccion= (006-20)', () => {
  it('opens that card in edit mode and gives it the focus', async () => {
    await openProfile({}, '/perfil?seccion=habilidades')

    const skills = card(t.skills.title)
    expect(within(skills).getByRole('button', { name: t.actions.save })).toBeInTheDocument()
    // The search is open (the list is empty) and has the cursor.
    await waitFor(() =>
      expect(within(skills).getByRole('searchbox', { name: t.skills.pickerLabel })).toHaveFocus(),
    )
    // The other cards stay in read mode.
    expect(screen.queryByRole('region', { name: t.academic.title })).not.toBeInTheDocument()
  })

  it.each([
    ['academico', t.academic.title, t.academic.career],
    ['disponibilidad', t.availability.title, t.availability.hours],
  ])('?seccion=%s opens "%s" with the cursor on its first field', async (value, title, label) => {
    await openProfile({}, `/perfil?seccion=${value}`)

    await waitFor(() => expect(within(card(title)).getByLabelText(label)).toHaveFocus())
  })

  it('an unknown value is ignored: every card is in read mode', async () => {
    await openProfile({}, '/perfil?seccion=otra-cosa')

    expect(screen.queryByRole('button', { name: t.actions.save })).not.toBeInTheDocument()
  })
})

describe('Private page (006-14)', () => {
  it('a visitor is taken to /ingresar and comes back to /perfil afterwards', async () => {
    const { router } = openStudentApp('/perfil', { signedOut: true })

    await screen.findByRole('heading', { level: 1, name: es.identity.login.title })
    expect(router.state.location.pathname).toBe('/ingresar')
    expect(router.state.location.search).toBe('?returnTo=%2Fperfil')
  })

  it('a student with a legal acceptance pending is taken to /aceptar-documentos', async () => {
    const { router } = openStudentApp('/perfil', {
      identity: {
        accounts: {
          'ana.prueba@example.test': {
            password: 'Clave12345',
            role: 'STUDENT',
            legalAcceptancePending: true,
          },
        },
      },
    })

    await screen.findByRole('heading', { level: 1, name: es.acceptDocuments.title })
    expect(router.state.location.pathname).toBe('/aceptar-documentos')
    expect(router.state.location.search).toBe('?returnTo=%2Fperfil')
  })

  it('a company account is told the page is for students and no profile is requested', async () => {
    const { profile } = openStudentApp('/perfil', { role: 'COMPANY' })

    expect(await screen.findByRole('heading', { name: t.studentsOnlyTitle })).toBeInTheDocument()
    expect(profile.requests).not.toContain('GET /profile')
  })

  it('a session that expires while saving leads to /ingresar with "Tu sesión expiró" (006-19)', async () => {
    const { identity, profile, router, user } = await openProfile({}, '/perfil?seccion=academico')
    await user.type(screen.getByLabelText(t.academic.career), 'Desarrollo')
    profile.setFailing({ '/profile/academic': 401 })
    identity.expireSession()

    await user.click(screen.getByRole('button', { name: t.actions.save }))

    await screen.findByRole('heading', { level: 1, name: es.identity.login.title })
    expect(router.state.location.pathname).toBe('/ingresar')
    expect(screen.getByText(es.identity.login.notices.expired)).toBeInTheDocument()
  })

  it('nothing of the profile is kept in storage or in the address', async () => {
    const { router, user } = await openProfile({ ...FULL_ACADEMIC }, '/perfil?seccion=academico')
    await user.clear(screen.getByLabelText(t.academic.career))
    await user.type(screen.getByLabelText(t.academic.career), 'Mecatrónica')
    await user.click(screen.getByRole('button', { name: t.actions.save }))
    await screen.findByText('Mecatrónica')

    expect(localStorage.length).toBe(0)
    expect(sessionStorage.length).toBe(0)
    expect(router.state.location.search).not.toContain('Mecatr')
  })
})

describe('Datos académicos (006-05, 006-15, 006-19)', () => {
  it('saves the section, shows "Guardado", goes back to read mode and updates the percentage without reloading', async () => {
    const { profile, user } = await openProfile({}, '/perfil?seccion=academico')

    await user.type(screen.getByLabelText(t.academic.career), 'Desarrollo de Software')
    await user.type(screen.getByLabelText(t.academic.institute), 'Instituto Andino')
    await user.type(screen.getByLabelText(t.academic.cycle), '5')
    await user.click(screen.getByRole('button', { name: t.actions.save }))

    expect(await within(card(t.academic.title)).findByText(t.academic.cycleValue(5))).toBeVisible()
    expect(within(card(t.academic.title)).getByRole('status')).toHaveTextContent(t.actions.saved)
    expect(profile.bodies).toEqual([
      {
        method: 'PUT',
        path: '/profile/academic',
        body: { career: 'Desarrollo de Software', institute: 'Instituto Andino', cycle: 5 },
      },
    ])
    // The focus is not lost: it goes back to "Editar".
    await waitFor(() => expect(editButton(t.academic.title)).toHaveFocus())
    expect(profile.requests.filter((request) => request === 'GET /profile')).toHaveLength(1)
  })

  it('while saving the button says "Guardando…" and is disabled', async () => {
    const { user } = await openProfile({}, '/perfil?seccion=academico', { writeDelayMs: 200 })
    await user.type(screen.getByLabelText(t.academic.career), 'Desarrollo de Software')

    await user.click(screen.getByRole('button', { name: t.actions.save }))

    expect(await screen.findByRole('button', { name: t.actions.saving })).toBeDisabled()
    await screen.findByText('Desarrollo de Software')
  })

  it('006-15: a 422 shows each message under its field, tied with aria-describedby, focuses the first one and keeps what was typed', async () => {
    const { user } = await openProfile({}, '/perfil?seccion=academico')
    await user.type(screen.getByLabelText(t.academic.career), 'Desarrollo de Software')
    await user.type(screen.getByLabelText(t.academic.institute), 'X')
    await user.type(screen.getByLabelText(t.academic.cycle), '12')

    await user.click(screen.getByRole('button', { name: t.actions.save }))

    const institute = screen.getByLabelText(t.academic.institute)
    await waitFor(() => expect(institute).toHaveFocus())
    expect(institute).toHaveAttribute('aria-invalid', 'true')
    expect(institute).toHaveAccessibleDescription(/Escribe al menos 2 caracteres\./)
    expect(screen.getByLabelText(t.academic.cycle)).toHaveAccessibleDescription(
      /El ciclo debe estar entre 1 y 10\./,
    )
    expect(screen.getByLabelText(t.academic.career)).not.toHaveAttribute('aria-invalid')
    expect(screen.getByLabelText(t.academic.career)).toHaveValue('Desarrollo de Software')
    expect(institute).toHaveValue('X')
    // Still in edit mode, and nothing was saved.
  })

  it('a number that is not a whole number goes to the server as typed, so the server says it is invalid', async () => {
    const { profile, user } = await openProfile({}, '/perfil?seccion=academico')
    await user.type(screen.getByLabelText(t.academic.cycle), '2,5')

    await user.click(screen.getByRole('button', { name: t.actions.save }))

    expect(await screen.findByText('El ciclo debe ser un número entero.')).toBeInTheDocument()
    expect(profile.bodies[0]?.body).toMatchObject({ cycle: '2,5' })
  })

  it('006-19: a server error shows an alert with "Reintentar", keeps what was typed and the retry saves', async () => {
    const { profile, user } = await openProfile({}, '/perfil?seccion=academico', {
      failOnce: { '/profile/academic': 500 },
    })
    await user.type(screen.getByLabelText(t.academic.career), 'Desarrollo de Software')

    await user.click(screen.getByRole('button', { name: t.actions.save }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(t.saveFailed)
    expect(screen.getByLabelText(t.academic.career)).toHaveValue('Desarrollo de Software')
    await user.click(within(alert).getByRole('button', { name: t.actions.retry }))

    expect(await within(card(t.academic.title)).findByText('Desarrollo de Software')).toBeVisible()
    expect(profile.bodies).toHaveLength(2)
  })

  it('"Cancelar" discards what was typed and goes back to read mode', async () => {
    const { user } = await openProfile(FULL_ACADEMIC, '/perfil?seccion=academico')
    await user.clear(screen.getByLabelText(t.academic.career))
    await user.type(screen.getByLabelText(t.academic.career), 'Otra carrera')

    await user.click(screen.getByRole('button', { name: t.actions.cancel }))

    await user.click(screen.getByRole('button', { name: es.redesign.discard }))
    expect(within(card(t.academic.title)).getByText('Desarrollo de Software')).toBeVisible()
    expect(screen.queryByText('Otra carrera')).not.toBeInTheDocument()
    await waitFor(() => expect(editButton(t.academic.title)).toHaveFocus())
  })
})

describe('One card at a time', () => {
  it('opening another card closes the one with nothing to lose', async () => {
    const { user } = await openProfile({}, '/perfil?seccion=academico')
    expect(screen.getByLabelText(t.academic.career)).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: t.availability.title }))

    expect(screen.queryByLabelText(t.academic.career)).not.toBeInTheDocument()
    expect(screen.getByLabelText(t.availability.hours)).toBeInTheDocument()
  })

  it('a card with unsaved changes stays open when another one is opened', async () => {
    const { user } = await openProfile({}, '/perfil?seccion=academico')
    await user.type(screen.getByLabelText(t.academic.career), 'Desarrollo')

    await user.click(screen.getByRole('button', { name: t.availability.title }))

    expect(screen.getByLabelText(t.academic.career)).toHaveValue('Desarrollo')
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: es.redesign.stay }))
    expect(screen.queryByLabelText(t.availability.hours)).not.toBeInTheDocument()
  })
})

it('protects inputs and section navigation until the current profile save finishes', async () => {
  const { user } = await openProfile({}, '/perfil?seccion=academico', { writeDelayMs: 400 })
  await user.type(screen.getByLabelText(t.academic.career), 'Desarrollo de Software')
  await user.click(screen.getByRole('button', { name: t.actions.save }))
  expect(await screen.findByRole('button', { name: t.actions.saving })).toBeDisabled()
  expect(screen.getByLabelText(t.academic.career)).toBeDisabled()
  expect(screen.getByRole('button', { name: t.skills.title })).toBeDisabled()
  expect(screen.getByRole('link', { name: es.redesign.useCv })).toHaveAttribute(
    'aria-disabled',
    'true',
  )
  await screen.findByText('Desarrollo de Software')
})
