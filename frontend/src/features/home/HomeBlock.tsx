import { useId, type ReactNode } from 'react'
import { Card } from '../../components/Card'
import './HomeBlock.css'

interface HomeBlockProps {
  title: string
  /** Extra class for the position of the block in the grid. */
  className?: string
  busy?: boolean
  children: ReactNode
}

/**
 * One block of the student Home (spec 005-09): a `section` named by its `h2`, with one key
 * piece of data and at most one main action inside.
 */
export function HomeBlock({ title, className, busy, children }: HomeBlockProps) {
  const titleId = useId()
  return (
    <Card
      as="section"
      className={className ? `home-block ${className}` : 'home-block'}
      aria-labelledby={titleId}
      aria-busy={busy || undefined}
    >
      <div className="home-block__head">
        <h2 id={titleId} className="home-block__title">
          {title}
        </h2>
      </div>
      {children}
    </Card>
  )
}
