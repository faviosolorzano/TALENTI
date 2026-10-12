import { useId, useRef, useState, type KeyboardEvent, type ReactNode } from 'react'
import './Steps.css'

export interface StepItem {
  title: string
  description: string
  /** Visual panel shown while the step is selected. */
  panel: ReactNode
}

interface StepsProps {
  /** Accessible name of the group of steps. */
  label: string
  items: readonly StepItem[]
}

/**
 * Steps as tabs: a `tablist` with one `tab` per step and a `tabpanel` for each. Only the
 * selected tab is in the tab order; the arrows (vertical or horizontal, wrapping around)
 * change the step and move focus. Panels that are not selected are hidden from screen
 * readers and from the keyboard (`aria-hidden` and `inert`).
 */
export function Steps({ label, items }: StepsProps) {
  const baseId = useId()
  const [selected, setSelected] = useState(0)
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([])

  function select(index: number, moveFocus: boolean) {
    const count = items.length
    const next = ((index % count) + count) % count
    setSelected(next)
    if (moveFocus) tabRefs.current[next]?.focus()
  }

  function onKeyDown(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    switch (event.key) {
      case 'ArrowDown':
      case 'ArrowRight':
        event.preventDefault()
        select(index + 1, true)
        break
      case 'ArrowUp':
      case 'ArrowLeft':
        event.preventDefault()
        select(index - 1, true)
        break
      case 'Home':
        event.preventDefault()
        select(0, true)
        break
      case 'End':
        event.preventDefault()
        select(items.length - 1, true)
        break
    }
  }

  return (
    <div className="steps">
      <div className="steps__tabs" role="tablist" aria-label={label} aria-orientation="vertical">
        {items.map((item, index) => {
          const isSelected = index === selected
          return (
            <button
              key={item.title}
              ref={(element) => {
                tabRefs.current[index] = element
              }}
              type="button"
              role="tab"
              id={`${baseId}-tab-${index}`}
              className="steps__tab"
              aria-selected={isSelected}
              aria-controls={`${baseId}-panel-${index}`}
              aria-labelledby={`${baseId}-title-${index}`}
              aria-describedby={`${baseId}-text-${index}`}
              tabIndex={isSelected ? 0 : -1}
              onClick={() => select(index, false)}
              onKeyDown={(event) => onKeyDown(event, index)}
            >
              <span className="steps__number" aria-hidden="true">
                {String(index + 1).padStart(2, '0')}
              </span>
              <span className="steps__title" id={`${baseId}-title-${index}`}>
                {item.title}
              </span>
              <span className="steps__text" id={`${baseId}-text-${index}`}>
                <span>{item.description}</span>
              </span>
            </button>
          )
        })}
      </div>

      <div className="steps__stage">
        {items.map((item, index) => {
          const isSelected = index === selected
          return (
            <div
              key={item.title}
              role="tabpanel"
              id={`${baseId}-panel-${index}`}
              className="steps__pane"
              aria-labelledby={`${baseId}-tab-${index}`}
              aria-hidden={!isSelected}
              inert={!isSelected}
              data-active={isSelected || undefined}
            >
              {item.panel}
            </div>
          )
        })}
      </div>
    </div>
  )
}
