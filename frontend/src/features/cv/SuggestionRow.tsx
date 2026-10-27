import { useState } from 'react'
import type { Suggestion } from '../../api/cv'
import type { CatalogSkill, SkillLevel } from '../../api/profile'
import { Button } from '../../components/Button'
import { TagField } from '../../components/TagField'
import { TextAreaField } from '../../components/TextAreaField'
import { TextField } from '../../components/TextField'
import { es } from '../../i18n/es'
import { LevelSelect } from '../profile/LevelSelect'
import { PROFILE_LIMITS } from '../profile/sections'
import { SkillPicker } from '../profile/SkillPicker'
import {
  asAcademic,
  asProject,
  asSkill,
  skillSlugOf,
  suggestionName,
  type RowDecision,
} from './decisions'
import './SuggestionRow.css'

const t = es.cv.review
const NO_SLUGS: ReadonlySet<string> = new Set()

interface SuggestionRowProps {
  active?: boolean
  onActivate?: () => void
  suggestion: Suggestion
  decision: RowDecision | undefined
  /** A message of the server about this suggestion (422). */
  serverError?: string
  onChange: (next: RowDecision | undefined) => void
}

/**
 * One suggestion with its three actions (Aceptar, Editar, Descartar), by keyboard too.
 * Everything starts "sin decidir". A skill needs a level chosen by the person; one that is not
 * in the catalog needs to be linked to one that is (RN-03).
 */
export function SuggestionRow({
  suggestion,
  decision,
  serverError,
  onChange,
  active = true,
  onActivate,
}: SuggestionRowProps) {
  const [editing, setEditing] = useState(false)
  const [picking, setPicking] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)

  const isSkill = suggestion.kind === 'SKILL'
  const skill = isSkill ? asSkill(suggestion.value) : null
  const name = suggestionName(suggestion, decision)
  const action = decision?.action
  const level = decision?.level ?? skill?.level ?? null
  const slug = isSkill ? skillSlugOf(suggestion, decision) : null
  const error = localError ?? serverError

  function decide(next: RowDecision['action']) {
    setLocalError(null)
    onChange({ ...decision, action: next })
  }

  /** Accepting a skill needs the level and a catalog skill; otherwise it says what is missing. */
  function accept(extra: Partial<RowDecision> = {}) {
    if (!active) onActivate?.()
    const merged = { ...decision, ...extra }
    if (isSkill) {
      if (!(merged.level ?? skill?.level)) {
        onChange({ ...merged, action: undefined })
        setLocalError(t.levelMissing)
        return
      }
      if (!(merged.slug ?? skill?.slug)) {
        onChange({ ...merged, action: undefined })
        setLocalError(t.slugMissing)
        setPicking(true)
        return
      }
    }
    setLocalError(null)
    onChange({ ...merged, action: merged.action === 'EDIT' ? 'EDIT' : 'ACCEPT' })
  }

  function onLevel(chosen: SkillLevel) {
    setLocalError(null)
    // Choosing the level of an accepted skill keeps it accepted; otherwise it only stores it.
    if (action === 'ACCEPT' || action === 'EDIT') onChange({ ...decision, level: chosen })
    else onChange({ ...decision, level: chosen, action: undefined })
  }

  function onPick(chosen: CatalogSkill) {
    setPicking(false)
    setLocalError(null)
    const changed = chosen.slug !== skill?.slug
    const next = { ...decision, slug: chosen.slug, skillName: chosen.name }
    if (next.level ?? skill?.level) {
      onChange({ ...next, action: changed ? 'EDIT' : 'ACCEPT' })
    } else {
      onChange({ ...next, action: undefined })
      setLocalError(t.levelMissing)
    }
  }

  const status = action ? t.state[action] : t.state.none

  return (
    <li className="suggestion" data-decision={action ?? 'none'} data-testid="suggestion">
      <div className="suggestion__body">
        <div className="suggestion__main">
          {suggestion.kind === 'ACADEMIC' && (
            <p className="suggestion__value">
              <span className="suggestion__field">{t.fields[suggestion.field ?? 'CAREER']}</span>{' '}
              <strong>{String(decision?.text ?? asAcademic(suggestion.value).value)}</strong>
            </p>
          )}
          {isSkill && (
            <p className="suggestion__value">
              <strong>{name}</strong>
              {slug && skill && !skill.slug && (
                <span className="suggestion__field"> {t.linkedTo(name)}</span>
              )}
            </p>
          )}
          {suggestion.kind === 'PROJECT' && (
            <ProjectBody suggestion={suggestion} decision={decision} />
          )}
          <span className="suggestion__status" data-decision={action ?? 'none'}>
            {status}
          </span>
        </div>

        {suggestion.evidence?.trim() && (
          <details className="suggestion__evidence">
            <summary>{t.fromCv}</summary>
            <p>«{suggestion.evidence}»</p>
          </details>
        )}
        <p className="suggestion__current">
          {t.current(suggestion.currentValue || es.redesign.noData)}
        </p>
        {isSkill && skill && !skill.slug && !decision?.slug && (
          <p className="suggestion__note">{t.unlinked}</p>
        )}
      </div>

      {isSkill && (
        <>
          <Button variant="quiet" size="sm" onClick={onActivate} aria-expanded={active}>
            {es.redesign.reviewLevel}
          </Button>
          {active && <LevelSelect skillName={name} value={level} onChange={onLevel} />}
        </>
      )}

      <div className="suggestion__actions">
        <Button
          size="sm"
          variant={action === 'ACCEPT' || action === 'EDIT' ? 'primary' : 'quiet'}
          aria-pressed={action === 'ACCEPT' || action === 'EDIT'}
          aria-label={t.acceptLabel(name)}
          onClick={() =>
            action === 'ACCEPT' || action === 'EDIT' ? onChange(undefined) : accept()
          }
        >
          {t.accept}
        </Button>
        {isSkill ? (
          <Button
            size="sm"
            variant="quiet"
            aria-expanded={picking}
            aria-label={t.editLabel(name)}
            onClick={() => setPicking((open) => !open)}
          >
            {skill?.slug || decision?.slug ? t.changeSkill : t.linkSkill}
          </Button>
        ) : (
          <Button
            size="sm"
            variant="quiet"
            aria-expanded={editing}
            aria-label={t.editLabel(name)}
            onClick={() => setEditing((open) => !open)}
          >
            {t.edit}
          </Button>
        )}
        <Button
          size="sm"
          variant={action === 'DISCARD' ? 'primary' : 'quiet'}
          aria-pressed={action === 'DISCARD'}
          aria-label={t.discardLabel(name)}
          onClick={() => (action === 'DISCARD' ? onChange(undefined) : decide('DISCARD'))}
        >
          {t.discard}
        </Button>
      </div>

      {error && (
        <p className="suggestion__error" role="alert">
          {error}
        </p>
      )}

      {picking && (
        <div className="suggestion__panel">
          <SkillPicker added={NO_SLUGS} onPick={onPick} onClose={() => setPicking(false)} />
        </div>
      )}

      {editing && suggestion.kind === 'ACADEMIC' && (
        <AcademicEditor
          suggestion={suggestion}
          decision={decision}
          onDone={(text) => {
            setEditing(false)
            setLocalError(null)
            onChange({ ...decision, text, action: 'EDIT' })
          }}
        />
      )}
      {editing && suggestion.kind === 'PROJECT' && (
        <ProjectEditor
          suggestion={suggestion}
          decision={decision}
          onDone={(project) => {
            setEditing(false)
            setLocalError(null)
            onChange({ ...decision, project, action: 'EDIT' })
          }}
        />
      )}
    </li>
  )
}

