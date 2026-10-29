import { screen, within, waitFor } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import { openStudentApp } from '../../test/studentApp'
import { clearCsrfCookie } from '../../test/fakeIdentity'
import { es } from '../../i18n/es'
const t = es.workflows
const json = (value: unknown, status = 200) =>
  new Response(JSON.stringify(value), { status, headers: { 'Content-Type': 'application/json' } })
function open(
  options: {
    unverified?: boolean
    suggestions?: boolean
    pendingSelection?: Promise<Response>
    unmatched?: boolean
    remove?: boolean
    failDecision?: boolean
  } = {},
) {
  const app = openStudentApp('/perfil/github')
  const fallback = globalThis.fetch
  const requests: { path: string; method: string; body: unknown }[] = []
  let linked = true
  let selected: string[] = []
  let failed = false
  const repos = Array.from({ length: 7 }, (_, i) => ({
    id: 'repo-' + i,
    name: 'Proyecto ' + i,
    url: 'https://github.com/estudiante/proyecto-' + i,
    description: 'Proyecto inventado de prueba',
    primaryLanguage: 'Java',
    languages: [{ name: 'Java', percent: 100 }],
    topics: [],
    archived: false,
    pushedAt: '2026-10-28T10:00:00Z',
    selected: false,
  }))
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    const path = String(input).split('/api/v1')[1] ?? ''
    if (!path.startsWith('/github')) return fallback(input, init)
    requests.push({
      path,
      method: init?.method ?? 'GET',
      body: init?.body ? JSON.parse(String(init.body)) : null,
    })
    if (path === '/github' && init?.method === 'DELETE') {
      linked = false
      return Promise.resolve(new Response(null, { status: 204 }))
    }
    if (path === '/github')
      return Promise.resolve(
        linked
          ? json({
              status: options.unverified ? 'UNVERIFIED' : 'VERIFIED',
              login: 'estudiante-prueba',
              githubUserId: 123,
              verifiedAt: '2026-10-28T10:00:00Z',
            })
          : new Response(null, { status: 204 }),
      )
    if (path === '/github/repositories')
      return Promise.resolve(
        json({
          items: repos.map((r) => ({ ...r, selected: selected.includes(r.id) })),
          fetchedAt: '2026-10-28T10:00:00Z',
          stale: false,
        }),
      )
    if (path === '/github/suggestions')
      return Promise.resolve(
        json({
          suggestions: options.suggestions
            ? [
                {
                  slug: 'java',
                  name: 'Java',
                  evidence: [{ repo: 'Proyecto 0', source: 'LANGUAGE', share: 100 }],
                  currentLevel: null,
                  currentEvidence: null,
                },
              ]
            : [],
          unmatched: options.unmatched
            ? [{ name: 'Rust<ejemplo>', evidence: [{ repo: 'Proyecto 0', source: 'TOPIC' }] }]
            : [],
          source: 'RULES',
        }),
      )
    if (path === '/github/repositories/selection') {
      selected = JSON.parse(String(init?.body)).repoIds
      return options.pendingSelection ?? Promise.resolve(json({ items: repos }))
    }
    if (path === '/github/refresh')
      return Promise.resolve(
        json({
          items: repos,
          removed: options.remove ? [{ id: 'gone', name: 'Repositorio retirado' }] : [],
          fetchedAt: '2026-10-28T10:00:00Z',
        }),
      )
    if (path === '/github/suggestions/decisions') {
      if (options.failDecision && !failed) {
        failed = true
        return Promise.resolve(json({ code: 'internal-error' }, 500))
      }
      return Promise.resolve(json({}))
    }
    return Promise.resolve(json({ code: 'test-unknown' }, 404))
  })
  return { ...app, requests }
}
afterEach(clearCsrfCookie)
it('an unverified link is not presented as a connected account and requests no repositories', async () => {
  const { requests } = open({ unverified: true })
  expect(await screen.findByRole('button', { name: t.connect })).toBeInTheDocument()
  expect(screen.queryByText(t.linked, { exact: false })).not.toBeInTheDocument()
  expect(requests.some((r) => r.path === '/github/repositories')).toBe(false)
})
it('returning repository choices to their saved state clears the unsaved warning', async () => {
  const { user } = open()
  const first = await screen.findByRole('checkbox', { name: 'Proyecto 0' })
  await user.click(first)
  await user.click(first)
  expect(screen.queryByText(t.unsaved)).toBeNull()
  expect(screen.getByRole('button', { name: t.refresh })).toBeEnabled()
})
it('limits the selection to six and locks mutation controls while it is being saved', async () => {
  let release: ((r: Response) => void) | undefined
  const pending = new Promise<Response>((r) => {
    release = r
  })
  const { user, requests } = open({ pendingSelection: pending })
  await screen.findByRole('checkbox', { name: 'Proyecto 0' })
  for (let i = 0; i < 6; i++)
    await user.click(screen.getByRole('checkbox', { name: 'Proyecto ' + i }))
  expect(screen.getByRole('checkbox', { name: 'Proyecto 6' })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: t.saveRepos }))
  try {
    expect(screen.getByRole('checkbox', { name: 'Proyecto 0' })).toBeDisabled()
    expect(screen.getByRole('button', { name: t.disconnect })).toBeDisabled()
  } finally {
    release!(json({ items: [] }))
  }
  await waitFor(() => expect(requests.filter((r) => r.path.endsWith('/selection'))).toHaveLength(1))
  expect(requests.find((r) => r.path.endsWith('/selection'))?.body).toEqual({
    repoIds: ['repo-0', 'repo-1', 'repo-2', 'repo-3', 'repo-4', 'repo-5'],
  })
})
it('does not let stale suggestions be changed while the repository selection is unsaved', async () => {
  const { user } = open({ suggestions: true })
  await user.click(await screen.findByRole('checkbox', { name: 'Proyecto 0' }))
  const row = screen.getByRole('heading', { name: 'Java' }).closest('div')!
  expect(within(row).getByRole('combobox')).toBeDisabled()
})
it('requires an explicit level and preserves the decisions when the service fails', async () => {
  const { user, requests } = open({ suggestions: true, failDecision: true })
  const row = (await screen.findByRole('heading', { name: 'Java' })).closest('div')!
  await user.selectOptions(within(row).getByRole('combobox'), 'ACCEPT')
  expect(screen.getByRole('button', { name: t.applySuggestions })).toBeDisabled()
  await user.selectOptions(within(row).getAllByRole('combobox')[1]!, '2')
  await user.click(screen.getByRole('button', { name: t.applySuggestions }))
  await screen.findByRole('alert')
  expect(within(row).getAllByRole('combobox')[1]).toHaveValue('2')
  await user.click(screen.getByRole('button', { name: t.applySuggestions }))
  await waitFor(() => expect(requests.filter((r) => r.path.endsWith('/decisions'))).toHaveLength(2))
  expect(requests.find((r) => r.path.endsWith('/decisions'))?.body).toEqual({
    items: [{ slug: 'java', decision: 'ACCEPT', level: 2 }],
  })
})
it('shows technologies without a catalog match as evidence, without an accept action or HTML injection', async () => {
  open({ unmatched: true })
  expect(await screen.findByText('Rust<ejemplo>')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: t.applySuggestions })).toBeNull()
  expect(document.querySelector('ejemplo')).toBeNull()
})
it('explains which selected repository the service removed during refresh', async () => {
  const { user } = open({ remove: true })
  await user.click(await screen.findByRole('button', { name: t.refresh }))
  expect(await screen.findByText('Repositorio retirado', { exact: false })).toBeInTheDocument()
})
it('requires confirmation to unlink and uses only the server result as success', async () => {
  const { user, requests } = open()
  await user.click(await screen.findByRole('button', { name: t.disconnect }))
  await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: t.cancel }))
  expect(requests.some((r) => r.method === 'DELETE')).toBe(false)
  await user.click(screen.getByRole('button', { name: t.disconnect }))
  await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: t.disconnect }))
  expect(await screen.findByRole('button', { name: t.connect })).toBeInTheDocument()
  expect(requests.filter((r) => r.method === 'DELETE')).toHaveLength(1)
})
