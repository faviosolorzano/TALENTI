import { screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { es } from '../../i18n/es'
import { clearCsrfCookie } from '../../test/fakeIdentity'
import { TEST_SKILLS, type FakeProfileOptions } from '../../test/fakeProfile'
import { openStudentApp } from '../../test/studentApp'
import { normalizeSearch, searchSkills } from './skillSearch'
import { typedNumber } from './typedNumber'

afterEach(clearCsrfCookie)
const t = es.profile

const card = (title: string) => screen.getByRole('region', { name: title })

async function open(
  section: string,
  data: NonNullable<FakeProfileOptions['profile']> = {},
  options: FakeProfileOptions = {},
) {
  const app = openStudentApp(`/perfil?seccion=${section}`, {
    profile: { ...options, profile: data },
  })
  await screen.findByRole('region', { name: t.about.title })
  return app
}

describe('Sobre ti (about)', () => {
  it('is the first card, and saves name and presentation with their counters', async () => {
    const { profile, user } = await open('sobre-ti')
    const ids = screen.getAllByRole('region').map((region) => region.getAttribute('id'))
    expect(ids.find((id) => id?.startsWith('seccion-'))).toBe('seccion-sobre-ti')

    await user.type(screen.getByLabelText(t.about.displayName), 'Ana')
    await user.type(screen.getByLabelText(t.about.about), 'Estudio software y busco practicar.')
    expect(
      screen.getByText(t.about.counter('Estudio software y busco practicar.'.length, 500)),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: t.actions.save }))

    expect(await within(card(t.about.title)).findByText('Ana')).toBeVisible()
    expect(profile.bodies[0]).toMatchObject({ path: '/profile/about' })
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '10')
  })

  it('a 422 is shown under its field and the focus goes there', async () => {
    const { user } = await open('sobre-ti')
    await user.type(screen.getByLabelText(t.about.displayName), 'A')
    await user.click(screen.getByRole('button', { name: t.actions.save }))

    const field = screen.getByLabelText(t.about.displayName)
    await waitFor(() => expect(field).toHaveFocus())
    expect(field).toHaveAccessibleDescription(/Escribe al menos 2 caracteres\./)
  })
})

describe('Habilidades (006-16)', () => {
  it('"js" finds JavaScript by synonym, and a miss says so', async () => {
    const { user } = await open('habilidades')
    const search = await screen.findByRole('searchbox', { name: t.skills.pickerLabel })

    await user.type(search, 'js')
    await user.click(screen.getByRole('button', { name: /JavaScript/ }))
    const skills = card(t.skills.title)
    expect(
      within(skills).getByRole('radiogroup', { name: t.skills.levelGroup('JavaScript') }),
    ).toBeVisible()
    expect(within(skills).getByText(t.skills.declared)).toBeInTheDocument()
    await user.type(search, 'zzz')
    expect(screen.getByText(t.skills.none)).toBeInTheDocument()
  })

  it('saves levels, "Quitar" removes, and three skills raise the server percentage', async () => {
    const { profile, user } = await open('habilidades')
    const search = await screen.findByRole('searchbox', { name: t.skills.pickerLabel })
    for (const name of ['Java', 'SQL', 'Git']) {
      await user.type(search, name)
      await user.keyboard('{Enter}')
    }
    await user.click(
      within(card(t.skills.title)).getAllByRole('radio', { name: t.skills.levels[3] })[0]!,
    )
    await user.click(screen.getByRole('button', { name: t.actions.save }))

    await waitFor(() =>
      expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '25'),
    )
    expect(profile.bodies[0]?.body).toEqual({
      skills: [
        { slug: 'java', level: 3 },
        { slug: 'sql', level: 1 },
        { slug: 'git', level: 1 },
      ],
    })
  })

  it('searchSkills ignores case and accents and hides excluded skills', () => {
    expect(normalizeSearch(' JÁVA ')).toBe('java')
    expect(searchSkills(TEST_SKILLS, 'js', new Set())[0]?.skill.slug).toBe('javascript')
    expect(
      searchSkills(TEST_SKILLS, 'java', new Set(['java'])).map((match) => match.skill.slug),
    ).toEqual(['javascript'])
    expect(searchSkills(TEST_SKILLS, 'zzz', new Set())).toEqual([])
  })

  it('typedNumber sends whole numbers as numbers and the rest as typed', () => {
    expect(typedNumber('')).toBeNull()
    expect(typedNumber(' 12 ')).toBe(12)
    expect(typedNumber('2,5')).toBe('2,5')
  })
})

