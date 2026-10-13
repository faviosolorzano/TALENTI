import { useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react'
import { fetchSkillCatalog, type CatalogSkill } from '../../api/profile'
import { Button } from '../../components/Button'
import { TextField } from '../../components/TextField'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import '../vacancies/Skeletons.css'
import { searchSkills } from './skillSearch'
import './SkillPicker.css'

const t = es.profile.skills

interface SkillPickerProps {
  /** Slugs already in the person's list: they are not offered again. */
  added: ReadonlySet<string>
  onPick: (skill: CatalogSkill) => void
  onClose: () => void
}

/**
 * Search over the skill catalog (`GET /skills`, 006-16). It finds by name or synonym without
 * caring for case or accents ("js" finds JavaScript). There is no free text: only catalog skills
 * can be added (RN-03). Enter adds the first result; Esc closes the search.
 */
export function SkillPicker({ added, onPick, onClose }: SkillPickerProps) {
  const catalog = useApiResource('skill-catalog', (signal) => fetchSkillCatalog(signal))
  const [query, setQuery] = useState('')
  const inputRef = useRef<HTMLInputElement>(null)
  const ready = catalog.state.status === 'success'

  const matches = useMemo(
    () =>
      catalog.state.status === 'success' ? searchSkills(catalog.state.data, query, added) : [],
    [catalog.state, query, added],
  )

  // The search is opened to type: the cursor is already in it. Again when the catalog arrives.
  useEffect(() => {
    inputRef.current?.focus({ preventScroll: true })
  }, [ready])

  function pick(skill: CatalogSkill) {
    onPick(skill)
    setQuery('')
    inputRef.current?.focus({ preventScroll: true })
  }

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.stopPropagation()
      onClose()
    }
  }

  function onInputKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key !== 'Enter') return
    // Enter must not send the section form: it adds the first result.
    event.preventDefault()
    const first = matches[0]
    if (first) pick(first.skill)
  }

  return (
    <div className="skill-picker" onKeyDown={onKeyDown}>
      {catalog.state.status === 'loading' && (
        <div className="skill-picker__loading" aria-busy="true">
          <span className="visually-hidden" role="status">
            {t.catalogLoading}
          </span>
          <span className="skeleton skeleton--heading skill-picker__skeleton-field" aria-hidden />
          <span className="skeleton skeleton--line" aria-hidden />
          <span className="skeleton skeleton--line skeleton--short" aria-hidden />
        </div>
      )}

      {catalog.state.status === 'error' && (
        <ErrorNotice message={t.catalogError} onRetry={catalog.retry} />
      )}

      {catalog.state.status === 'success' && (
        <>
          <TextField
            ref={inputRef}
            label={t.pickerLabel}
            hint={t.pickerHint}
            type="search"
            name="skill-search"
            autoComplete="off"
            autoCapitalize="off"
            spellCheck={false}
            placeholder={t.pickerPlaceholder}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            onKeyDown={onInputKeyDown}
          />

          {matches.length > 0 ? (
            <ul className="skill-picker__list" aria-label={t.resultsLabel}>
              {matches.map(({ skill, alias }) => (
                <li key={skill.slug}>
                  <button type="button" className="skill-option" onClick={() => pick(skill)}>
                    <span className="skill-option__name">{skill.name}</span>
                    {alias && <span className="skill-option__alias">{t.matchedAlias(alias)}</span>}
                    <span className="skill-option__add" aria-hidden="true">
                      +
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          ) : (
            <p className="skill-picker__none" role="status">
              {query.trim() === '' ? t.allAdded : t.none}
            </p>
          )}
        </>
      )}

      <div>
        <Button variant="quiet" size="sm" onClick={onClose}>
          {t.pickerClose}
        </Button>
      </div>
    </div>
  )
}
