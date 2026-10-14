import { vi } from 'vitest'
import type {
  AnalysisStatus,
  CvAnalysis,
  CvUsage,
  DegradedReason,
  FailureReason,
  ProjectValue,
  SkillValue,
  Suggestion,
} from '../api/cv'
import type { Profile, ProfileSkill } from '../api/profile'
import { EMPTY_PROFILE, TEST_SKILLS, completenessOf } from './fakeProfile'

/**
 * In-memory stand-in for the CV reader API of spec 009, for component tests. It imitates the
 * SHAPE of the real answers (202 on upload, `current` with its states, 204 when there is none,
 * the errors with their `code` and `reason`, 422 with `decisions[i]...` paths). The real rules
 * (what is read, the quota, the percentage) are tested in the backend and end to end.
 * All the data is invented.
 */

export const SAMPLE_SUGGESTIONS: Suggestion[] = [
  {
    id: 's-career',
    kind: 'ACADEMIC',
    field: 'CAREER',
    value: { value: 'Desarrollo de Software' },
    evidence: 'Carrera: Desarrollo de Software',
    currentValue: null,
  },
  {
    id: 's-cycle',
    kind: 'ACADEMIC',
    field: 'CYCLE',
    value: { value: 5 },
    evidence: 'Cursando el 5.º ciclo',
    currentValue: '3',
  },
  {
    id: 's-java',
    kind: 'SKILL',
    field: null,
    value: { slug: 'java', name: 'Java', level: null } satisfies SkillValue,
    evidence: 'Experiencia en Java y SQL',
    currentValue: null,
  },
  {
    id: 's-kotlin',
    kind: 'SKILL',
    field: null,
    value: { slug: null, name: 'Kotlin', level: null } satisfies SkillValue,
    evidence: 'Apps con Kotlin',
    currentValue: null,
  },
  {
    id: 's-project',
    kind: 'PROJECT',
    field: null,
    value: {
      name: 'Tienda en línea',
      description: 'Una tienda para un negocio de barrio.',
      technologies: ['Java', 'SQL'],
    } satisfies ProjectValue,
    evidence: 'Proyecto: Tienda en línea',
    currentValue: null,
  },
]

export interface FakeCvOptions {
  /** An analysis already open when the page opens. */
  open?: Partial<CvAnalysis>
  usage?: Partial<CvUsage['enhanced']>
  /** Suggestions that a finished reading gives (default `SAMPLE_SUGGESTIONS`). */
  suggestions?: Suggestion[]
  /** How many `GET current` answers say `ANALYZING` before `READY` (default 1). */
  pollsUntilReady?: number
  /** The reading ends in `FAILED` with this reason instead of `READY`. */
  failWith?: FailureReason
  /** `POST /cv/analyses` answers this error. */
  uploadError?: { status: number; code: string; extra?: Record<string, unknown> }
  /** `POST .../confirm` answers this status once (then it works). */
  confirmFailsOnce?: number
  /** `GET /cv/analyses/current` answers 500. */
  currentFails?: boolean
  /** Reading that came out degraded. */
  degraded?: DegradedReason
  /** Waits this many ms before answering `GET /cv/analyses/current`. */
  readDelayMs?: number
}

