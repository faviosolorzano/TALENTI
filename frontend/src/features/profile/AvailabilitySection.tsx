import { useEffect, useId, useState } from 'react'
import { saveAvailability, type Profile } from '../../api/profile'
import { fetchFilters, MODALITIES, type Modality } from '../../api/vacancies'
import { ChoiceChips } from '../../components/ChoiceChips'
import { TextField } from '../../components/TextField'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { SectionForm } from './SectionForm'
import { typedNumber } from './typedNumber'
import { PROFILE_LIMITS } from './sections'
import { messageFor, otherMessages, useSubmit } from './useSubmit'
import './ReadList.css'

const t = es.profile.availability

const MODALITY_OPTIONS = MODALITIES.map((value) => ({
  value,
  label: es.vacancies.modality[value],
}))

/** Read mode: hours, modalities and city, or an invitation. */
export function AvailabilitySummary({ profile }: { profile: Profile }) {
  const { hoursPerWeek, acceptedModalities, location } = profile.availability
  if (hoursPerWeek === null && acceptedModalities.length === 0 && !location) {
    return <p className="read-empty">{t.empty}</p>
  }
  const missing = <span className="read-list__missing">{t.notSet}</span>
  return (
    <dl className="read-list">
      <div className="read-list__row">
        <dt>{t.hours}</dt>
        <dd>{hoursPerWeek === null ? missing : t.hoursValue(hoursPerWeek)}</dd>
      </div>
      <div className="read-list__row">
        <dt>{t.modalities}</dt>
        <dd>
          {acceptedModalities.length === 0
            ? missing
            : acceptedModalities.map((modality) => es.vacancies.modality[modality]).join(', ')}
        </dd>
      </div>
      <div className="read-list__row">
        <dt>{t.location}</dt>
        <dd>{location ?? missing}</dd>
      </div>
    </dl>
  )
}

interface FormProps {
  profile: Profile
  onSaved: (profile: Profile) => void
  onCancel: () => void
  onDirtyChange: (dirty: boolean) => void
}

const FIELDS = ['hoursPerWeek', 'acceptedModalities', 'location'] as const

/** Edit mode of "Disponibilidad y preferencias" (006-07, 006-17). */
export function AvailabilityForm({ profile, onSaved, onCancel, onDirtyChange }: FormProps) {
  const initial = {
    hours:
      profile.availability.hoursPerWeek === null ? '' : String(profile.availability.hoursPerWeek),
    modalities: profile.availability.acceptedModalities,
    location: profile.availability.location ?? '',
  }
  const [hours, setHours] = useState(initial.hours)
  const [modalities, setModalities] = useState<Modality[]>(initial.modalities)
  const [location, setLocation] = useState(initial.location)
  const { state, run } = useSubmit(onSaved)
  const listId = useId()

  // Suggestions for the city: the cities of the open vacancies. If this fails, nothing is blocked.
  const filters = useApiResource('vacancy-filters', (signal) => fetchFilters(signal))
  const suggestions = filters.state.status === 'success' ? filters.state.data.locations : []

  const dirty =
    hours !== initial.hours ||
    location !== initial.location ||
    modalities.join() !== initial.modalities.join()
  useEffect(() => {
    onDirtyChange(dirty)
    return () => onDirtyChange(false)
  }, [dirty, onDirtyChange])

  return (
    <SectionForm
      state={state}
      otherProblems={otherMessages(state, FIELDS)}
      onCancel={onCancel}
      onSubmit={() =>
        void run(() =>
          saveAvailability({
            hoursPerWeek: typedNumber(hours),
            acceptedModalities: modalities,
            location,
          }),
        )
      }
    >
      <div className="section-form__row">
        <TextField
          label={t.hours}
          hint={t.hoursHint}
          name="hoursPerWeek"
          inputMode="numeric"
          autoComplete="off"
          maxLength={3}
          value={hours}
          onChange={(event) => setHours(event.target.value)}
          error={messageFor(state, 'hoursPerWeek')}
        />
        <TextField
          label={t.location}
          hint={t.locationHint}
          name="location"
          autoComplete="off"
          maxLength={PROFILE_LIMITS.locationMaxLength}
          list={suggestions.length > 0 ? listId : undefined}
          value={location}
          onChange={(event) => setLocation(event.target.value)}
          error={messageFor(state, 'location')}
        />
      </div>
      <ChoiceChips
        legend={t.modalities}
        hint={t.modalitiesHint}
        options={MODALITY_OPTIONS}
        value={modalities}
        onChange={(next) => setModalities(next as Modality[])}
        error={messageFor(state, 'acceptedModalities')}
      />
      {suggestions.length > 0 && (
        <datalist id={listId}>
          {suggestions.map((item) => (
            <option key={item.value} value={item.value} />
          ))}
        </datalist>
      )}
    </SectionForm>
  )
}
