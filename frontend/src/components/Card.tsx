import type { HTMLAttributes, ReactNode, Ref } from 'react'
import './Card.css'

interface CardProps extends HTMLAttributes<HTMLElement> {
  /** Element to draw. Use `article` for a self-contained piece of content. */
  as?: 'div' | 'article' | 'section'
  ref?: Ref<HTMLElement>
  children: ReactNode
}

/** White surface with border, radius and shadow. It only reads tokens. */
export function Card({ as: Element = 'div', className, children, ref, ...rest }: CardProps) {
  return (
    <Element
      {...rest}
      ref={ref as Ref<HTMLDivElement>}
      className={className ? `card ${className}` : 'card'}
    >
      {children}
    </Element>
  )
}
