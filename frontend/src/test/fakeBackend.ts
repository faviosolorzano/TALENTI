import { vi } from 'vitest'
import type {
  FeaturedCompany,
  FilterOptions,
  Modality,
  VacancyCardData,
  VacancyDetail,
  VacancyPage,
} from '../api/vacancies'

/**
 * In-memory stand-in for the public vacancy API, for component tests. It only imitates the
 * shape and the basic behavior of spec 004 (filters, sort, pages, 404); the real behavior is
 * tested in the backend.
 */

interface SeedVacancy {
  title: string
  company: string
  city: string
  modality: Modality
  location: string | null
  required: string[]
  desirable: string[]
  featured?: boolean
  daysAgo: number
}

// The 12 example vacancies of spec 004, section 5 (company, place and skills).
const SEED: SeedVacancy[] = [
  {
    title: 'Practicante Backend Java',
    company: 'Andes Tech',
    city: 'Lima',
    modality: 'REMOTE',
    location: null,
    required: ['Java', 'Spring Boot', 'SQL'],
    desirable: ['Git', 'Docker'],
    featured: true,
    daysAgo: 1,
  },
  {
    title: 'Practicante Frontend',
    company: 'Nexa Digital',
    city: 'Lima',
    modality: 'HYBRID',
    location: 'Lima',
    required: ['JavaScript', 'HTML', 'CSS'],
    desirable: ['React', 'Git'],
    featured: true,
    daysAgo: 2,
  },
  {
    title: 'Practicante React y TypeScript',
    company: 'Nexa Digital',
    city: 'Lima',
    modality: 'REMOTE',
    location: null,
    required: ['React', 'TypeScript', 'Git'],
    desirable: ['Testing', 'REST APIs'],
    daysAgo: 3,
  },
  {
    title: 'Practicante de Calidad',
    company: 'Punto Código',
    city: 'Lima',
    modality: 'HYBRID',
    location: 'Arequipa',
    required: ['Testing', 'Postman', 'Git'],
    desirable: ['Selenium', 'SQL'],
    daysAgo: 4,
  },
  {
    title: 'Practicante iOS',
    company: 'Ruta Labs',
    city: 'Lima',
    modality: 'ONSITE',
    location: 'Lima',
    required: ['Swift', 'REST APIs', 'Git'],
    desirable: ['Testing'],
    daysAgo: 5,
  },
  {
    title: 'Practicante Android',
    company: 'Ruta Labs',
    city: 'Lima',
    modality: 'HYBRID',
    location: 'Lima',
    required: ['Kotlin', 'Android', 'Git'],
    desirable: ['REST APIs'],
    daysAgo: 6,
  },
  {
    title: 'Practicante de Datos con SQL',
    company: 'Cóndor Datos',
    city: 'Lima',
    modality: 'HYBRID',
    location: 'Lima',
    required: ['SQL', 'Python', 'PostgreSQL'],
    desirable: ['Git', 'Linux'],
    featured: true,
    daysAgo: 7,
  },
  {
    title: 'Practicante Python y automatización',
    company: 'Cóndor Datos',
    city: 'Lima',
    modality: 'REMOTE',
    location: null,
    required: ['Python', 'Git', 'Linux'],
    desirable: ['Docker', 'SQL'],
    daysAgo: 8,
  },
  {
    title: 'Practicante Full Stack Node.js',
    company: 'Quipu Systems',
    city: 'Lima',
    modality: 'ONSITE',
    location: 'Lima',
    required: ['JavaScript', 'Node.js', 'SQL'],
    desirable: ['MongoDB', 'Docker'],
    daysAgo: 9,
  },
  {
    title: 'Practicante .NET',
    company: 'Misti Software',
    city: 'Arequipa',
    modality: 'ONSITE',
    location: 'Arequipa',
    required: ['C#', '.NET', 'SQL'],
    desirable: ['Git', 'Scrum'],
    daysAgo: 10,
  },
  {
    title: 'Practicante PHP y Laravel',
    company: 'Misti Software',
    city: 'Arequipa',
    modality: 'HYBRID',
    location: 'Arequipa',
    required: ['PHP', 'Laravel', 'SQL'],
    desirable: ['JavaScript', 'Git'],
    daysAgo: 11,
  },
  {
    title: 'Practicante DevOps junior',
    company: 'Tambo Cloud',
    city: 'Lima',
    modality: 'REMOTE',
    location: null,
    required: ['Linux', 'Docker', 'Git'],
    desirable: ['AWS', 'Python'],
    featured: true,
    daysAgo: 12,
  },
]

