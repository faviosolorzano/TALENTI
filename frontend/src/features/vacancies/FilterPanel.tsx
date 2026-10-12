import { useEffect, useId, useRef, useState } from 'react'
import { MODALITIES, type FilterOptions, type Modality } from '../../api/vacancies'
import { Button } from '../../components/Button'
import { es } from '../../i18n/es'
import type { ResourceState } from '../../hooks/useApiResource'
import { MAX_FILTER_VALUES } from './searchParams'
import './FilterPanel.css'

/** Technologies listed before "Ver las N tecnologías". Chosen ones are always listed. */
const SKILLS_COLLAPSED = 8

interface FilterPanelProps {
  filters: ResourceState<FilterOptions>
  onRetryFilters: () => void
  modality: Modality[]
  skill: string[]
  onToggleModality: (value: Modality) => void
  onToggleSkill: (slug: string) => void
  onClear: () => void
}

/**
 * Side filters (modality and technology, with counts). From 980 px they are a column; below
 * that they fold into a panel opened by "Filtros (n)" (`aria-expanded` and `aria-controls`).
 * Esc closes the panel and gives the focus back to the button. Filters apply as soon as they
 * are checked.
 */
export function FilterPanel({
  filters,
  onRetryFilters,
  modality,
  skill,
  onToggleModality,
  onToggleSkill,
  onClear,
}: FilterPanelProps) {
  const panelId = useId()
  const [open, setOpen] = useState(false)
  const [showAllSkills, setShowAllSkills] = useState(false)
  const toggleRef = useRef<HTMLButtonElement>(null)
  const activeCount = modality.length + skill.length

  useEffect(() => {
    if (!open) return
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return
      setOpen(false)
      toggleRef.current?.focus()
    }
    document.addEventListener('keydown', onKeyDown)
    return () => document.removeEventListener('keydown', onKeyDown)
  }, [open])

  const options = filters.status === 'success' ? filters.data : null

  const skillOptions = options
    ? [...options.skills].sort(
        (a, b) => b.count - a.count || a.name.localeCompare(b.name, 'es', { sensitivity: 'base' }),
      )
    : []
  const visibleSkills = showAllSkills
    ? skillOptions
    : skillOptions.filter(
        (option, index) => index < SKILLS_COLLAPSED || skill.includes(option.slug),
      )
  const canExpand = skillOptions.length > visibleSkills.length || showAllSkills

  return (
    <div className="filters" data-open={open}>
      <button
        ref={toggleRef}
        type="button"
        className="filters__toggle"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={() => setOpen((value) => !value)}
      >
        {es.opportunities.filters.toggle(activeCount)}
      </button>

      <div
        id={panelId}
        className="filters__panel"
        role="group"
        aria-label={es.opportunities.filters.panelLabel}
      >
        <div className="filters__head">
          <h2 className="filters__title">{es.opportunities.filters.title}</h2>
          {activeCount > 0 && (
            <Button variant="quiet" size="sm" onClick={onClear}>
              {es.opportunities.filters.clear}
            </Button>
          )}
        </div>

        {filters.status === 'loading' && (
          <p className="filters__note" aria-busy="true">
            {es.vacancies.loadingLabel}
          </p>
        )}

        {filters.status === 'error' && (
          <div className="filters__error" role="alert">
            <p>{es.opportunities.filters.unavailable}</p>
            <Button variant="quiet" size="sm" onClick={onRetryFilters}>
              {es.common.retry}
            </Button>
          </div>
        )}

        {options && (
          <>
            <fieldset className="filters__group">
              <legend className="filters__legend">{es.opportunities.filters.modality}</legend>
              {MODALITIES.map((value) => {
                const option = options.modalities.find((item) => item.value === value)
                const checked = modality.includes(value)
                // A modality without open vacancies stays out, unless it is already chosen.
                if (!option && !checked) return null
                return (
                  <label key={value} className="filters__option">
                    <input
                      type="checkbox"
                      checked={checked}
                      onChange={() => onToggleModality(value)}
                    />
                    <span>
                      {es.opportunities.filters.option(
                        es.vacancies.modality[value],
                        option?.count ?? 0,
                      )}
                    </span>
                  </label>
                )
              })}
            </fieldset>

            {skillOptions.length > 0 && (
              <fieldset className="filters__group">
                <legend className="filters__legend">{es.opportunities.filters.skills}</legend>
                {visibleSkills.map((option) => (
                  <label key={option.slug} className="filters__option">
                    <input
                      type="checkbox"
                      checked={skill.includes(option.slug)}
                      disabled={!skill.includes(option.slug) && skill.length >= MAX_FILTER_VALUES}
                      onChange={() => onToggleSkill(option.slug)}
                    />
                    <span>{es.opportunities.filters.option(option.name, option.count)}</span>
                  </label>
                ))}
                {canExpand && (
                  <button
                    type="button"
                    className="filters__more"
                    aria-expanded={showAllSkills}
                    onClick={() => setShowAllSkills((value) => !value)}
                  >
                    {showAllSkills
                      ? es.opportunities.filters.showFewerSkills
                      : es.opportunities.filters.showAllSkills(skillOptions.length)}
                  </button>
                )}
              </fieldset>
            )}
          </>
        )}
      </div>
    </div>
  )
}
