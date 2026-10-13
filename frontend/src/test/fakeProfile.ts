import { vi } from 'vitest'
import type {
  CatalogSkill,
  Completeness,
  CompletenessItem,
  CompletenessKey,
  Profile,
  ProfileProject,
  ProfileSkill,
} from '../api/profile'

/**
 * In-memory stand-in for the profile API of spec 006, for component tests. It imitates the SHAPE
 * of the real answers (the whole profile on every write, `completeness` with `nextStep`, 422
 * with `errors[{field, rule, message}]`) and a few of the rules. The real rules and the real
 * percentage are tested in the backend and in the end-to-end tests: here the numbers only have
 * to look like the server's (20, 25, 20, 25 and 10).
 */

export const TEST_SKILLS: CatalogSkill[] = [
  { slug: 'css', name: 'CSS', aliases: ['CSS3'] },
  { slug: 'docker', name: 'Docker', aliases: ['Contenedores'] },
  { slug: 'git', name: 'Git', aliases: ['GitHub'] },
  { slug: 'java', name: 'Java', aliases: [] },
  { slug: 'javascript', name: 'JavaScript', aliases: ['JS', 'ECMAScript'] },
  { slug: 'python', name: 'Python', aliases: ['Py'] },
  { slug: 'react', name: 'React', aliases: ['ReactJS'] },
  { slug: 'sql', name: 'SQL', aliases: ['Bases de datos'] },
]

const WEIGHTS: Record<CompletenessKey, number> = {
  ACADEMIC: 15,
  SKILLS: 25,
  AVAILABILITY: 15,
  PROJECTS: 25,
  LOCATION: 10,
  ABOUT: 10,
}
const ORDER: CompletenessKey[] = [
  'ACADEMIC',
  'SKILLS',
  'AVAILABILITY',
  'PROJECTS',
  'LOCATION',
  'ABOUT',
]

export const EMPTY_PROFILE: Profile = {
  about: { displayName: null, about: null },
  academic: { career: null, institute: null, cycle: null },
  skills: [],
  availability: { hoursPerWeek: null, acceptedModalities: [], location: null },
  projects: [],
  completeness: { percent: 0, items: [], nextStep: 'SKILLS' },
  updatedAt: null,
}

/** The same rule as the server (006-09, 006-10), written again only for the fake. */
function completenessOf(profile: Profile): Completeness {
  const done: Record<CompletenessKey, boolean> = {
    ACADEMIC:
      Boolean(profile.academic.career) &&
      Boolean(profile.academic.institute) &&
      profile.academic.cycle !== null,
    SKILLS: profile.skills.length >= 3,
    AVAILABILITY:
      profile.availability.hoursPerWeek !== null &&
      profile.availability.acceptedModalities.length > 0,
    PROJECTS: profile.projects.length >= 1,
    LOCATION: Boolean(profile.availability.location),
    ABOUT: Boolean(profile.about.about),
  }
  const items: CompletenessItem[] = ORDER.map((key) => ({
    key,
    weight: WEIGHTS[key],
    done: done[key],
  }))
  const percent = items.filter((item) => item.done).reduce((sum, item) => sum + item.weight, 0)
  const pending = items.filter((item) => !item.done)
  const heaviest = Math.max(0, ...pending.map((item) => item.weight))
  const nextStep = pending.find((item) => item.weight === heaviest)?.key ?? null
  return { percent, items, nextStep }
}

type Problem = { field: string; rule: string; message: string }

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const problem = (status: number, code: string) =>
  json(status, { type: `urn:talenti:problem:${code}`, status, code })

const invalid = (...errors: Problem[]) =>
  json(422, {
    type: 'urn:talenti:problem:validation-failed',
    status: 422,
    code: 'validation-failed',
    errors,
  })

const text = (value: unknown) => (typeof value === 'string' ? value.trim() : '')

export interface FakeProfileOptions {
  /** The profile the person already has (default: empty). */
  profile?: Partial<Omit<Profile, 'completeness' | 'updatedAt'>>
  /** Exact paths (after `/api/v1`) that answer with this status, for error cases. */
  failing?: Record<string, number>
  /** Skills of the catalog (default: `TEST_SKILLS`). */
  catalog?: CatalogSkill[]
  /** `GET /profile` answers 403, like a company or admin account. */
  forbidden?: boolean
  /** The next requests to this exact path fail with this status only once. */
  failOnce?: Record<string, number>
  /** Waits this many ms before answering a write (to look at "Guardando…"). */
  writeDelayMs?: number
  /** Waits this many ms before answering `GET /profile` (to look at the skeleton). */
  readDelayMs?: number
}