const slugOf = (name: string) =>
  name
    .toLowerCase()
    .normalize('NFD')
    .replace(/[̀-ͯ]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-|-$/g, '')

const idOf = (index: number) => `00000000-0000-4000-8000-${String(index + 1).padStart(12, '0')}`
const NOW = Date.now()
const isoDaysAgo = (days: number) => new Date(NOW - days * 24 * 60 * 60 * 1000).toISOString()

export function seedCard(seed: SeedVacancy, index: number): VacancyCardData {
  return {
    id: idOf(index),
    title: seed.title,
    summary: `Resumen de ${seed.title}.`,
    modality: seed.modality,
    location: seed.location,
    hoursPerWeek: 20,
    publishedAt: isoDaysAgo(seed.daysAgo),
    featured: Boolean(seed.featured),
    company: { id: `company-${slugOf(seed.company)}`, name: seed.company, city: seed.city },
    skills: [
      ...seed.required.map((name) => ({ slug: slugOf(name), name, kind: 'REQUIRED' as const })),
      ...seed.desirable.map((name) => ({ slug: slugOf(name), name, kind: 'DESIRABLE' as const })),
    ],
  }
}

export const VACANCY_IDS = SEED.map((_, index) => idOf(index))

export interface FakeBackendOptions {
  /** Vacancies the backend knows (default: the 12 of the spec). */
  vacancies?: SeedVacancy[]
  /** Ids that answer as closed. */
  closed?: string[]
  /** Responses forced for an exact path, for error cases: `{ '/vacancies/featured': 500 }`. */
  failing?: Record<string, number>
  pageSize?: number
}

