import { useEffect, useState } from 'react'
import { saveAcademic, type Profile } from '../../api/profile'
import { TextField } from '../../components/TextField'
import { es } from '../../i18n/es'
import { SectionForm } from './SectionForm'
import { typedNumber } from './typedNumber'
import { messageFor, otherMessages, useSubmit } from './useSubmit'
import './ReadList.css'

const t = es.profile.academic

/** Read mode: what the person already told us, or an invitation (006-20). */
export function AcademicSummary({ profile }: { profile: Profile }) {
  const { career, institute, cycle } = profile.academic
  if (!career && !institute && cycle === null) {
    return <p className="read-empty">{t.empty}</p>
  }
  return (
    <dl className="read-list">
      <div className="read-list__row">
        <dt>{t.career}</dt>
        <dd>{career ?? <span className="read-list__missing">{t.notSet}</span>}</dd>
      </div>
      <div className="read-list__row">
        <dt>{t.institute}</dt>
        <dd>{institute ?? <span className="read-list__missing">{t.notSet}</span>}</dd>
      </div>
      <div className="read-list__row">
        <dt>{t.cycle}</dt>
        <dd>
          {cycle === null ? (
            <span className="read-list__missing">{t.notSet}</span>
          ) : (
            t.cycleValue(cycle)
          )}
        </dd>
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

const FIELDS = ['career', 'institute', 'cycle'] as const

/** Edit mode of "Datos académicos" (006-05, 006-15). */
export function AcademicForm({ profile, onSaved, onCancel, onDirtyChange }: FormProps) {
  const initial = {
    career: profile.academic.career ?? '',
    institute: profile.academic.institute ?? '',
    cycle: profile.academic.cycle === null ? '' : String(profile.academic.cycle),
  }
  const [career, setCareer] = useState(initial.career)
  const [institute, setInstitute] = useState(initial.institute)
  const [cycle, setCycle] = useState(initial.cycle)
  const { state, run } = useSubmit(onSaved)

  const dirty =
    career !== initial.career || institute !== initial.institute || cycle !== initial.cycle
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
        void run(() => saveAcademic({ career, institute, cycle: typedNumber(cycle) }))
      }
    >
      <TextField
        label={t.career}
        hint={t.careerHint}
        name="career"
        autoComplete="off"
        maxLength={80}
        value={career}
        onChange={(event) => setCareer(event.target.value)}
        error={messageFor(state, 'career')}
      />
      <TextField
        label={t.institute}
        hint={t.instituteHint}
        name="institute"
        autoComplete="off"
        maxLength={80}
        value={institute}
        onChange={(event) => setInstitute(event.target.value)}
        error={messageFor(state, 'institute')}
      />
      <TextField
        className="section-form__short"
        label={t.cycle}
        hint={t.cycleHint}
        name="cycle"
        inputMode="numeric"
        autoComplete="off"
        maxLength={3}
        value={cycle}
        onChange={(event) => setCycle(event.target.value)}
        error={messageFor(state, 'cycle')}
      />
    </SectionForm>
  )
}