export interface FakeCv {
  requests: string[]
  /** Bodies of the JSON writes, in order. */
  bodies: Array<{ method: string; path: string; body: unknown }>
  /** The files and modes received by `POST /cv/analyses`. */
  uploads: Array<{ name: string; type: string; size: number; mode: string }>
  current: () => CvAnalysis | null
  setCurrentFails: (value: boolean) => void
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const problem = (status: number, code: string, extra: Record<string, unknown> = {}) =>
  json(status, { type: `urn:talenti:problem:${code}`, status, code, ...extra })

/** Replaces `fetch` for `/cv/*`; any other path goes to the `fetch` installed before. */
export function mockCvBackend(options: FakeCvOptions = {}): FakeCv {
  const previousFetch = globalThis.fetch
  const requests: string[] = []
  const bodies: FakeCv['bodies'] = []
  const uploads: FakeCv['uploads'] = []
  let currentFails = options.currentFails ?? false
  let confirmFailsOnce = options.confirmFailsOnce
  const suggestions = options.suggestions ?? SAMPLE_SUGGESTIONS
  let used = options.usage?.used ?? 0
  let polls = 0
  let analysis: CvAnalysis | null = options.open
    ? {
        id: 'a1',
        status: 'READY',
        source: 'RULES',
        level: 'BASIC',
        reused: false,
        degraded: false,
        degradedReason: null,
        createdAt: '2026-10-14T10:00:00Z',
        expiresAt: '2026-10-21T10:00:00Z',
        failureReason: null,
        inputTruncated: false,
        alreadyInProfile: 0,
        suggestions,
        ...options.open,
      }
    : null

  const usage = (): CvUsage => ({
    basic: { available: true },
    enhanced: {
      available: false,
      reason: 'DISABLED',
      used,
      limit: 3,
      resetsAt: '2026-11-01T05:00:00Z',
      ...options.usage,
    },
  })

  function start(mode: 'BASIC' | 'ENHANCED') {
    polls = 0
    const enhanced = mode === 'ENHANCED'
    if (enhanced) used += 1
    analysis = {
      id: analysis?.id ?? 'a1',
      status: 'PENDING',
      source: enhanced ? 'AI' : 'RULES',
      level: mode,
      reused: false,
      degraded: options.degraded !== undefined,
      degradedReason: options.degraded ?? null,
      createdAt: '2026-10-14T10:00:00Z',
      expiresAt: '2026-10-21T10:00:00Z',
      failureReason: null,
      inputTruncated: false,
      alreadyInProfile: 0,
      suggestions: [],
    }
  }

  function advance() {
    if (!analysis || (analysis.status !== 'PENDING' && analysis.status !== 'ANALYZING')) return
    if (polls < (options.pollsUntilReady ?? 1)) {
      polls += 1
      analysis = { ...analysis, status: 'ANALYZING' as AnalysisStatus }
      return
    }
    analysis = options.failWith
      ? { ...analysis, status: 'FAILED', failureReason: options.failWith }
      : { ...analysis, status: 'READY', suggestions }
  }

  const handler = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(String(input), 'http://localhost')
    if (!url.pathname.startsWith('/api/v1/cv/')) return previousFetch(input, init)

    const path = url.pathname.replace('/api/v1', '')
    const method = (init?.method ?? 'GET').toUpperCase()
    requests.push(`${method} ${path}`)

    if (method === 'GET' && path === '/cv/usage') return json(200, usage())

    if (method === 'GET' && path === '/cv/analyses/current') {
      if (options.readDelayMs) await new Promise((r) => setTimeout(r, options.readDelayMs))
      if (currentFails) return problem(500, 'internal-error')
      if (!analysis) return new Response(null, { status: 204 })
      advance()
      return json(200, analysis)
    }

    if (method === 'POST' && path === '/cv/analyses') {
      const form = init?.body as FormData
      const file = form.get('file') as File
      const mode = String(form.get('mode'))
      uploads.push({ name: file.name, type: file.type, size: file.size, mode })
      if (options.uploadError) {
        return problem(
          options.uploadError.status,
          options.uploadError.code,
          options.uploadError.extra,
        )
      }
      if (analysis && analysis.status !== 'FAILED') return problem(409, 'analysis-in-progress')
      if (file.type !== 'application/pdf') return problem(415, 'cv-invalid-type')
      if (file.size > 2 * 1024 * 1024) return problem(413, 'cv-too-large')
      start(mode === 'ENHANCED' ? 'ENHANCED' : 'BASIC')
      return json(202, { id: analysis?.id, status: 'PENDING', reused: false, degraded: false })
    }

    const body = init?.body ? (JSON.parse(String(init.body)) as Record<string, unknown>) : null
    if (method !== 'GET') bodies.push({ method, path, body })

    const reads = /^\/cv\/analyses\/([^/]+)\/reads$/.exec(path)
    if (method === 'POST' && reads) {
      if (!analysis) return problem(404, 'analysis-not-found')
      if (body?.mode === 'ENHANCED' && used >= 3) {
        return problem(429, 'ai-quota-reached', {
          resetsAt: '2026-11-01T05:00:00Z',
          used,
          limit: 3,
        })
      }
      start(body?.mode === 'ENHANCED' ? 'ENHANCED' : 'BASIC')
      return json(202, { id: analysis?.id, status: 'PENDING', reused: false, degraded: false })
    }

    const confirm = /^\/cv\/analyses\/([^/]+)\/confirm$/.exec(path)
    if (method === 'POST' && confirm) {
      if (!analysis) return problem(404, 'analysis-not-found')
      if (analysis.status !== 'READY') return problem(409, 'analysis-not-ready')
      if (confirmFailsOnce !== undefined) {
        const status = confirmFailsOnce
        confirmFailsOnce = undefined
        return problem(status, 'internal-error')
      }
      const decisions = (body?.decisions ?? []) as Array<{
        suggestionId: string
        action: string
        value?: Record<string, unknown>
      }>
      const errors: Array<{ field: string; rule: string; message: string }> = []
      decisions.forEach((decision, index) => {
        const suggestion = analysis?.suggestions.find((s) => s.id === decision.suggestionId)
        if (suggestion?.kind === 'SKILL' && decision.action !== 'DISCARD') {
          if (![1, 2, 3].includes(Number(decision.value?.level))) {
            errors.push({
              field: `decisions[${index}].value.level`,
              rule: 'required',
              message: 'Elige un nivel.',
            })
          }
        }
      })
      if (errors.length > 0) {
        return json(422, {
          type: 'urn:talenti:problem:validation-failed',
          status: 422,
          code: 'validation-failed',
          errors,
        })
      }
      const profile: Profile = { ...EMPTY_PROFILE, skills: [], projects: [] }
      for (const decision of decisions) {
        if (decision.action === 'DISCARD') continue
        const suggestion = analysis.suggestions.find((s) => s.id === decision.suggestionId)
        if (!suggestion) continue
        if (suggestion.kind === 'SKILL') {
          const slug = String(decision.value?.slug ?? (suggestion.value as SkillValue).slug)
          const skill: ProfileSkill = {
            slug,
            name: TEST_SKILLS.find((s) => s.slug === slug)?.name ?? slug,
            level: Number(decision.value?.level) as ProfileSkill['level'],
            evidence: 'DECLARED',
          }
          profile.skills = [...profile.skills, skill]
        } else if (suggestion.kind === 'ACADEMIC') {
          const value = decision.value?.value ?? (suggestion.value as { value: string }).value
          if (suggestion.field === 'CAREER') {
            profile.academic = { ...profile.academic, career: String(value) }
          } else if (suggestion.field === 'INSTITUTE') {
            profile.academic = { ...profile.academic, institute: String(value) }
          } else {
            profile.academic = { ...profile.academic, cycle: Number(value) }
          }
        } else {
          const project = (decision.value ?? suggestion.value) as ProjectValue
          profile.projects = [
            ...profile.projects,
            { id: `p${profile.projects.length}`, ...project },
          ]
        }
      }
      analysis = null
      return json(200, { ...profile, completeness: completenessOf(profile) })
    }

    const discard = /^\/cv\/analyses\/([^/]+)$/.exec(path)
    if (method === 'DELETE' && discard) {
      if (!analysis) return problem(404, 'analysis-not-found')
      analysis = null
      return new Response(null, { status: 204 })
    }

    return problem(404, 'not-found')
  })
  vi.stubGlobal('fetch', handler)

  return {
    requests,
    bodies,
    uploads,
    current: () => analysis,
    setCurrentFails: (value) => {
      currentFails = value
    },
  }
}