describe('Disponibilidad (006-17)', () => {
  it('saves hours, modalities and city', async () => {
    const { profile, user } = await open('disponibilidad')
    await user.type(screen.getByLabelText(t.availability.hours), '30')
    await user.type(screen.getByLabelText(t.availability.location), 'Lima')
    await user.click(screen.getByRole('checkbox', { name: 'Remoto' }))
    await user.click(screen.getByRole('checkbox', { name: 'Híbrido' }))
    await user.click(screen.getByRole('button', { name: t.actions.save }))

    expect(await within(card(t.availability.title)).findByText('Remoto, Híbrido')).toBeVisible()
    expect(profile.bodies[0]?.body).toEqual({
      hoursPerWeek: 30,
      acceptedModalities: ['REMOTE', 'HYBRID'],
      location: 'Lima',
    })
  })

  it('99 hours is rejected by the server and focused', async () => {
    const { user } = await open('disponibilidad')
    await user.type(screen.getByLabelText(t.availability.hours), '99')
    await user.click(screen.getByRole('button', { name: t.actions.save }))
    expect(await screen.findByText('Las horas deben estar entre 1 y 40.')).toBeInTheDocument()
    expect(screen.getByLabelText(t.availability.hours)).toHaveFocus()
  })
})

describe('Proyectos (006-18)', () => {
  const project = {
    id: 'p0',
    name: 'Tienda',
    description: '<b>Una tienda</b> en línea.',
    technologies: ['React'],
  }

  it('adds, edits and deletes with confirmation; the text is shown as text', async () => {
    const { user } = await open('proyectos', { projects: [project] })
    expect(screen.getByText('<b>Una tienda</b> en línea.')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: t.projects.add }))
    await user.type(screen.getByLabelText(t.projects.name), 'Blog')
    await user.type(
      screen.getByLabelText(t.projects.description),
      'Un blog personal con comentarios.',
    )
    await user.type(screen.getByRole('textbox', { name: t.projects.technologies }), 'Node{Enter}')
    await user.click(screen.getByRole('button', { name: t.projects.save }))
    const list = await screen.findByRole('list', { name: t.projects.listLabel })
    expect(await within(list).findByText('Blog')).toBeVisible()

    await user.click(screen.getByRole('button', { name: t.projects.editLabel('Blog') }))
    await user.clear(screen.getByLabelText(t.projects.name))
    await user.type(screen.getByLabelText(t.projects.name), 'Blog 2')
    await user.click(screen.getByRole('button', { name: t.projects.save }))
    expect(await within(list).findByText('Blog 2')).toBeVisible()

    await user.click(screen.getByRole('button', { name: t.projects.removeLabel('Blog 2') }))
    await user.click(screen.getByRole('button', { name: t.actions.cancel }))
    expect(within(list).getByText('Blog 2')).toBeVisible()
    await user.click(screen.getByRole('button', { name: t.projects.removeLabel('Blog 2') }))
    await user.click(screen.getByRole('button', { name: t.projects.confirmYes }))
    await waitFor(() => expect(screen.queryByText('Blog 2')).not.toBeInTheDocument())
  })

  it('a short description shows the server message', async () => {
    const { user } = await open('proyectos')
    await user.click(screen.getByRole('button', { name: t.projects.add }))
    await user.type(screen.getByLabelText(t.projects.name), 'Blog')
    await user.type(screen.getByLabelText(t.projects.description), 'corta')
    await user.click(screen.getByRole('button', { name: t.projects.save }))
    expect(await screen.findByText(/al menos 10 caracteres/)).toBeInTheDocument()
  })

  it('at 10 projects it warns and offers no "Agregar proyecto"', async () => {
    const many = Array.from({ length: 10 }, (_, i) => ({ ...project, id: `p${i}`, name: `P${i}` }))
    await open('proyectos', { projects: many })
    expect(screen.getByText(t.projects.limitReached)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: t.projects.add })).not.toBeInTheDocument()
  })
})