export interface FakeProfile {
  /** Requests received as "METHOD /path". */
  requests: string[]
  /** The bodies of the writes, in order. */
  bodies: Array<{ method: string; path: string; body: unknown }>
  current: () => Profile
  setFailing: (failing: Record<string, number>) => void
}

/**
 * Replaces `fetch` for `/profile` and `/skills`; any other path goes to the `fetch` installed
 * before (the vacancies fake or the identity fake).
 */
export function mockProfileBackend(options: FakeProfileOptions = {}): FakeProfile {
  const previousFetch = globalThis.fetch
  let failing = { ...options.failing }
  const failOnce = { ...options.failOnce }
  const catalog = options.catalog ?? TEST_SKILLS
  const requests: string[] = []
  const bodies: FakeProfile['bodies'] = []
  let counter = 0

  let state: Profile = {
    ...EMPTY_PROFILE,
    ...options.profile,
    about: { ...EMPTY_PROFILE.about, ...options.profile?.about },
    academic: { ...EMPTY_PROFILE.academic, ...options.profile?.academic },
    availability: { ...EMPTY_PROFILE.availability, ...options.profile?.availability },
  }
  state = { ...state, completeness: completenessOf(state) }

  const commit = (changes: Partial<Profile>) => {
    state = { ...state, ...changes, updatedAt: new Date().toISOString() }
    state = { ...state, completeness: completenessOf(state) }
    return json(200, state)
  }

  function projectProblems(body: Record<string, unknown>): Problem[] {
    const found: Problem[] = []
    const name = text(body.name)
    const description = text(body.description)
    const technologies = Array.isArray(body.technologies) ? (body.technologies as string[]) : []
    if (name.length < 2) {
      found.push({ field: 'name', rule: 'min-length', message: 'Escribe al menos 2 caracteres.' })
    }
    if (description.length < 10) {
      found.push({
        field: 'description',
        rule: 'min-length',
        message: 'La descripción debe tener al menos 10 caracteres.',
      })
    }
    if (technologies.length === 0) {
      found.push({
        field: 'technologies',
        rule: 'required',
        message: 'Agrega al menos una tecnología.',
      })
    }
    return found
  }

  const handler = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(String(input), 'http://localhost')
    const isMine = url.pathname.startsWith('/api/v1/profile') || url.pathname === '/api/v1/skills'
    if (!isMine) return previousFetch(input, init)

    const path = url.pathname.replace('/api/v1', '')
    const method = (init?.method ?? 'GET').toUpperCase()
    requests.push(`${method} ${path}`)
    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : null
    if (method !== 'GET') bodies.push({ method, path, body })

    if (failOnce[path] !== undefined) {
      const status = failOnce[path]
      delete failOnce[path]
      return problem(status, 'internal-error')
    }
    const forced = failing[path]
    if (forced !== undefined) return problem(forced, 'internal-error')

    if (method === 'GET' && path === '/skills') return json(200, catalog)
    if (options.forbidden) return problem(403, 'access-denied')
    if (method === 'GET' && path === '/profile') {
      if (options.readDelayMs) await new Promise((r) => setTimeout(r, options.readDelayMs))
      return json(200, state)
    }

    if (options.writeDelayMs) await new Promise((r) => setTimeout(r, options.writeDelayMs))

    if (method === 'PUT' && path === '/profile/about') {
      const displayName = text(body?.displayName)
      const about = text(body?.about)
      const found: Problem[] = []
      if (displayName && displayName.length < 2) {
        found.push({
          field: 'displayName',
          rule: 'min-length',
          message: 'Escribe al menos 2 caracteres.',
        })
      }
      if (about && about.length < 10) {
        found.push({
          field: 'about',
          rule: 'min-length',
          message: 'Escribe al menos 10 caracteres.',
        })
      }
      if (found.length > 0) return invalid(...found)
      return commit({ about: { displayName: displayName || null, about: about || null } })
    }

    if (method === 'PUT' && path === '/profile/academic') {
      const career = text(body?.career)
      const institute = text(body?.institute)
      const cycle = body?.cycle
      const found: Problem[] = []
      if (career && career.length < 2) {
        found.push({
          field: 'career',
          rule: 'min-length',
          message: 'Escribe al menos 2 caracteres.',
        })
      }
      if (institute && institute.length < 2) {
        found.push({
          field: 'institute',
          rule: 'min-length',
          message: 'Escribe al menos 2 caracteres.',
        })
      }
      if (cycle !== null && cycle !== undefined) {
        if (typeof cycle !== 'number') {
          found.push({
            field: 'cycle',
            rule: 'invalid',
            message: 'El ciclo debe ser un número entero.',
          })
        } else if (cycle < 1 || cycle > 10) {
          found.push({
            field: 'cycle',
            rule: 'out-of-range',
            message: 'El ciclo debe estar entre 1 y 10.',
          })
        }
      }
      if (found.length > 0) return invalid(...found)
      return commit({
        academic: {
          career: career || null,
          institute: institute || null,
          cycle: typeof cycle === 'number' ? cycle : null,
        },
      })
    }

    if (method === 'PUT' && path === '/profile/skills') {
      const items = (Array.isArray(body?.skills) ? body.skills : []) as Array<{
        slug: string
        level: number
      }>
      const found: Problem[] = []
      items.forEach((item, index) => {
        if (!catalog.some((skill) => skill.slug === item.slug)) {
          found.push({
            field: `skills[${index}].slug`,
            rule: 'unknown-skill',
            message: 'Esa habilidad no está en el catálogo.',
          })
        }
      })
      if (found.length > 0) return invalid(...found)
      const skills: ProfileSkill[] = items.map((item) => ({
        slug: item.slug,
        name: catalog.find((skill) => skill.slug === item.slug)?.name ?? item.slug,
        level: item.level as ProfileSkill['level'],
        evidence: 'DECLARED',
      }))
      return commit({ skills })
    }

    if (method === 'PUT' && path === '/profile/availability') {
      const hours = body?.hoursPerWeek
      const found: Problem[] = []
      if (hours !== null && hours !== undefined) {
        if (typeof hours !== 'number') {
          found.push({
            field: 'hoursPerWeek',
            rule: 'invalid',
            message: 'Escribe un número entero.',
          })
        } else if (hours < 1 || hours > 40) {
          found.push({
            field: 'hoursPerWeek',
            rule: 'out-of-range',
            message: 'Las horas deben estar entre 1 y 40.',
          })
        }
      }
      if (found.length > 0) return invalid(...found)
      return commit({
        availability: {
          hoursPerWeek: typeof hours === 'number' ? hours : null,
          acceptedModalities: (body?.acceptedModalities ??
            []) as Profile['availability']['acceptedModalities'],
          location: text(body?.location) || null,
        },
      })
    }

    if (method === 'POST' && path === '/profile/projects') {
      const found = projectProblems(body ?? {})
      if (found.length > 0) return invalid(...found)
      if (state.projects.length >= 10) {
        return invalid({ field: 'projects', rule: 'limit', message: 'Máximo 10 proyectos.' })
      }
      counter += 1
      const project: ProfileProject = {
        id: `p${counter}`,
        name: text(body?.name),
        description: text(body?.description),
        technologies: (body?.technologies ?? []) as string[],
      }
      const response = commit({ projects: [...state.projects, project] })
      return new Response(response.body, { status: 201, headers: response.headers })
    }

    const match = /^\/profile\/projects\/([^/]+)$/.exec(path)
    if (match && (method === 'PUT' || method === 'DELETE')) {
      const id = decodeURIComponent(match[1] ?? '')
      if (!state.projects.some((project) => project.id === id)) {
        return problem(404, 'project-not-found')
      }
      if (method === 'DELETE') {
        return commit({ projects: state.projects.filter((project) => project.id !== id) })
      }
      const found = projectProblems(body ?? {})
      if (found.length > 0) return invalid(...found)
      return commit({
        projects: state.projects.map((project) =>
          project.id === id
            ? {
                ...project,
                name: text(body?.name),
                description: text(body?.description),
                technologies: (body?.technologies ?? []) as string[],
              }
            : project,
        ),
      })
    }

    return problem(404, 'not-found')
  })
  vi.stubGlobal('fetch', handler)

  return {
    requests,
    bodies,
    current: () => state,
    setFailing: (next) => {
      failing = { ...next }
    },
  }
}
