import { useMemo, useRef, useState } from 'react'
import { AiLabel } from '../../components/AiLabel'
import { Badge } from '../../components/Badge'
import { Card } from '../../components/Card'
import { CompatibilityRing } from '../../components/CompatibilityRing'
import type { ChipLevel } from '../../components/chipLevel'
import { LevelChip } from '../../components/LevelChip'
import { useInView } from '../../hooks/useInView'
import { es } from '../../i18n/es'
import { COMPATIBILITY_CONFIG } from '../compatibility/config'
import { computeCompatibility } from '../compatibility/computeCompatibility'
import {
  DEMO_CONDITIONS,
  DEMO_COSINE,
  DEMO_INITIAL_LEVELS,
  DEMO_REQUIREMENTS,
  type DemoSkillId,
} from '../compatibility/demoVacancy'
import { buildExplanation, buildGroups, verdictFor, verdictSubFor, type SkillRow } from './demoCard'
import './CompatibilityCard.css'

type Levels = Record<DemoSkillId, ChipLevel>

const GROUP_TONES = { match: 'ok', reinforce: 'mid', missing: 'no' } as const

function SkillGroup({
  title,
  tone,
  rows,
}: {
  title: string
  tone: 'ok' | 'mid' | 'no'
  rows: readonly SkillRow[]
}) {
  if (rows.length === 0) return null
  return (
    <div className="compat__group">
      <h4 className="compat__group-title">{title}</h4>
      <ul className="compat__rows">
        {rows.map((row) => (
          <li key={row.id} className="compat__row" data-tone={tone}>
            <span className="compat__mark" aria-hidden="true" />
            {row.name}
            <Badge>{row.kind === 'required' ? es.badge.required : es.badge.desirable}</Badge>
            <em className="compat__evidence">{row.evidence}</em>
          </li>
        ))}
      </ul>
    </div>
  )
}

/**
 * The compatibility card of the landing page, with EXAMPLE data. The person taps the skills
 * and the ring, verdict, groups and DORA's explanation are recalculated without reloading.
 * The calculation is a demonstration (`computeCompatibility`); the real one is the backend's.
 */
export function CompatibilityCard() {
  const cardRef = useRef<HTMLElement>(null)
  const onScreen = useInView(cardRef, { threshold: 0.35, once: true })
  const [levels, setLevels] = useState<Levels>(DEMO_INITIAL_LEVELS as Levels)

  const result = useMemo(
    () =>
      computeCompatibility({
        requirements: DEMO_REQUIREMENTS,
        levels,
        conditions: DEMO_CONDITIONS,
        cosine: DEMO_COSINE,
      }),
    [levels],
  )
  const groups = useMemo(() => buildGroups(result, DEMO_REQUIREMENTS), [result])
  const verdict = verdictFor(result)
  const { weights, cap } = COMPATIBILITY_CONFIG

  return (
    <Card
      as="article"
      id="compatibilidad-card"
      className="compat"
      tabIndex={-1}
      aria-labelledby="compatibilidad-title"
      ref={cardRef}
    >
      <div className="compat__head">
        <div className="compat__avatar" aria-hidden="true">
          {es.card.avatar}
        </div>
        <div>
          <h2 className="compat__title" id="compatibilidad-title">
            {es.card.title}
          </h2>
          <p className="compat__subtitle">{es.card.subtitle}</p>
        </div>
      </div>

      <div className="compat__score">
        <CompatibilityRing value={result.percent} capped={result.capped} active={onScreen} />
        <div className="compat__verdict">
          <h3>{es.card.verdict[verdict]}</h3>
          <p>{verdictSubFor(result)}</p>
        </div>
      </div>

      <div className="compat__chips-title">
        <strong>{es.card.chipsTitle}</strong>
        <span>{es.card.chipsHint}</span>
      </div>
      <div className="compat__chips" role="group" aria-label={es.card.chipsGroupLabel}>
        {DEMO_REQUIREMENTS.map((req) => {
          const id = req.id as DemoSkillId
          return (
            <LevelChip
              key={id}
              name={es.card.skills[id]}
              kind={req.type}
              level={levels[id]}
              onLevelChange={(next) => setLevels((current) => ({ ...current, [id]: next }))}
            />
          )
        })}
      </div>

      <div className="compat__groups">
        <SkillGroup title={es.card.groups.match} tone={GROUP_TONES.match} rows={groups.match} />
        <SkillGroup
          title={es.card.groups.reinforce}
          tone={GROUP_TONES.reinforce}
          rows={groups.reinforce}
        />
        <SkillGroup
          title={es.card.groups.missing}
          tone={GROUP_TONES.missing}
          rows={groups.missing}
        />
        <div className="compat__group">
          <h4 className="compat__group-title">{es.card.groups.unknown}</h4>
          <ul className="compat__rows">
            <li className="compat__row" data-tone="unknown">
              <span className="compat__mark" aria-hidden="true" />
              {es.card.availability}
              <em className="compat__evidence">{es.card.evidence.missingData}</em>
            </li>
          </ul>
        </div>
      </div>

      <div className="compat__dora" aria-live="polite">
        <AiLabel />
        <p>{buildExplanation(result, groups)}</p>
      </div>

      <details className="compat__how">
        <summary>{es.card.how.summary}</summary>
        <p>{es.card.how.intro}</p>
        <table>
          <caption className="visually-hidden">{es.card.how.weights}</caption>
          <tbody>
            <tr>
              <th scope="row">{es.card.how.required}</th>
              <td>{weights.required}</td>
            </tr>
            <tr>
              <th scope="row">{es.card.how.desirable}</th>
              <td>{weights.desirable}</td>
            </tr>
            <tr>
              <th scope="row">{es.card.how.conditions}</th>
              <td>{weights.conditions}</td>
            </tr>
            <tr>
              <th scope="row">{es.card.how.similarity}</th>
              <td>{weights.similarity}</td>
            </tr>
          </tbody>
        </table>
        <p>{es.card.how.cap(cap)}</p>
      </details>

      <p className="compat__sample">{es.card.sampleNote}</p>
    </Card>
  )
}
