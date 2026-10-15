import { apiFetch } from './client'

/**
 * Applications API (spec 008, section 6). Only students. Every write answers with the whole
 * application; the interface never decides a state, it only shows what the server says.
 */

/** The server may send any of the E3 states; the screens show unknown ones as "En proceso". */
export type ApplicationStatus = 'SUBMITTED' | 'WITHDRAWN' | (string & {})

export interface ApplicationItem {
  id: string
  status: ApplicationStatus
  submittedAt: string
  withdrawnAt: string | null
  vacancy: {
    id: string
    title: string
    status: 'OPEN' | 'CLOSED'
    company: { id: string; name: string; city: string }
  }
}

export interface Application extends ApplicationItem {
  history: Array<{ status: ApplicationStatus; at: string }>
}

export interface ApplicationPage {
  items: ApplicationItem[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface ApplicationSummary {
  active: number
  withdrawn: number
  latest: ApplicationItem | null
}

/** Active = it is not withdrawn or expired (008-09). */
export const isActiveStatus = (status: ApplicationStatus) =>
  status !== 'WITHDRAWN' && status !== 'EXPIRED'

export const submitApplication = (vacancyId: string) =>
  apiFetch<Application>('/applications', { method: 'POST', json: { vacancyId } })

export const fetchApplications = (
  { page = 1, size = 10, vacancyId }: { page?: number; size?: number; vacancyId?: string },
  signal?: AbortSignal,
) => {
  const params = new URLSearchParams({ page: String(page), size: String(size) })
  if (vacancyId) params.set('vacancyId', vacancyId)
  return apiFetch<ApplicationPage>(`/applications?${params.toString()}`, { signal })
}

export const fetchApplicationSummary = (signal?: AbortSignal) =>
  apiFetch<ApplicationSummary>('/applications/summary', { signal })

export const fetchApplication = (id: string, signal?: AbortSignal) =>
  apiFetch<Application>(`/applications/${encodeURIComponent(id)}`, { signal })

export const withdrawApplication = (id: string) =>
  apiFetch<Application>(`/applications/${encodeURIComponent(id)}/withdraw`, { method: 'POST' })