export interface FakeBackend {
  fetch: ReturnType<typeof vi.fn>
  /** Paths (with query) requested so far. */
  requested: () => string[]
  setFailing: (failing: Record<string, number>) => void
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const normalize = (text: string) => text.toLowerCase().normalize('NFD').replace(/[̀-ͯ]/g, '')

/** Replaces `fetch` with the fake backend and returns handles to inspect it. */
export function mockBackend(options: FakeBackendOptions = {}): FakeBackend {
  const seeds = options.vacancies ?? SEED
  const cards = seeds.map(seedCard)
  const closed = new Set(options.closed ?? [])
  let failing = options.failing ?? {}
  const calls: string[] = []

  const open = () => cards.filter((card) => !closed.has(card.id))

  function list(params: URLSearchParams): VacancyPage {
    const terms = normalize(params.get('q') ?? '')
      .split(/\s+/)
      .filter(Boolean)
    const modalities = params.getAll('modality')
    const locations = params.getAll('location')
    const skills = params.getAll('skill')
    let items = open().filter((card) => {
      const haystack = normalize(
        [card.title, card.summary, card.company.name, ...card.skills.map((s) => s.name)].join(' '),
      )
      return (
        terms.every((term) => haystack.includes(term)) &&
        (modalities.length === 0 || modalities.includes(card.modality)) &&
        (locations.length === 0 || (card.location !== null && locations.includes(card.location))) &&
        (skills.length === 0 || card.skills.some((skill) => skills.includes(skill.slug)))
      )
    })
    items =
      params.get('sort') === 'company'
        ? [...items].sort((a, b) => a.company.name.localeCompare(b.company.name, 'es'))
        : [...items].sort((a, b) => b.publishedAt.localeCompare(a.publishedAt))
    const size = Number(params.get('size') ?? options.pageSize ?? 9)
    const page = Number(params.get('page') ?? 1)
    return {
      items: items.slice((page - 1) * size, page * size),
      page,
      size,
      totalItems: items.length,
      totalPages: Math.ceil(items.length / size),
    }
  }

  function filters(): FilterOptions {
    const count = <T>(values: T[]) => {
      const map = new Map<T, number>()
      values.forEach((value) => map.set(value, (map.get(value) ?? 0) + 1))
      return map
    }
    const skillNames = new Map<string, string>()
    open().forEach((card) => card.skills.forEach((s) => skillNames.set(s.slug, s.name)))
    return {
      openCount: open().length,
      modalities: [...count(open().map((card) => card.modality))].map(([value, n]) => ({
        value,
        count: n,
      })),
      locations: [...count(open().flatMap((card) => (card.location ? [card.location] : [])))].map(
        ([value, n]) => ({ value, count: n }),
      ),
      skills: [...count(open().flatMap((card) => card.skills.map((s) => s.slug)))].map(
        ([slug, n]) => ({ slug, name: skillNames.get(slug) ?? slug, count: n }),
      ),
    }
  }

  function companies(): FeaturedCompany[] {
    const featuredNames = ['Andes Tech', 'Nexa Digital', 'Cóndor Datos', 'Tambo Cloud']
    return featuredNames.flatMap((name) => {
      const own = open().filter((card) => card.company.name === name)
      const first = own[0]
      return first
        ? [
            {
              id: first.company.id,
              name,
              city: first.company.city,
              shortDescription: `Descripción de ${name}.`,
              openVacancies: own.length,
            },
          ]
        : []
    })
  }

  function detail(id: string): VacancyDetail | null {
    const card = cards.find((item) => item.id === id)
    if (!card) return null
    const requirement = (kind: 'REQUIRED' | 'DESIRABLE') =>
      card.skills
        .filter((s) => s.kind === kind)
        .map((s) => ({
          skill: { slug: s.slug, name: s.name },
          minLevel: kind === 'REQUIRED' ? 2 : 1,
        }))
    return {
      ...card,
      company: { ...card.company, shortDescription: `Descripción de ${card.company.name}.` },
      description: `Descripción de ${card.title}.\n\nSegundo párrafo.`,
      status: closed.has(id) ? 'CLOSED' : 'OPEN',
      requirements: { required: requirement('REQUIRED'), desirable: requirement('DESIRABLE') },
    }
  }

  const handler = vi.fn(async (input: RequestInfo | URL): Promise<Response> => {
    const url = new URL(String(input), 'http://localhost')
    calls.push(`${url.pathname.replace('/api/v1', '')}${url.search}`)
    const path = url.pathname.replace('/api/v1', '')
    const forced = Object.entries(failing).find(([failingPath]) => path === failingPath)
    if (forced) return json(forced[1], { title: 'Error', status: forced[1] })

    if (path === '/vacancies') return json(200, list(url.searchParams))
    if (path === '/vacancies/filters') return json(200, filters())
    if (path === '/vacancies/featured') {
      const limit = Number(url.searchParams.get('limit') ?? 4)
      return json(200, {
        items: open()
          .filter((card) => card.featured)
          .slice(0, limit),
      })
    }
    if (path === '/companies/featured') return json(200, { items: companies() })
    const match = /^\/vacancies\/([^/]+)$/.exec(path)
    if (match) {
      const found = detail(decodeURIComponent(match[1] ?? ''))
      return found
        ? json(200, found)
        : json(404, { type: 'vacancy-not-found', title: 'No encontrada', status: 404 })
    }
    return json(404, { status: 404 })
  })
  vi.stubGlobal('fetch', handler)

  return {
    fetch: handler,
    requested: () => [...calls],
    setFailing: (next) => {
      failing = next
    },
  }
}
