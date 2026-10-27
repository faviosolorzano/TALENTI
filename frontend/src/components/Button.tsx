import type { AnchorHTMLAttributes, ButtonHTMLAttributes, MouseEvent, ReactNode } from 'react'
import { Link, type To } from 'react-router'
import './Button.css'

type CommonProps = {
  /**
   * `primary` is the single main action of a screen; `quiet` is the outlined, discreet one;
   * `danger` confirms something that cannot be undone.
   */
  variant?: 'primary' | 'quiet' | 'danger'
  size?: 'md' | 'sm'
  /** Colors for a navy background (only meaningful for `quiet`). */
  onDark?: boolean
  disabled?: boolean
  className?: string
  children: ReactNode
}

type AnchorProps = CommonProps &
  Omit<AnchorHTMLAttributes<HTMLAnchorElement>, keyof CommonProps | 'href'> & {
    /** Plain link to a page or an external address. */
    href: string
    to?: never
  }

type RouterLinkProps = CommonProps &
  Omit<AnchorHTMLAttributes<HTMLAnchorElement>, keyof CommonProps | 'href'> & {
    /** Navigation inside the app (no page reload). */
    to: To
    state?: unknown
    href?: never
  }

type ActionProps = CommonProps &
  Omit<ButtonHTMLAttributes<HTMLButtonElement>, keyof CommonProps> & {
    href?: never
    to?: never
  }

export type ButtonProps = AnchorProps | RouterLinkProps | ActionProps

/**
 * A link that looks like a button when it NAVIGATES (`href` or `to`, drawn as `<a>`), or a real
 * `<button>` when it ACTS. At least 44 px tall.
 */
export function Button(props: ButtonProps) {
  const {
    variant = 'primary',
    size = 'md',
    onDark = false,
    disabled = false,
    className,
    children,
    ...rest
  } = props

  const classes = ['btn', `btn--${variant}`, size === 'sm' && 'btn--sm', onDark && 'btn--on-dark']
    .concat(className ?? [])
    .filter(Boolean)
    .join(' ')

  if ('to' in rest && rest.to !== undefined) {
    const { to, onClick, ...anchor } = rest
    return (
      <Link
        {...anchor}
        to={to}
        className={classes}
        aria-disabled={disabled || undefined}
        tabIndex={disabled ? -1 : anchor.tabIndex}
        onClick={(event: MouseEvent<HTMLAnchorElement>) => {
          if (disabled) event.preventDefault()
          else onClick?.(event)
        }}
      >
        {children}
      </Link>
    )
  }

  if ('href' in rest && rest.href !== undefined) {
    const { href, onClick, ...anchor } = rest
    return (
      <a
        {...anchor}
        href={href}
        className={classes}
        aria-disabled={disabled || undefined}
        tabIndex={disabled ? -1 : anchor.tabIndex}
        onClick={(event) => {
          if (disabled) event.preventDefault()
          else onClick?.(event)
        }}
      >
        {children}
      </a>
    )
  }

  const { type = 'button', ...button } = rest as ButtonHTMLAttributes<HTMLButtonElement>
  return (
    <button {...button} type={type} className={classes} disabled={disabled}>
      {children}
    </button>
  )
}
