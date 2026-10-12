import type { CSSProperties } from 'react'

/**
 * Builds a `style` object made only of CSS custom properties. Dynamic values reach the CSS
 * this way (set through the DOM, which the CSP allows) instead of inline style attributes
 * written in HTML.
 */
export function cssVars(vars: Record<`--${string}`, string | number>): CSSProperties {
  return vars as CSSProperties
}
