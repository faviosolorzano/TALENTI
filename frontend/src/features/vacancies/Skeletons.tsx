import { Card } from '../../components/Card'
import { es } from '../../i18n/es'
import './Skeletons.css'

/** Placeholder for one vacancy card. The animation stops with "reduce motion". */
export function VacancyCardSkeleton() {
  return (
    <Card as="div" className="vacancy-skeleton" aria-hidden="true">
      <div className="vacancy-skeleton__body">
        <span className="skeleton skeleton--title" />
        <span className="skeleton skeleton--line" />
        <span className="skeleton skeleton--line skeleton--short" />
      </div>
    </Card>
  )
}

interface SkeletonListProps {
  count: number
  className?: string
}

/** A group of card skeletons. It tells assistive technology that the area is busy. */
export function VacancyListSkeleton({ count, className }: SkeletonListProps) {
  return (
    <div className={className} aria-busy="true">
      <span className="visually-hidden">{es.vacancies.loadingLabel}</span>
      {Array.from({ length: count }, (_, index) => (
        <VacancyCardSkeleton key={index} />
      ))}
    </div>
  )
}

/** Placeholder for the whole detail page. */
export function VacancyDetailSkeleton() {
  return (
    <div className="container vacancy-detail-skeleton" aria-busy="true">
      <h1 className="visually-hidden">{es.pages.vacancy.title}</h1>
      <span className="visually-hidden">{es.vacancies.loadingLabel}</span>
      <div aria-hidden="true">
        <span className="skeleton skeleton--line skeleton--short" />
        <span className="skeleton skeleton--heading" />
        <span className="skeleton skeleton--line" />
        <span className="skeleton skeleton--block" />
      </div>
    </div>
  )
}
