import { useId } from 'react'
import { fetchCompatibility, type Compatibility } from '../../api/compatibility'
import { AiLabel } from '../../components/AiLabel'
import { Button } from '../../components/Button'
import { Card } from '../../components/Card'
import { CompatibilityRing } from '../../components/CompatibilityRing'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import '../vacancies/Skeletons.css'
import { sectionPath } from '../profile/sections'
import { HowItIsCalculated } from './HowItIsCalculated'
import { firstMissingSection } from './matchFormat'
import { MatchLists } from './MatchLists'
import { useSessionLost } from './useSessionLost'
import './VacancyCompatibilityPanel.css'

const t = es.compatibility

function Calculated({ result }: { result: Compatibility }) {
  const percent = result.percent
  const basis = result.informationBasis
  const aiWritten = result.explanation.source === 'AI'
  return (
    <>
      <div className="compat-panel__score">
        <CompatibilityRing value={percent} capped={result.capped} />
        <div className="compat-panel__verdict">
          <p className="compat-panel__verdict-title">
            {result.level ? t.verdict[result.level] : ''}
          </p>
          {basis !== null && basis < 100 && <p className="compat-panel__basis">{t.basis(basis)}</p>}
        </div>
      </div>

      {result.capped && <p className="compat-panel__cap">{t.capped(result.formula.cap)}</p>}

      <div className="compat-panel__explanation">
        {aiWritten ? (
          <AiLabel />
        ) : (
          <p className="compat-panel__explanation-label">{t.explanationLabel}</p>
        )}
        <p>{result.explanation.text}</p>
      </div>

      <MatchLists matches={result.matches} gaps={result.gaps} missingInfo={result.missingInfo} />
      <HowItIsCalculated formula={result.formula} />
    </>
  )
}

function Insufficient({ result }: { result: Compatibility }) {
  const reason = result.reasons[0] ?? 'LOW_INFORMATION'
  const action =
    reason === 'VACANCY_WITHOUT_REQUIREMENTS'
      ? null
      : reason === 'NO_SKILLS'
        ? sectionPath('skills')
        : sectionPath(firstMissingSection(result.missingInfo) ?? 'skills')
  return (
    <>
      <div className="compat-panel__score">
        <CompatibilityRing value={null} />
        <div className="compat-panel__verdict">
          <p className="compat-panel__verdict-title">{t.insufficient.title}</p>
          <p className="compat-panel__basis">{t.insufficient[reason]}</p>
          {action && (
            <Button to={action} size="sm">
              {t.insufficient.action}
            </Button>
          )}
        </div>
      </div>
      {reason === 'LOW_INFORMATION' && (
        <MatchLists matches={[]} gaps={[]} missingInfo={result.missingInfo} />
      )}
    </>
  )
}

/**
 * "Tu compatibilidad" in the detail of a vacancy (spec 007-18 to 007-21, 007-25). Only for a
 * student with a session. Everything shown (the percentage, the level, the lists, the text) was
 * calculated by the server; this component never calculates. If the call fails, only this card
 * shows the error: the vacancy stays readable.
 */
export function VacancyCompatibilityPanel({ vacancyId }: { vacancyId: string }) {
  const titleId = useId()
  const sessionLost = useSessionLost()
  const resource = useApiResource(`compatibility:${vacancyId}`, async (signal) => {
    try {
      return await fetchCompatibility(vacancyId, signal)
    } catch (error) {
      sessionLost(error, true)
      throw error
    }
  })
  const { state } = resource

  return (
    <Card
      as="section"
      className="compat-panel"
      aria-labelledby={titleId}
      aria-busy={state.status === 'loading'}
    >
      <h2 id={titleId} className="compat-panel__title">
        {t.title}
      </h2>

      {state.status === 'loading' && (
        <div className="compat-panel__skeleton">
          <p className="visually-hidden" role="status">
            {t.loading}
          </p>
          <div aria-hidden="true" className="compat-panel__skeleton-row">
            <span className="skeleton skeleton--ring" />
            <span className="compat-panel__skeleton-lines">
              <span className="skeleton skeleton--line" />
              <span className="skeleton skeleton--line skeleton--short" />
            </span>
          </div>
        </div>
      )}

      {state.status === 'error' && <ErrorNotice message={t.error} onRetry={resource.retry} />}

      {state.status === 'success' &&
        (state.data.status === 'CALCULATED' ? (
          <Calculated result={state.data} />
        ) : (
          <Insufficient result={state.data} />
        ))}
    </Card>
  )
}
