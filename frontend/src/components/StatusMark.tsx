import type { ReactNode } from 'react'
import { es } from '../i18n/es'
import './StatusMark.css'

/**
 * The five states of the interface (spec 011): ✓ you have it / done, ◐ reinforce, ✗ missing,
 * ● done (a step that happened), ○ pending. Each one has its own shape AND color, and a hidden
 * text for screen readers, so it never depends on color alone (011-05).
 */
export type StatusKind = 'have' | 'reinforce' | 'missing' | 'done' | 'pending'

interface StatusMarkProps {
  kind: StatusKind
  /** Text for screen readers. By default, the one of the state ("Cumples", "Te falta"…). */
  label?: string
  className?: string
}

const SHAPES: Record<StatusKind, ReactNode> = {
  have: <path d="M5 10.5l3.2 3.2L15 7" className="status-mark__stroke" />,
  reinforce: (
    <>
      <circle cx="10" cy="10" r="6.5" className="status-mark__stroke" />
      <path d="M10 3.5a6.5 6.5 0 0 0 0 13z" className="status-mark__fill" />
    </>
  ),
  missing: <path d="M6 6l8 8M14 6l-8 8" className="status-mark__stroke" />,
  done: <circle cx="10" cy="10" r="5.5" className="status-mark__fill" />,
  pending: <circle cx="10" cy="10" r="5.5" className="status-mark__stroke" />,
}

export function StatusMark({ kind, label, className }: StatusMarkProps) {
  return (
    <span className={className ? `status-mark ${className}` : 'status-mark'} data-kind={kind}>
      <svg viewBox="0 0 20 20" aria-hidden="true" focusable="false">
        {SHAPES[kind]}
      </svg>
      {/* The space keeps the hidden text apart from what follows in an accessible name. */}
      <span className="visually-hidden">{`${label ?? es.statusMark[kind]} `}</span>
    </span>
  )
}
