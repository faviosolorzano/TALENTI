import { vi } from 'vitest'
import type { Compatibility, CompatibilitySummary, RecommendedPage } from '../api/compatibility'
import type { VacancyPage } from '../api/vacancies'
import { VACANCY_IDS } from './fakeBackend'

/**
 * In-memory stand-in for the compatibility API of spec 007, for component tests. The numbers it
 * answers are INVENTED here, the way the server would send them: the interface under test never
 * calculates a percentage, it only draws what arrives. Paths it does not know go to the `fetch`
 * installed before (the vacancies fake).
 */

export const SAMPLE_FORMULA: Compatibility['formula'] = {
  version: '1',
  weights: { required: 50, desirable: 15, conditions: 20, similarity: 15 },
  cap: 69,
  minEvaluableWeight: 50,
  similarityEnabled: false,
}

const levelOf = (percent: number): CompatibilitySummary['level'] =>
  percent >= 80 ? 'HIGH' : percent >= 60 ? 'MEDIUM' : 'LOW'

/** Invented percentage of the n-th seeded vacancy: 90, 83, 76 ... */
export const fakePercentOf = (id: string): number => 90 - 7 * Math.max(0, VACANCY_IDS.indexOf(id))

export function fakeSummary(
  vacancyId: string,
  overrides: Partial<CompatibilitySummary> = {},
): CompatibilitySummary {
  const percent = fakePercentOf(vacancyId)
  return {
    vacancyId,
    status: 'CALCULATED',
    percent,
    level: levelOf(percent),
    capped: false,
    ...overrides,
  }
}

/** The example of RN-01 as the server answers it: 81 %, with matches, gaps and missing data. */
export function sampleDetail(
  vacancyId: string,
  overrides: Partial<Compatibility> = {},
): Compatibility {
  return {
    ...fakeSummary(vacancyId, { percent: 81, level: 'HIGH' }),
    reasons: [],
    informationBasis: 100,
    components: [
      { key: 'REQUIRED', weight: 50, score: 0.833, evaluable: true },
      { key: 'DESIRABLE', weight: 15, score: 0.5, evaluable: true },
      { key: 'CONDITIONS', weight: 20, score: 1, evaluable: true },
      { key: 'SIMILARITY', weight: 15, score: null, evaluable: false },
    ],
    matches: [
      {
        type: 'REQUIREMENT',
        skill: { slug: 'java', name: 'Java' },
        requirementKind: 'REQUIRED',
        requiredLevel: 2,
        profileLevel: 2,
        outcome: 'MATCH',
        evidence: 'DECLARED',
      },
      {
        type: 'REQUIREMENT',
        skill: { slug: 'git', name: 'Git' },
        requirementKind: 'DESIRABLE',
        requiredLevel: 1,
        profileLevel: 1,
        outcome: 'MATCH',
        evidence: 'DECLARED',
      },
      {
        type: 'CONDITION',
        factor: 'MODALITY',
        outcome: 'MATCH',
        vacancyValue: 'REMOTE',
        profileValue: 'REMOTE,HYBRID',
      },
    ],
    gaps: [
      {
        type: 'REQUIREMENT',
        skill: { slug: 'sql', name: 'SQL' },
        requirementKind: 'REQUIRED',
        requiredLevel: 2,
        profileLevel: 1,
        outcome: 'PARTIAL',
        evidence: 'DECLARED',
      },
      {
        type: 'REQUIREMENT',
        skill: { slug: 'docker', name: 'Docker' },
        requirementKind: 'DESIRABLE',
        requiredLevel: 1,
        profileLevel: null,
        outcome: 'MISSING',
        evidence: 'DECLARED',
      },
    ],
    missingInfo: [
      {
        type: 'CONDITION',
        factor: 'AVAILABILITY',
        outcome: 'UNKNOWN',
        vacancyValue: '20',
        profileValue: null,
      },
    ],
    explanation: {
      source: 'TEMPLATE',
      version: '1',
      text: 'Cumples 2 de 3 requisitos obligatorios. Para subir tu compatibilidad, refuerza SQL.',
    },
    formula: SAMPLE_FORMULA,
    ...overrides,
  }
}