function ProjectBody({
  suggestion,
  decision,
}: {
  suggestion: Suggestion
  decision: RowDecision | undefined
}) {
  const project = decision?.project ?? asProject(suggestion.value)
  return (
    <div className="suggestion__project">
      <strong>{project.name}</strong>
      <p className="suggestion__description">{project.description}</p>
      {project.technologies.length > 0 && (
        <ul className="suggestion__tags" aria-label={es.profile.projects.techList}>
          {project.technologies.map((tech) => (
            <li key={tech} className="suggestion__tag">
              {tech}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

function AcademicEditor({
  suggestion,
  decision,
  onDone,
}: {
  suggestion: Suggestion
  decision: RowDecision | undefined
  onDone: (text: string) => void
}) {
  const [text, setText] = useState(String(decision?.text ?? asAcademic(suggestion.value).value))
  const field = suggestion.field ?? 'CAREER'
  return (
    <div className="suggestion__panel">
      <TextField
        label={t.fields[field]}
        hint={field === 'CYCLE' ? t.cycleHint : undefined}
        value={text}
        inputMode={field === 'CYCLE' ? 'numeric' : undefined}
        maxLength={PROFILE_LIMITS.careerMaxLength}
        onChange={(event) => setText(event.target.value)}
      />
      <div>
        <Button size="sm" onClick={() => onDone(text.trim())}>
          {t.editDone}
        </Button>
      </div>
    </div>
  )
}

function ProjectEditor({
  suggestion,
  decision,
  onDone,
}: {
  suggestion: Suggestion
  decision: RowDecision | undefined
  onDone: (project: { name: string; description: string; technologies: string[] }) => void
}) {
  const start = decision?.project ?? asProject(suggestion.value)
  const [name, setName] = useState(start.name)
  const [description, setDescription] = useState(start.description)
  const [technologies, setTechnologies] = useState<string[]>(start.technologies)
  const p = es.profile.projects
  return (
    <div className="suggestion__panel">
      <TextField
        label={p.name}
        value={name}
        maxLength={PROFILE_LIMITS.projectNameMaxLength}
        onChange={(event) => setName(event.target.value)}
      />
      <TextAreaField
        label={p.description}
        value={description}
        maxLength={PROFILE_LIMITS.projectDescriptionMaxLength}
        onChange={(event) => setDescription(event.target.value)}
      />
      <TagField
        label={p.technologies}
        hint={p.technologiesHint}
        values={technologies}
        onChange={setTechnologies}
        addLabel={p.techAdd}
        removeLabel={p.techRemove}
        listLabel={p.techList}
      />
      <div>
        <Button
          size="sm"
          onClick={() =>
            onDone({ name: name.trim(), description: description.trim(), technologies })
          }
        >
          {t.editDone}
        </Button>
      </div>
    </div>
  )
}
