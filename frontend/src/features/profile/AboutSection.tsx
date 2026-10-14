import { useEffect, useState } from 'react'
import { saveAbout, type Profile } from '../../api/profile'
import { TextAreaField } from '../../components/TextAreaField'
import { TextField } from '../../components/TextField'
import { es } from '../../i18n/es'
import { useSession } from '../../session/useSession'
import { SectionForm } from './SectionForm'
import { PROFILE_LIMITS } from './sections'
import { messageFor, otherMessages, useSubmit } from './useSubmit'
import './AboutSection.css'
import './ReadList.css'

const t = es.profile.about

/** The server returns nine digits; the screen groups them for reading ("987 654 321"). */
function formatPhone(phone: string): string {
  return /^\d{9}$/.test(phone) ? phone.replace(/(\d{3})(\d{3})(\d{3})/, '$1 $2 $3') : phone
}

/** Read mode: personal data and the professional presentation, or an invitation. */
export function AboutSummary({ profile }: { profile: Profile }) {
  const { firstNames, lastNames, contactEmail, phone, about } = profile.about
  if (!firstNames && !lastNames && !contactEmail && !phone && !about) {
    return <p className="read-empty">{t.empty}</p>
  }
  const missing = <span className="read-list__missing">{t.notSet}</span>
  return (
    <div className="about-read">
      <dl className="read-list">
        <div className="read-list__row">
          <dt>{t.firstNames}</dt>
          <dd>{firstNames ?? missing}</dd>
        </div>
        <div className="read-list__row">
          <dt>{t.lastNames}</dt>
          <dd>{lastNames ?? missing}</dd>
        </div>
        <div className="read-list__row">
          <dt>{t.contactEmail}</dt>
          <dd>{contactEmail ?? missing}</dd>
        </div>
        <div className="read-list__row">
          <dt>{t.phone}</dt>
          <dd>{phone ? formatPhone(phone) : missing}</dd>
        </div>
      </dl>
      <dl className="read-list">
        <div className="read-list__row">
          <dt>{t.about}</dt>
          <dd className="about-read__text">{about ?? missing}</dd>
        </div>
      </dl>
    </div>
  )
}

interface FormProps {
  profile: Profile
  onSaved: (profile: Profile) => void
  onCancel: () => void
  onDirtyChange: (dirty: boolean) => void
}

const FIELDS = ['firstNames', 'lastNames', 'contactEmail', 'phone', 'about'] as const

/**
 * Edit mode of "Datos personales" and "Sobre ti" (one section, one save: the server replaces it
 * whole). Every field is optional; the SERVER decides what is valid and the page shows its
 * answer under each field. The contact e-mail starts as the account's e-mail, only in the form:
 * nothing is sent until the person saves.
 */
export function AboutForm({ profile, onSaved, onCancel, onDirtyChange }: FormProps) {
  const { user } = useSession()
  const [initial] = useState(() => ({
    firstNames: profile.about.firstNames ?? '',
    lastNames: profile.about.lastNames ?? '',
    contactEmail: profile.about.contactEmail ?? user?.email ?? '',
    phone: profile.about.phone ?? '',
    about: profile.about.about ?? '',
  }))
  const [values, setValues] = useState(initial)
  const { state, run } = useSubmit(onSaved)

  const set = (field: keyof typeof initial) => (value: string) =>
    setValues((current) => ({ ...current, [field]: value }))

  const dirty = FIELDS.some((field) => values[field] !== initial[field])
  useEffect(() => {
    onDirtyChange(dirty)
    return () => onDirtyChange(false)
  }, [dirty, onDirtyChange])

  return (
    <SectionForm
      state={state}
      otherProblems={otherMessages(state, FIELDS)}
      onCancel={onCancel}
      onSubmit={() => void run(() => saveAbout(values))}
    >
      <p className="about-form__lead">{t.lead}</p>
      <div className="about-form__grid">
        <TextField
          label={t.firstNames}
          hint={t.firstNamesHint}
          name="firstNames"
          autoComplete="given-name"
          maxLength={PROFILE_LIMITS.personNameMaxLength}
          value={values.firstNames}
          onChange={(event) => set('firstNames')(event.target.value)}
          error={messageFor(state, 'firstNames')}
        />
        <TextField
          label={t.lastNames}
          hint={t.lastNamesHint}
          name="lastNames"
          autoComplete="family-name"
          maxLength={PROFILE_LIMITS.personNameMaxLength}
          value={values.lastNames}
          onChange={(event) => set('lastNames')(event.target.value)}
          error={messageFor(state, 'lastNames')}
        />
        <TextField
          label={t.contactEmail}
          hint={t.contactEmailHint}
          name="contactEmail"
          type="email"
          autoComplete="email"
          maxLength={PROFILE_LIMITS.contactEmailMaxLength}
          value={values.contactEmail}
          onChange={(event) => set('contactEmail')(event.target.value)}
          error={messageFor(state, 'contactEmail')}
        />
        <TextField
          label={t.phone}
          hint={t.phoneHint}
          name="phone"
          type="tel"
          inputMode="tel"
          autoComplete="tel-national"
          maxLength={PROFILE_LIMITS.phoneMaxLength}
          value={values.phone}
          onChange={(event) => set('phone')(event.target.value)}
          error={messageFor(state, 'phone')}
        />
      </div>
      <TextAreaField
        label={t.about}
        hint={t.aboutHint}
        counter={t.counter(values.about.length, PROFILE_LIMITS.aboutMaxLength)}
        name="about"
        maxLength={PROFILE_LIMITS.aboutMaxLength}
        value={values.about}
        onChange={(event) => set('about')(event.target.value)}
        error={messageFor(state, 'about')}
      />
    </SectionForm>
  )
}
