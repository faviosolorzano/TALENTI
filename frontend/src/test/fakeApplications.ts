import { vi } from 'vitest'
import type { Application, ApplicationItem } from '../api/applications'
import type { VacancyDetail } from '../api/vacancies'

/**
 * In-memory stand-in for the applications API of spec 008, for component tests. It imitates the
 * SHAPE of the real answers (201 with the whole application, `history`, `summary`, the 409 codes,
 * newest first with pages). The vacancy summary is read from the vacancies fake installed before.
 * The real rules are tested in the backend and in the end-to-end tests. All data is invented.
 */

export interface FakeApplicationsOptions {
  /** Applications that already exist (oldest first). */
  existing?: Array<{
    vacancyId: string
    status?: string
    submittedAt?: string
    withdrawnAt?: string
  }>
  /** `POST /applications` answers this error once (then it works). */
  submitFailsOnce?: { status: number; code: string }
  /** `POST /applications` answers this error always. */
  submitFails?: { status: number; code: string }
  /** `POST .../withdraw` answers this error once. */
  withdrawFailsOnce?: { status: number; code: string }
  /** `GET /applications` answers 500. */
  listFails?: boolean
  /** Waits this many ms before answering `GET /applications` (to look at the skeleton). */
  listDelayMs?: number
  /** Waits this many ms before answering a submit (to look at "Enviando…"). */
  submitDelayMs?: number
}

export interface FakeApplications {
  requests: string[]
  all: () => Application[]
  setListFails: (value: boolean) => void
}

function json(status: number, body: unknown): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

const problem = (status: number, code: string) =>
  json(status, { type: `urn:talenti:problem:${code}`, status, code })

export function mockApplicationsBackend(options: FakeApplicationsOptions = {}): FakeApplications {
  const previousFetch = globalThis.fetch
  const requests: string[] = []
  let listFails = options.listFails ?? false
  let submitFailsOnce = options.submitFailsOnce
  let withdrawFailsOnce = options.withdrawFailsOnce
  let counter = 0
  let clock = Date.parse('2026-10-14T15:00:00Z')
  const tick = () => new Date((clock += 60_000)).toISOString()
  const applications: Application[] = []

  async function vacancyOf(id: string): Promise<VacancyDetail | null> {
    const response = await previousFetch(`/api/v1/vacancies/${id}`)
    return response.ok ? ((await response.json()) as VacancyDetail) : null
  }

  async function create(vacancyId: string, status = 'SUBMITTED', at?: string, wAt?: string) {
    const vacancy = await vacancyOf(vacancyId)
    if (!vacancy) return null
    counter += 1
    const submittedAt = at ?? tick()
    const application: Application = {
      id: `app-${counter}`,
      status,
      submittedAt,
      withdrawnAt: status === 'WITHDRAWN' ? (wAt ?? tick()) : null,
      vacancy: {
        id: vacancy.id,
        title: vacancy.title,
        status: vacancy.status,
        company: {
          id: vacancy.company.id,
          name: vacancy.company.name,
          city: vacancy.company.city,
        },
      },
      history: [{ status: 'SUBMITTED', at: submittedAt }],
    }
    if (status === 'WITHDRAWN') {
      application.history.push({ status: 'WITHDRAWN', at: application.withdrawnAt as string })
    }
    applications.push(application)
    return application
  }

  const ready = (async () => {
    for (const item of options.existing ?? []) {
      await create(item.vacancyId, item.status, item.submittedAt, item.withdrawnAt)
    }
  })()

  const item = (application: Application): ApplicationItem => {
    const { history: _history, ...rest } = application
    void _history
    return rest
  }
  const newestFirst = () => [...applications].reverse()

  const handler = vi.fn(async (input: RequestInfo | URL, init?: RequestInit): Promise<Response> => {
    const url = new URL(String(input), 'http://localhost')
    if (!url.pathname.startsWith('/api/v1/applications')) return previousFetch(input, init)
    await ready

    const path = url.pathname.replace('/api/v1', '')
    const method = (init?.method ?? 'GET').toUpperCase()
    requests.push(`${method} ${path}`)

    if (method === 'GET' && path === '/applications/summary') {
      const active = applications.filter(
        (a) => a.status !== 'WITHDRAWN' && a.status !== 'EXPIRED',
      ).length
      return json(200, {
        active,
        withdrawn: applications.filter((a) => a.status === 'WITHDRAWN').length,
        latest: applications.length > 0 ? item(newestFirst()[0] as Application) : null,
      })
    }

    if (method === 'GET' && path === '/applications') {
      if (options.listDelayMs) await new Promise((r) => setTimeout(r, options.listDelayMs))
      if (listFails) return problem(500, 'internal-error')
      const vacancyId = url.searchParams.get('vacancyId')
      const size = Number(url.searchParams.get('size') ?? 10)
      const page = Number(url.searchParams.get('page') ?? 1)
      const matching = newestFirst().filter((a) => !vacancyId || a.vacancy.id === vacancyId)
      return json(200, {
        items: matching.slice((page - 1) * size, page * size).map(item),
        page,
        size,
        totalItems: matching.length,
        totalPages: Math.max(1, Math.ceil(matching.length / size)),
      })
    }

    if (method === 'POST' && path === '/applications') {
      if (options.submitDelayMs) await new Promise((r) => setTimeout(r, options.submitDelayMs))
      if (options.submitFails) {
        return problem(options.submitFails.status, options.submitFails.code)
      }
      if (submitFailsOnce) {
        const failure = submitFailsOnce
        submitFailsOnce = undefined
        return problem(failure.status, failure.code)
      }
      const { vacancyId } = JSON.parse(String(init?.body)) as { vacancyId: string }
      const vacancy = await vacancyOf(vacancyId)
      if (!vacancy) return problem(404, 'vacancy-not-found')
      if (vacancy.status === 'CLOSED') return problem(409, 'vacancy-closed')
      if (applications.some((a) => a.vacancy.id === vacancyId && a.status === 'SUBMITTED')) {
        return problem(409, 'application-already-exists')
      }
      return json(201, await create(vacancyId))
    }

    const withdraw = /^\/applications\/([^/]+)\/withdraw$/.exec(path)
    if (method === 'POST' && withdraw) {
      const found = applications.find((a) => a.id === withdraw[1])
      if (!found) return problem(404, 'application-not-found')
      if (withdrawFailsOnce) {
        const failure = withdrawFailsOnce
        withdrawFailsOnce = undefined
        return problem(failure.status, failure.code)
      }
      if (found.status !== 'SUBMITTED') return problem(409, 'application-not-withdrawable')
      const at = tick()
      found.status = 'WITHDRAWN'
      found.withdrawnAt = at
      found.history.push({ status: 'WITHDRAWN', at })
      return json(200, found)
    }

    const one = /^\/applications\/([^/]+)$/.exec(path)
    if (method === 'GET' && one) {
      const found = applications.find((a) => a.id === one[1])
      return found ? json(200, found) : problem(404, 'application-not-found')
    }

    return problem(404, 'not-found')
  })
  vi.stubGlobal('fetch', handler)

  return { requests, all: () => applications, setListFails: (value) => (listFails = value) }
}