/** A result the server could not back with a number. */
export function insufficientDetail(
  vacancyId: string,
  reason: Compatibility['reasons'][number] = 'NO_SKILLS',
): Compatibility {
  return sampleDetail(vacancyId, {
    status: 'INSUFFICIENT',
    percent: null,
    level: null,
    informationBasis: null,
    reasons: [reason],
    matches: [],
    gaps: [],
    missingInfo: reason === 'NO_SKILLS' ? [{ type: 'PROFILE', key: 'SKILLS' }] : [],
    explanation: { source: 'TEMPLATE', version: '1', text: '' },
  })
}

export interface FakeCompatibilityOptions {
  /** Does the profile have enough to be calculated? (default: yes) */
  sufficient?: boolean
  /** Full result by vacancy id (default: `sampleDetail`, or `insufficientDetail` when not sufficient). */
  details?: Record<string, Compatibility>
  /** Summary overrides by vacancy id. */
  summaries?: Record<string, Partial<CompatibilitySummary>>
  /** Exact paths (after `/api/v1`, no query) that answer with this status. */
  failing?: Record<string, number>
  /** Wait this many ms before answering (to look at skeletons). */
  delayMs?: number
}

export interface FakeCompatibility {
  /** Paths (with query) requested so far under `/compatibility`. */
  requests: string[]
  setFailing: (failing: Record<string, number>) => void
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

export function mockCompatibilityBackend(
  options: FakeCompatibilityOptions = {},
): FakeCompatibility {
  const previousFetch = globalThis.fetch
  const sufficient = options.sufficient ?? true
  let failing = { ...options.failing }
  const requests: string[] = []

  const summaryOf = (id: string): CompatibilitySummary =>
    sufficient
      ? fakeSummary(id, options.summaries?.[id])
      : fakeSummary(id, { status: 'INSUFFICIENT', percent: null, level: null })

  const handler = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(String(input), 'http://localhost')
    if (!url.pathname.startsWith('/api/v1/compatibility')) return previousFetch(input, init)

    const path = url.pathname.replace('/api/v1', '')
    requests.push(`${path}${url.search}`)
    if (options.delayMs) await new Promise((resolve) => setTimeout(resolve, options.delayMs))
    if (failing[path] !== undefined) {
      const status = failing[path]
      return json(status, { type: 'urn:talenti:problem:error', status })
    }

    if (path === '/compatibility/summaries') {
      const ids = url.searchParams.getAll('vacancyId')
      return json(200, { sufficient, items: ids.map(summaryOf) })
    }

    if (path === '/compatibility/recommended') {
      const page = Number(url.searchParams.get('page') ?? 1)
      const size = Number(url.searchParams.get('size') ?? 9)
      const all = await previousFetch('/api/v1/vacancies?size=100&sort=recent')
      const cards = ((await all.json()) as VacancyPage).items
      const ordered = sufficient
        ? [...cards].sort((a, b) => fakePercentOf(b.id) - fakePercentOf(a.id))
        : []
      const body: RecommendedPage = {
        profile: { sufficient },
        items: ordered.slice((page - 1) * size, page * size).map((vacancy) => ({
          vacancy,
          compatibility: summaryOf(vacancy.id),
        })),
        page,
        size,
        totalItems: ordered.length,
        totalPages: Math.ceil(ordered.length / size),
      }
      return json(200, body)
    }

    const match = /^\/compatibility\/vacancies\/([^/]+)$/.exec(path)
    if (match) {
      const id = decodeURIComponent(match[1] ?? '')
      const exists = await previousFetch(`/api/v1/vacancies/${encodeURIComponent(id)}`)
      if (!exists.ok) return json(404, { type: 'vacancy-not-found', status: 404 })
      return json(
        200,
        options.details?.[id] ?? (sufficient ? sampleDetail(id) : insufficientDetail(id)),
      )
    }
    return json(404, { status: 404 })
  })
  vi.stubGlobal('fetch', handler)

  return {
    requests,
    setFailing: (next) => {
      failing = { ...next }
    },
  }
}
