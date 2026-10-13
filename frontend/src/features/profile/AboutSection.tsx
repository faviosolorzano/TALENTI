import { useEffect, useState } from 'react'
import { saveAbout, type Profile } from '../../api/profile'
import { TextAreaField } from '../../components/TextAreaField'
import { TextField } from '../../components/TextField'
import { es } from '../../i18n/es'
import { SectionForm } from './SectionForm'
import { PROFILE_LIMITS } from './sections'
import { messageFor, otherMessages, useSubmit } from './useSubmit'
import './ReadList.css'

const t = es.profile.about

/** Read mode: the name and the presentation, or an invitation. */
export function AboutSummary({ profile }: { profile: Profile }) {
  const { displayName, about } = profile.about
  if (!displayName && !about) return <p className="read-empty">{t.empty}</p>
  const missing = <span className="read-list__missing">{t.notSet}</span>
  return (
    <dl className="read-list">
      <div className="read-list__row">
        <dt>{t.displayName}</dt>
        <dd>{displayName ?? missing}</dd>
      </div>
      <div className="read-list__row">
        <dt>{t.about}</dt>
        <dd>{about ?? missing}</dd>
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

const FIELDS = ['displayName', 'about'] as const

/** Edit mode of "Sobre ti": both fields are optional; the server checks the lengths. */
export function AboutForm({ profile, onSaved, onCancel, onDirtyChange }: FormProps) {
  const initial = { name: profile.about.displayName ?? '', about: profile.about.about ?? '' }
  const [name, setName] = useState(initial.name)
  const [about, setAbout] = useState(initial.about)
  const { state, run } = useSubmit(onSaved)

  const dirty = name !== initial.name || about !== initial.about
  useEffect(() => {
    onDirtyChange(dirty)
    return () => onDirtyChange(false)
  }, [dirty, onDirtyChange])

  return (
    <SectionForm
      state={state}
      otherProblems={otherMessages(state, FIELDS)}
      onCancel={onCancel}
      onSubmit={() => void run(() => saveAbout({ displayName: name, about }))}
    >
      <TextField
        label={t.displayName}
        hint={t.displayNameHint}
        name="displayName"
        autoComplete="off"
        maxLength={PROFILE_LIMITS.displayNameMaxLength}
        value={name}
        onChange={(event) => setName(event.target.value)}
        error={messageFor(state, 'displayName')}
      />
      <TextAreaField
        label={t.about}
        hint={t.aboutHint}
        counter={t.counter(about.length, PROFILE_LIMITS.aboutMaxLength)}
        name="about"
        maxLength={PROFILE_LIMITS.aboutMaxLength}
        value={about}
        onChange={(event) => setAbout(event.target.value)}
        error={messageFor(state, 'about')}
      />
    </SectionForm>
  )
}
