import { useId, type MouseEvent, type ReactNode } from 'react'
import { Link, type To } from 'react-router'
import './GroupedList.css'

interface GroupedListProps {
  /** Short name of the group, drawn above it (sentence case, never all caps). */
  title?: string
  /** Name for screen readers when there is no visible title. */
  label?: string
  className?: string
  children: ReactNode
}

/**
 * One surface with rows separated by a hairline, like the Settings of a phone (spec 011). It
 * replaces cards inside cards: a screen has one surface, and each row is one thing.
 */
export function GroupedList({ title, label, className, children }: GroupedListProps) {
  const titleId = useId()
  return (
    <div className={className ? `grouped ${className}` : 'grouped'}>
      {title && (
        <h2 id={titleId} className="grouped__title">
          {title}
        </h2>
      )}
      <ul
        className="grouped__list"
        aria-labelledby={title ? titleId : undefined}
        aria-label={label}
      >
        {children}
      </ul>
    </div>
  )
}

interface ListRowProps {
  /** Main text of the row. */
  title: ReactNode
  /** One line under the title, in gray. */
  detail?: ReactNode
  /** Mark before the text (a `StatusMark`). */
  mark?: ReactNode
  /** Value at the right (a number, a state). */
  trailing?: ReactNode
  /** The whole row goes to this page (and shows a chevron). */
  to?: To
  /** The whole row is a button. */
  onClick?: (event: MouseEvent<HTMLButtonElement>) => void
  /** Color of a row that is an action: the brand blue, or red for something that cannot be undone. */
  tone?: 'default' | 'action' | 'danger'
  disabled?: boolean
  className?: string
  /** Anything more under the row (for example, a form that opens in place). */
  children?: ReactNode
}

/** One row of a `GroupedList`: at least 44 px tall, and the whole row is the target. */
export function ListRow({
  title,
  detail,
  mark,
  trailing,
  to,
  onClick,
  tone = 'default',
  disabled,
  className,
  children,
}: ListRowProps) {
  const content = (
    <>
      {mark}
      {/* Keeps the hidden text of the mark apart from the title in the accessible name. */}
      {mark && ' '}
      <span className="list-row__text">
        <span className="list-row__title">{title}</span>
        {detail && <span className="list-row__detail">{detail}</span>}
      </span>
      {trailing && <span className="list-row__trailing">{trailing}</span>}
      {to !== undefined && (
        <svg className="list-row__chevron" viewBox="0 0 20 20" aria-hidden="true" focusable="false">
          <path d="M8 5l5 5-5 5" />
        </svg>
      )}
    </>
  )

  let inner: ReactNode
  if (to !== undefined) {
    inner = (
      <Link className="list-row__inner list-row__inner--target" to={to}>
        {content}
      </Link>
    )
  } else if (onClick) {
    inner = (
      <button
        type="button"
        className="list-row__inner list-row__inner--target"
        disabled={disabled}
        onClick={onClick}
      >
        {content}
      </button>
    )
  } else {
    inner = <div className="list-row__inner">{content}</div>
  }

  return (
    <li className={className ? `list-row ${className}` : 'list-row'} data-tone={tone}>
      {inner}
      {children}
    </li>
  )
}
