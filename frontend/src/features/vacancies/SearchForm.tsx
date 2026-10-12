import { useId, useState, type FormEvent } from 'react'
import { Button } from '../../components/Button'
import { es } from '../../i18n/es'
import { cleanText, MAX_QUERY_LENGTH } from './searchParams'
import './SearchForm.css'

interface SearchFormProps {
  /** Values currently in the address. */
  q: string
  location: string
  /** Cities with open vacancies (from the filters). */
  locations: ReadonlyArray<{ value: string; count: number }>
  onSearch: (q: string, location: string) => void
}

/**
 * "¿Qué sabes hacer?" (text or technology), place and "Buscar". It is sent with Enter or the
 * button. What the person types stays in this field and in the address they choose to share;
 * it is not saved anywhere else.
 */
export function SearchForm({ q, location, locations, onSearch }: SearchFormProps) {
  const queryId = useId()
  const locationId = useId()
  const [draftQuery, setDraftQuery] = useState(q)
  const [draftLocation, setDraftLocation] = useState(location)
  const [seenQuery, setSeenQuery] = useState(q)
  const [seenLocation, setSeenLocation] = useState(location)

  // The address changed (back, forward, "Limpiar filtros", a link): the fields follow it.
  if (seenQuery !== q) {
    setSeenQuery(q)
    setDraftQuery(q)
  }
  if (seenLocation !== location) {
    setSeenLocation(location)
    setDraftLocation(location)
  }

  const options = locations.some((option) => option.value === draftLocation)
    ? locations
    : draftLocation
      ? [{ value: draftLocation, count: 0 }, ...locations]
      : locations

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    onSearch(cleanText(draftQuery), draftLocation)
  }

  return (
    <form
      className="search-form"
      role="search"
      aria-label={es.opportunities.searchLabel}
      onSubmit={submit}
    >
      <div className="search-form__field search-form__field--query">
        <label htmlFor={queryId}>{es.opportunities.queryLabel}</label>
        <input
          id={queryId}
          type="text"
          value={draftQuery}
          maxLength={MAX_QUERY_LENGTH}
          placeholder={es.opportunities.queryPlaceholder}
          autoComplete="off"
          autoCapitalize="off"
          spellCheck={false}
          enterKeyHint="search"
          onChange={(event) => setDraftQuery(event.target.value)}
        />
      </div>

      <div className="search-form__field">
        <label htmlFor={locationId}>{es.opportunities.locationLabel}</label>
        <select
          id={locationId}
          value={draftLocation}
          onChange={(event) => setDraftLocation(event.target.value)}
        >
          <option value="">{es.opportunities.locationAll}</option>
          {options.map((option) => (
            <option key={option.value} value={option.value}>
              {option.count > 0
                ? es.opportunities.filters.option(option.value, option.count)
                : option.value}
            </option>
          ))}
        </select>
      </div>

      <Button type="submit" className="search-form__submit">
        {es.opportunities.submit}
      </Button>
    </form>
  )
}
