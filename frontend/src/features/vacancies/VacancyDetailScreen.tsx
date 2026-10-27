import { useEffect, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { DemoNotice } from '../../components/DemoNotice'
import { fetchVacancy, type Requirement, type VacancyDetail } from '../../api/vacancies'
import { ApiError } from '../../api/client'
import { Badge } from '../../components/Badge'
import { Button } from '../../components/Button'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { useSession } from '../../session/useSession'
import { ApplyCard } from '../applications/ApplyCard'
import { VacancyCompatibilityPanel } from '../compatibility/VacancyCompatibilityPanel'
import { CompanyAvatar } from './CompanyAvatar'
import { ErrorNotice } from './ErrorNotice'
import { VacancyDetailSkeleton } from './Skeletons'
import { publishedLabel } from './format'
import './VacancyDetailScreen.css'

/** Only addresses inside /oportunidades are accepted as "back" targets. */
function backTarget(state: unknown): string {
  const from = (state as { from?: unknown } | null)?.from
  return typeof from === 'string' && /^\/oportunidades(\?|$)/.test(from) ? from : '/oportunidades'
}

/** Public page /oportunidades/:id. */
export function VacancyDetailScreen() {
  const { id = '' } = useParams()
  const location = useLocation()
  const vacancy = useApiResource(`vacancy:${id}`, (signal) => fetchVacancy(id, signal))
  const back = backTarget(location.state)

  const loaded = vacancy.state.status === 'success' ? vacancy.state.data : null
  useEffect(() => {
    if (loaded) document.title = `${loaded.title} · ${es.app.name}`
  }, [loaded])

  if (vacancy.state.status === 'loading') return <VacancyDetailSkeleton />

  if (vacancy.state.status === 'error') {
    const notFound = vacancy.state.error instanceof ApiError && vacancy.state.error.status === 404
    return (
      <div className="container vacancy-detail vacancy-detail--message">
        {notFound ? (
          <div className="vacancy-detail__notfound">
            <h1 className="vacancy-detail__message-title">{es.vacancyDetail.notFoundTitle}</h1>
            <Button to="/oportunidades">{es.vacancyDetail.notFoundLink}</Button>
          </div>
        ) : (
          <>
            <h1 className="visually-hidden">{es.pages.vacancy.title}</h1>
            <ErrorNotice message={es.vacancyDetail.errorTitle} onRetry={vacancy.retry} />
          </>
        )}
      </div>
    )
  }

  return <VacancyDetailContent vacancy={vacancy.state.data} back={back} />
}

function RequirementList({
  items,
  kind,
}: {
  items: Requirement[]
  kind: 'required' | 'desirable'
}) {
  return (
    <ul className="vacancy-detail__requirements">
      {items.map((item) => (
        <li key={item.skill.slug} className="vacancy-detail__requirement">
          <span className="vacancy-detail__skill">{item.skill.name}</span>
          <Badge>{kind === 'required' ? es.badge.required : es.badge.desirable}</Badge>
          <span className="vacancy-detail__level">
            {es.vacancyDetail.minLevel(es.vacancies.levels[item.minLevel] ?? String(item.minLevel))}
          </span>
        </li>
      ))}
    </ul>
  )
}

function VacancyDetailContent({ vacancy, back }: { vacancy: VacancyDetail; back: string }) {
  const session = useSession()
  const location = useLocation()
  const [tab, setTab] = useState(location.hash.startsWith('#requisito-') ? 'fit' : 'description')
  const open = vacancy.status === 'OPEN'
  const { required, desirable } = vacancy.requirements
  const returnTo = encodeURIComponent(`/oportunidades/${vacancy.id}`)
  // While the session is still being checked "Postular" is shown disabled, so a signed-in
  // person never sees the "Inicia sesión" link for a moment.
  const signedIn = session.status === 'authenticated'
  const checkingSession = session.status === 'loading'

  return (
    <div className="container vacancy-detail">
      <Link
        className="vacancy-detail__back"
        to={back}
        state={{ restoreVacancy: vacancy.id, ...location.state }}
      >
        <span aria-hidden="true">←</span>
        {es.vacancyDetail.back}
      </Link>
      <DemoNotice />
      <header className="vacancy-detail__header">
        <div className="vacancy-detail__company">
          <CompanyAvatar name={vacancy.company.name} size="lg" />
          <p>
            <strong>{vacancy.company.name}</strong>
            <span>{vacancy.company.city}</span>
          </p>
        </div>
        <h1 className="vacancy-detail__title">{vacancy.title}</h1>
        <p className="vacancy-detail__summary">{vacancy.summary}</p>
      </header>

      <div className="vacancy-detail__grid">
        <section className="vacancy-detail__apply" aria-label={es.vacancyDetail.apply}>
          {open ? (
            session.isStudent ? (
              <ApplyCard
                vacancyId={vacancy.id}
                vacancyTitle={vacancy.title}
                companyName={vacancy.company.name}
              />
            ) : signedIn ? (
              <p className="vacancy-detail__notice">{es.applications.card.notAllowed}</p>
            ) : checkingSession ? (
              <Button disabled>{es.vacancyDetail.apply}</Button>
            ) : (
              <>
                <Button to={`/ingresar?returnTo=${returnTo}`}>{es.vacancyDetail.apply}</Button>
                <p className="vacancy-detail__notice">{es.vacancyDetail.applyLoginNotice}</p>
              </>
            )
          ) : (
            <>
              <p className="vacancy-detail__closed">{es.vacancyDetail.closedTitle}</p>
              <p className="vacancy-detail__notice">{es.vacancyDetail.closedHint}</p>
              <Button variant="quiet" to="/oportunidades">
                {es.vacancyDetail.seeOthers}
              </Button>
            </>
          )}
        </section>

        <nav className="vacancy-detail__tabs" aria-label={es.redesign.vacancySections}>
          <button
            type="button"
            aria-current={tab === 'description' ? 'page' : undefined}
            onClick={() => setTab('description')}
          >
            {es.redesign.descriptionTab}
          </button>
          <button
            type="button"
            aria-current={tab === 'fit' ? 'page' : undefined}
            onClick={() => setTab('fit')}
          >
            {es.redesign.fitTab}
          </button>
        </nav>
        <div className="vacancy-detail__main" hidden={tab !== 'fit'}>
          {open && session.isStudent && <VacancyCompatibilityPanel vacancyId={vacancy.id} />}
          <section aria-labelledby="detail-requirements">
            <h2 id="detail-requirements">{es.vacancyDetail.requirements}</h2>
            {required.length > 0 && (
              <>
                <h3>{es.vacancyDetail.required}</h3>
                <RequirementList items={required} kind="required" />
              </>
            )}
            {desirable.length > 0 && (
              <>
                <h3>{es.vacancyDetail.desirable}</h3>
                <RequirementList items={desirable} kind="desirable" />
              </>
            )}
          </section>
        </div>
        <div className="vacancy-detail__main" hidden={tab !== 'description'}>
          <section aria-labelledby="detail-about">
            <h2 id="detail-about">{es.vacancyDetail.about}</h2>
            <p className="vacancy-detail__description">{vacancy.description}</p>
          </section>

          <section aria-labelledby="detail-company">
            <h2 id="detail-company">{es.vacancyDetail.company}</h2>
            <p className="vacancy-detail__description">
              <strong>{vacancy.company.name}</strong>. {vacancy.company.shortDescription}
            </p>
          </section>
        </div>

        <div className="vacancy-detail__side" hidden={tab !== 'description'}>
          <section aria-labelledby="detail-conditions" className="vacancy-detail__panel">
            <h2 id="detail-conditions">{es.vacancyDetail.conditions}</h2>
            <dl className="vacancy-detail__conditions">
              <div>
                <dt>{es.vacancyDetail.modality}</dt>
                <dd>{es.vacancies.modality[vacancy.modality]}</dd>
              </div>
              <div>
                <dt>{es.vacancyDetail.location}</dt>
                <dd>{vacancy.location ?? es.vacancyDetail.noLocation}</dd>
              </div>
              <div>
                <dt>{es.vacancyDetail.hours}</dt>
                <dd>{es.vacancies.hoursPerWeek(vacancy.hoursPerWeek)}</dd>
              </div>
              <div>
                <dt>{es.vacancyDetail.published}</dt>
                <dd>{publishedLabel(vacancy.publishedAt)}</dd>
              </div>
            </dl>
          </section>
        </div>
      </div>
    </div>
  )
}
