import { act, fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, expect, it, vi } from 'vitest'
import type { DoraExplanation } from '../../api/compatibility'
import { es } from '../../i18n/es'
import { VACANCY_IDS } from '../../test/fakeBackend'
import { sampleDetail } from '../../test/fakeCompatibility'
import { clearCsrfCookie } from '../../test/fakeIdentity'
import { openStudentApp } from '../../test/studentApp'

const id = VACANCY_IDS[0]!
const path = `/compatibility/vacancies/${id}/explanation`
const t = es.doraExplains
afterEach(clearCsrfCookie)
function answer(overrides: Partial<DoraExplanation> = {}): DoraExplanation {
  return {
    vacancyId: id,
    percent: 72,
    level: 'MEDIUM',
    source: 'AI',
    fallbackReason: null,
    cached: false,
    sections: { fit: 'Ya manejas Java.', gaps: 'Mejora SQL.', unknown: null },
    practice: [
      {
        skill: { slug: 'sql', name: 'SQL' },
        requirementKind: 'REQUIRED',
        outcome: 'PARTIAL',
        actions: ['Haz una consulta SQL.'],
      },
    ],
    versions: {
      formula: '1',
      prompt: '1',
      template: '1',
      model: 'simulated',
      adapter: 'simulated',
    },
    generatedAt: '2026-10-29T20:00:00Z',
    ...overrides,
  }
}
async function open(response = answer(), available = true) {
  const app = openStudentApp(`/oportunidades/${id}`, {
    compatibility: {
      details: { [id]: sampleDetail(id, { doraExplanation: { available } }) },
      explanations: { [id]: response },
    },
  })
  fireEvent.click(await screen.findByRole('button', { name: es.redesign.fitTab }))
  const button = await screen.findByRole('button', {
    name: available ? t.trigger : t.templateTrigger,
  })
  return { ...app, button }
}
it('015-20,22,24,27: one explicit request, real score refreshed, AI label, fold and restore focus without requesting again', async () => {
  const { user, button, compatibility } = await open()
  expect(button).toHaveAttribute('aria-expanded', 'false')
  expect(compatibility.requests.filter((p) => p === path)).toHaveLength(0)
  await user.click(button)
  const region = await screen.findByRole('region', { name: t.title })
  expect(await within(region).findByText(es.ai.label)).toBeVisible()
  expect(screen.getByText('72 %')).toBeVisible()
  expect(screen.getByText(es.compatibility.verdict.MEDIUM)).toBeVisible()
  expect(within(region).queryByRole('heading', { name: t.unknown })).toBeNull()
  expect(within(region).getByRole('link', { name: t.practiceSkill('SQL') })).toHaveAttribute(
    'href',
    `/practicar?vacante=${id}&tema=sql`,
  )
  await user.click(within(region).getByRole('button', { name: t.hide }))
  expect(button).toHaveFocus()
  expect(button).toHaveAttribute('aria-expanded', 'false')
  await user.click(button)
  expect(
    within(screen.getByRole('region', { name: t.title })).getByText('Haz una consulta SQL.'),
  ).toBeVisible()
  expect(compatibility.requests.filter((p) => p === path)).toHaveLength(1)
})
it('015-23: a limited template is never labeled AI and no gaps offers practice for the vacancy', async () => {
  const { user, button } = await open(
    answer({ source: 'TEMPLATE', fallbackReason: 'PERSONAL_LIMIT', practice: [] }),
    false,
  )
  await user.click(button)
  const region = await screen.findByRole('region', { name: t.title })
  expect(await within(region).findByText(t.personalLimit)).toBeVisible()
  expect(within(region).getByText(t.template)).toBeVisible()
  expect(within(region).queryByText(es.ai.label)).toBeNull()
  expect(within(region).getByRole('link', { name: t.practiceVacancy })).toHaveAttribute(
    'href',
    `/practicar?vacante=${id}`,
  )
})
it('015-21,25: loading cannot issue a duplicate, failure remains local and retry succeeds', async () => {
  const { user, button, compatibility } = await open()
  compatibility.setFailing({ [path]: 503 })
  await user.click(button)
  expect(await screen.findByRole('alert')).toHaveTextContent(t.error)
  expect(screen.getByText('81 %')).toBeVisible()
  compatibility.setFailing({})
  await user.click(screen.getByRole('button', { name: t.retry }))
  expect(await screen.findByText('Haz una consulta SQL.')).toBeVisible()
})
it('015-21: repeated clicks while waiting keep a single POST and closing does not lose the pending answer', async () => {
  const { button } = await open()
  const fallback = globalThis.fetch
  let release: (r: Response) => void = () => {}
  let calls = 0
  vi.stubGlobal('fetch', (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input).endsWith(path)) {
      calls++
      return new Promise<Response>((resolve) => {
        release = resolve
      })
    }
    return fallback(input, init)
  })
  fireEvent.click(button)
  await screen.findByText(t.loading)
  fireEvent.click(button)
  fireEvent.click(button)
  expect(calls).toBe(1)
  await act(async () =>
    release(
      new Response(JSON.stringify(answer()), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      }),
    ),
  )
  await waitFor(() => expect(screen.getByText('Haz una consulta SQL.')).toBeVisible())
})
it('015-26: insufficient profile never offers the explanation', async () => {
  const app = openStudentApp(`/oportunidades/${id}`, { compatibility: { sufficient: false } })
  fireEvent.click(await screen.findByRole('button', { name: es.redesign.fitTab }))
  await screen.findByText(es.compatibility.insufficient.title)
  expect(screen.queryByRole('button', { name: t.trigger })).toBeNull()
  expect(screen.queryByRole('button', { name: t.templateTrigger })).toBeNull()
  expect(app.compatibility.requests.filter((p) => p === path)).toHaveLength(0)
})
