import { fireEvent, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { es } from '../../i18n/es'
import { VACANCY_IDS } from '../../test/fakeBackend'
import { insufficientDetail, sampleDetail } from '../../test/fakeCompatibility'
import { clearCsrfCookie } from '../../test/fakeIdentity'
import { openStudentApp } from '../../test/studentApp'

afterEach(clearCsrfCookie)

const t = es.compatibility
const FIRST = VACANCY_IDS[0] as string
const detailPath = `/oportunidades/${FIRST}`
const panel = async () => {
  const tab = await screen.findByRole('button', { name: es.redesign.fitTab })
  fireEvent.click(tab)
  return screen.findByRole('region', { name: t.title })
}

describe('Panel "Tu compatibilidad" in the detail (007-18 to 007-21)', () => {
  it('007-18: shows what the server sent: ring, verdict, no "Basado en" at 100 %', async () => {
    openStudentApp(detailPath)
    const region = await panel()
    expect(await within(region).findByText('81 %')).toBeInTheDocument()
    expect(within(region).getByText(t.verdict.HIGH)).toBeInTheDocument()
    expect(within(region).queryByText(/Basado en/)).not.toBeInTheDocument()
    expect(within(region).queryByText(t.capped(69))).not.toBeInTheDocument()
  })

  it('007-18: the interface never recalculates: an odd number from the server is shown as it came', async () => {
    openStudentApp(detailPath, {
      compatibility: { details: { [FIRST]: sampleDetail(FIRST, { percent: 37, level: 'HIGH' }) } },
    })
    const region = await panel()
    expect(await within(region).findByText('37 %')).toBeInTheDocument()
    expect(within(region).getByText(t.verdict.HIGH)).toBeInTheDocument()
  })

  it('007-18: with a missing required requirement it warns about the cap and says how much information was used', async () => {
    openStudentApp(detailPath, {
      compatibility: {
        details: {
          [FIRST]: sampleDetail(FIRST, {
            percent: 69,
            level: 'MEDIUM',
            capped: true,
            informationBasis: 59,
          }),
        },
      },
    })
    const region = await panel()
    expect(await within(region).findByText(t.capped(69))).toBeInTheDocument()
    expect(within(region).getByText(t.basis(59))).toBeInTheDocument()
  })

  it('007-19: compares evidence in rows with contextual links and never editable requested levels', async () => {
    openStudentApp(detailPath)
    const region = await panel()
    expect(
      await within(region).findByText('pide nivel intermedio · tú: básico · declarado'),
    ).toBeInTheDocument()
    const sql = within(region).getByText('SQL', { selector: 'strong' }).closest('li')!
    expect(within(sql).getByText(es.redesign.ownLevel)).toBeInTheDocument()
    expect(within(sql).getByText(es.redesign.requiredLevel)).toBeInTheDocument()
    expect(within(sql).getByRole('link')).toHaveAttribute(
      'href',
      expect.stringContaining('/perfil?seccion=habilidades&returnTo='),
    )
    expect(within(sql).queryByRole('radio')).not.toBeInTheDocument()
  })
  it('007-19: matches remain read-only without a correction action', async () => {
    openStudentApp(detailPath, {
      compatibility: { details: { [FIRST]: sampleDetail(FIRST, { gaps: [], missingInfo: [] }) } },
    })
    const region = await panel()
    await within(region).findByText('81 %')
    expect(within(region).queryByRole('link')).not.toBeInTheDocument()
  })

  it('007-20: the template explanation says "calculada con reglas" and has NO AI label', async () => {
    openStudentApp(detailPath)
    const region = await panel()
    expect(await within(region).findByText(t.explanationLabel)).toBeInTheDocument()
    expect(within(region).queryByText(es.ai.label)).not.toBeInTheDocument()
  })

  it('007-20: an explanation written by AI (a later slice) does carry the AI label', async () => {
    openStudentApp(detailPath, {
      compatibility: {
        details: {
          [FIRST]: sampleDetail(FIRST, {
            explanation: { source: 'AI', version: '1', text: 'Texto de prueba.' },
          }),
        },
      },
    })
    const region = await panel()
    expect(await within(region).findByText(es.ai.label)).toBeInTheDocument()
    expect(within(region).queryByText(t.explanationLabel)).not.toBeInTheDocument()
  })

  it('007-20: "Cómo se calcula" is a keyboard-operable disclosure that reads the weights from the response', async () => {
    const { user } = openStudentApp(detailPath, {
      compatibility: {
        details: {
          [FIRST]: sampleDetail(FIRST, {
            formula: {
              version: '7',
              weights: { required: 40, desirable: 20, conditions: 25, similarity: 15 },
              cap: 66,
              minEvaluableWeight: 45,
              similarityEnabled: false,
            },
          }),
        },
      },
    })
    const region = await panel()
    const summary = await within(region).findByText(t.how.summary)
    expect(summary.tagName).toBe('SUMMARY')
    await user.click(summary)
    expect(within(region).getByText(t.how.intro)).toBeVisible()
    expect(
      within(region).getByRole('row', {
        name: new RegExp(`${t.how.required}.*${t.how.points(40)}`),
      }),
    ).toBeInTheDocument()
    expect(within(region).getByText(t.how.cap(66))).toBeInTheDocument()
    expect(within(region).getByText(t.how.minimum(45))).toBeInTheDocument()
    expect(within(region).getByText(t.how.similarityOff)).toBeInTheDocument()
  })

  it('007-21: NO_SKILLS shows "Información insuficiente", an empty ring, no percentage and the way to the skills', async () => {
    openStudentApp(detailPath, { compatibility: { sufficient: false } })
    const region = await panel()
    expect(await within(region).findByText(t.insufficient.title)).toBeInTheDocument()
    expect(within(region).getByText(t.insufficient.NO_SKILLS)).toBeInTheDocument()
    expect(within(region).getByText(t.insufficient.title)).toBeInTheDocument()
    expect(within(region).queryByText(/\d+ %/)).not.toBeInTheDocument()
    expect(within(region).getByRole('link', { name: t.insufficient.action })).toHaveAttribute(
      'href',
      expect.stringContaining('/perfil?seccion=habilidades&returnTo='),
    )
  })

  it('007-21: LOW_INFORMATION leads to the first section with missing data and lists it', async () => {
    openStudentApp(detailPath, {
      compatibility: {
        details: {
          [FIRST]: {
            ...insufficientDetail(FIRST, 'LOW_INFORMATION'),
            missingInfo: [
              {
                type: 'CONDITION',
                factor: 'MODALITY',
                outcome: 'UNKNOWN',
                vacancyValue: 'REMOTE',
                profileValue: null,
              },
            ],
          },
        },
      },
    })
    const region = await panel()
    expect(await within(region).findByText(t.insufficient.LOW_INFORMATION)).toBeInTheDocument()
    expect(within(region).getByRole('link', { name: t.insufficient.action })).toHaveAttribute(
      'href',
      expect.stringContaining('/perfil?seccion=disponibilidad&returnTo='),
    )
    expect(
      within(region).getByText(t.condition.factor.MODALITY, { selector: 'strong' }),
    ).toBeInTheDocument()
  })

  it('007-21: a vacancy without requirements only informs, with no action', async () => {
    openStudentApp(detailPath, {
      compatibility: {
        details: { [FIRST]: insufficientDetail(FIRST, 'VACANCY_WITHOUT_REQUIREMENTS') },
      },
    })
    const region = await panel()
    expect(
      await within(region).findByText(t.insufficient.VACANCY_WITHOUT_REQUIREMENTS),
    ).toBeInTheDocument()
    expect(within(region).queryByRole('link')).not.toBeInTheDocument()
  })

  it('007-21: a visitor sees no panel, no percentage and the API is not called', async () => {
    const { compatibility } = openStudentApp(detailPath, { signedOut: true })
    await screen.findByRole('heading', { level: 1, name: 'Practicante Backend Java' })
    expect(screen.getByText(es.vacancyDetail.applyLoginNotice)).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: t.title })).not.toBeInTheDocument()
    expect(compatibility.requests).toEqual([])
  })

  it('007-21: a closed vacancy and a company account do not get the card', async () => {
    const closed = openStudentApp(detailPath, { vacancies: { closed: [FIRST] } })
    await screen.findByText(es.vacancyDetail.closedTitle)
    expect(screen.queryByRole('region', { name: t.title })).not.toBeInTheDocument()
    expect(closed.compatibility.requests).toEqual([])
  })

  it('007-21: a company account does not call the API', async () => {
    const company = openStudentApp(detailPath, { role: 'COMPANY' })
    await screen.findByRole('heading', { level: 1, name: 'Practicante Backend Java' })
    expect(screen.queryByRole('region', { name: t.title })).not.toBeInTheDocument()
    expect(company.compatibility.requests).toEqual([])
  })

  it('007-25: while loading it is a busy skeleton that does not block the vacancy', async () => {
    openStudentApp(detailPath, { compatibility: { delayMs: 60 } })
    const region = await panel()
    expect(region).toHaveAttribute('aria-busy', 'true')
    expect(
      screen.getByRole('heading', { level: 1, name: 'Practicante Backend Java' }),
    ).toBeInTheDocument()
    expect(screen.getByText(/Descripción de Practicante Backend Java/)).toBeInTheDocument()
    await waitFor(() => expect(region).toHaveAttribute('aria-busy', 'false'))
  })

  it('007-25: an error is an alert only in the panel, the vacancy stays readable, and "Reintentar" repeats only that call', async () => {
    const { compatibility, user } = openStudentApp(detailPath, {
      compatibility: { failing: { [`/compatibility/vacancies/${FIRST}`]: 500 } },
    })
    const region = await panel()
    const alert = await within(region).findByRole('alert')
    expect(alert).toHaveTextContent(t.error)
    expect(screen.getAllByRole('alert')).toHaveLength(1)
    expect(screen.getByText(/Descripción de Practicante Backend Java/)).toBeInTheDocument()

    compatibility.setFailing({})
    await user.click(within(alert).getByRole('button', { name: es.common.retry }))
    expect(await within(region).findByText('81 %')).toBeInTheDocument()
  })
})
