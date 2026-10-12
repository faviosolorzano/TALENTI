import type { ReactNode } from 'react'
import './Badge.css'

interface BadgeProps {
  children: ReactNode
}

/** Small label next to a requirement ("obligatorio", "deseable"). */
export function Badge({ children }: BadgeProps) {
  return <span className="badge">{children}</span>
}
