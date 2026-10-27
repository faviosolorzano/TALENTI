import { TechnologyIcon } from '../../components/TechnologyIcon'
import { useEffect, useMemo, useState } from 'react'
import {
  saveSkills,
  type CatalogSkill,
  type Profile,
  type ProfileSkill,
  type SkillLevel,
} from '../../api/profile'
import { Badge } from '../../components/Badge'
import { Button } from '../../components/Button'
import { es } from '../../i18n/es'
import { LevelSelect } from './LevelSelect'
import { SectionForm } from './SectionForm'
import { SkillPicker } from './SkillPicker'
import { messageFor, otherMessages, useSubmit } from './useSubmit'
import './ReadList.css'
import './SkillsSection.css'

const t = es.profile.skills

/** Read mode: the skills as chips with their level, and what "Declarada" means. */
export function SkillsSummary({ profile }: { profile: Profile }) {
  if (profile.skills.length === 0) return <p className="read-empty">{t.empty}</p>
  return (
    <div className="skills-read">
      <ul className="skills-read__list" aria-label={t.listLabel}>
        {profile.skills.map((skill) => (
          <li key={skill.slug} className="skill-chip">
            <span className="skill-chip__name">{skill.name}</span>
            <span className="skill-chip__level">{t.levels[skill.level]}</span>
            <span className="visually-hidden">, {t.declared}</span>
          </li>
        ))}
      </ul>
      <p className="skills-read__note">{t.declaredNote}</p>
    </div>
  )
}

interface FormProps {
  profile: Profile
  onSaved: (profile: Profile) => void
  onCancel: () => void
  onDirtyChange: (dirty: boolean) => void
}

type Row = Pick<ProfileSkill, 'slug' | 'name' | 'level'>

const sameRows = (a: readonly Row[], b: readonly Row[]) =>
  a.length === b.length && a.every((row, i) => row.slug === b[i]?.slug && row.level === b[i]?.level)

/** Edit mode of "Habilidades": levels, remove, and the catalog search (006-06, 006-16). */
export function SkillsForm({ profile, onSaved, onCancel, onDirtyChange }: FormProps) {
  const initial = useMemo<Row[]>(
    () => profile.skills.map(({ slug, name, level }) => ({ slug, name, level })),
    [profile.skills],
  )
  const [rows, setRows] = useState<Row[]>(initial)
  // With nothing in the list the search is already open: it is the one thing to do.
  const [activeSkill, setActiveSkill] = useState<string | null>(null)
  const [picking, setPicking] = useState(initial.length === 0)
  const [announcement, setAnnouncement] = useState('')
  const { state, run } = useSubmit(onSaved)

  const added = useMemo(() => new Set(rows.map((row) => row.slug)), [rows])
  const dirty = !sameRows(rows, initial)
  useEffect(() => {
    onDirtyChange(dirty)
    return () => onDirtyChange(false)
  }, [dirty, onDirtyChange])

  function add(skill: CatalogSkill) {
    setRows((current) => [...current, { slug: skill.slug, name: skill.name, level: 1 }])
    setActiveSkill(skill.slug)
    setAnnouncement(t.added(skill.name))
  }

  function remove(row: Row) {
    setRows((current) => current.filter((item) => item.slug !== row.slug))
    setAnnouncement(t.removed(row.name))
  }

  function setLevel(slug: string, level: SkillLevel) {
    setRows((current) => current.map((row) => (row.slug === slug ? { ...row, level } : row)))
  }

  return (
    <SectionForm
      state={state}
      otherProblems={otherMessages(state, [])}
      onCancel={onCancel}
      onSubmit={() => void run(() => saveSkills(rows.map(({ slug, level }) => ({ slug, level }))))}
    >
      <p className="skills-form__hint">{t.hint}</p>

      {rows.length > 0 ? (
        <ul className="skills-form__list" aria-label={t.listLabel}>
          {rows.map((row, index) => {
            const problem = messageFor(state, `skills[${index}]`)
            return (
              <li key={row.slug} className="skill-row">
                <div className="skill-row__who">
                  <TechnologyIcon slug={row.slug} />
                  <span className="skill-row__name">{row.name}</span>
                  <Badge>{t.declared}</Badge>
                </div>
                <div className="skill-row__editor">
                  <button
                    className="skill-row__toggle"
                    type="button"
                    aria-expanded={activeSkill === row.slug}
                    onClick={() => setActiveSkill(activeSkill === row.slug ? null : row.slug)}
                  >
                    {t.levels[row.level]} <span className="visually-hidden">{row.name}</span>
                  </button>
                  {activeSkill === row.slug && (
                    <>
                      <LevelSelect
                        skillName={row.name}
                        value={row.level}
                        onChange={(level) => setLevel(row.slug, level)}
                      />
                      <p className="field__hint">{es.redesign.levelHelp[row.level]}</p>
                    </>
                  )}
                </div>
                <Button
                  variant="quiet"
                  size="sm"
                  className="skill-row__remove"
                  aria-label={t.removeLabel(row.name)}
                  onClick={() => remove(row)}
                >
                  {t.remove}
                </Button>
                {problem && <p className="skill-row__problem">{problem}</p>}
              </li>
            )
          })}
        </ul>
      ) : (
        !picking && <p className="read-empty">{t.emptyEditing}</p>
      )}

      {picking ? (
        <SkillPicker added={added} onPick={add} onClose={() => setPicking(false)} />
      ) : (
        <div>
          <Button variant="quiet" data-autofocus onClick={() => setPicking(true)}>
            <span aria-hidden="true">+</span> {t.add}
          </Button>
        </div>
      )}

      <p className="visually-hidden" role="status">
        {announcement}
      </p>
    </SectionForm>
  )
}
