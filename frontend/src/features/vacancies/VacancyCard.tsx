import { TechnologyIcon } from '../../components/TechnologyIcon'
import { Link } from 'react-router'
import type { CompatibilitySummary } from '../../api/compatibility'
import type { VacancyCardData } from '../../api/vacancies'
import { es } from '../../i18n/es'
import { CompatibilityBadge } from '../compatibility/CompatibilityBadge'
import { CompanyAvatar } from './CompanyAvatar'
import { publishedLabel } from './format'
import './VacancyCard.css'

/** Technologies shown on a card; the rest become "+N". */
export const MAX_CARD_SKILLS = 4

interface VacancyCardProps {
  vacancy: VacancyCardData
  /** Address of the list the card is in, so "Volver a los resultados" can return to it. */
  from?: string
  /** The server's compatibility for this vacancy, only for a student with a sufficient profile. */
  compatibility?: CompatibilitySummary | null
}

/**
 * A vacancy in a list. The title is the only link and covers the whole card, so the card is
 * clickable but a screen reader finds a single link. The compatibility badge appears only when
 * the server sent a result for a student (spec 007-23); a visitor never gets one.
 */
export function VacancyCard({ vacancy, from, compatibility }: VacancyCardProps) {
  const visibleSkills = vacancy.skills.slice(0, MAX_CARD_SKILLS)
  const hiddenCount = vacancy.skills.length - visibleSkills.length

  return (
    <article className="vacancy-card" id={`vacante-${vacancy.id}`} aria-label={vacancy.title}>
      <CompanyAvatar name={vacancy.company.name} />
      <div className="vacancy-card__body">
        <h3 className="vacancy-card__title">
          <Link
            className="vacancy-card__link"
            to={`/oportunidades/${encodeURIComponent(vacancy.id)}`}
            state={from ? { from, restoreVacancy: vacancy.id, scrollY: window.scrollY } : undefined}
          >
            {vacancy.title}
          </Link>
        </h3>
        <p className="vacancy-card__company">
          {es.vacancies.companyAndCity(vacancy.company.name, vacancy.company.city)}
        </p>

        {compatibility && compatibility.status === 'CALCULATED' && (
          <p className="vacancy-card__compat">
            <CompatibilityBadge summary={compatibility} />
          </p>
        )}

        <ul className="vacancy-card__facts">
          <li className="vacancy-card__fact vacancy-card__fact--modality">
            {es.vacancies.modality[vacancy.modality]}
          </li>
          {vacancy.location && <li className="vacancy-card__fact">{vacancy.location}</li>}
          <li className="vacancy-card__fact">{es.vacancies.hoursPerWeek(vacancy.hoursPerWeek)}</li>
          <li className="vacancy-card__fact">{publishedLabel(vacancy.publishedAt)}</li>
        </ul>

        {visibleSkills.length > 0 && (
          <ul className="vacancy-card__skills" aria-label={es.vacancies.skillsLabel}>
            {visibleSkills.map((skill) => (
              <li key={skill.slug} className="vacancy-card__skill">
                <TechnologyIcon slug={skill.slug} />
                {skill.name}
              </li>
            ))}
            {hiddenCount > 0 && (
              <li
                className="vacancy-card__skill vacancy-card__skill--more"
                aria-label={es.vacancies.moreSkillsLabel(hiddenCount)}
              >
                {es.vacancies.moreSkills(hiddenCount)}
              </li>
            )}
          </ul>
        )}
      </div>
    </article>
  )
}
