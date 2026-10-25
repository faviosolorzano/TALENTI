import { useEffect, useRef, type ReactNode } from 'react'
import './ActionBar.css'

/** Custom property that tells the page how much room the bar takes at the bottom. */
const SPACE_VARIABLE = '--action-bar-space'

interface ActionBarProps {
  /** The main action of the screen (one primary button) and, at most, a quiet companion. */
  children: ReactNode
  className?: string
}

/**
 * The place of the main action of a screen (spec 011). Under 860 px it stays fixed at the bottom,
 * above the tab bar, and the page keeps room for it so it never hides content; from 860 px it
 * sits in its place in the page (for example, next to the percentage).
 */
export function ActionBar({ children, className }: ActionBarProps) {
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const bar = ref.current
    const root = document.documentElement
    if (!bar || typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(() => {
      root.style.setProperty(SPACE_VARIABLE, `${bar.offsetHeight}px`)
    })
    observer.observe(bar)
    return () => {
      observer.disconnect()
      root.style.removeProperty(SPACE_VARIABLE)
    }
  }, [])

  return (
    <div ref={ref} className={className ? `action-bar ${className}` : 'action-bar'}>
      {children}
    </div>
  )
}
