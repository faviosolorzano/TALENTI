import { useEffect, useId, useRef, useState } from 'react'
import {
  addProject,
  deleteProject,
  updateProject,
  type Profile,
  type ProfileProject,
} from '../../api/profile'
import { ApiError } from '../../api/client'
import { Button } from '../../components/Button'
import { TagField } from '../../components/TagField'
import { TextAreaField } from '../../components/TextAreaField'
import { TextField } from '../../components/TextField'
import { es } from '../../i18n/es'
import { useSession } from '../../session/useSession'
import { SectionForm } from './SectionForm'
import { PROFILE_LIMITS } from './sections'
import { messageFor, otherMessages, useSubmit } from './useSubmit'
import './ProjectsSection.css'

const t = es.profile.projects

function TechnologyList({ technologies }: { technologies: readonly string[] }) {
  return (
    <ul className="project__tech" aria-label={t.techList}>
      {technologies.map((technology) => (
        <li key={technology} className="project__tag">
          {technology}
        </li>
      ))}
    </ul>
  )
}

/** The content of one project (name, description, technologies). Always shown as text. */
function ProjectBody({ project }: { project: ProfileProject }) {
  return (
    <>
      <h3 className="project__name">{project.name}</h3>
      <p className="project__description">{project.description}</p>
      <TechnologyList technologies={project.technologies} />
    </>
  )
}

/** Read mode: every project with its description and technologies (006-18). */
export function ProjectsSummary({ profile }: { profile: Profile }) {
  if (profile.projects.length === 0) return <p className="read-empty">{t.empty}</p>
  return (
    <ul className="projects__list" aria-label={t.listLabel}>
      {profile.projects.map((project) => (
        <li key={project.id} className="project">
          <ProjectBody project={project} />
        </li>
      ))}
    </ul>
  )
}

interface FormProps {
  /** The project being edited; none = a new one. */
  project?: ProfileProject
  onSaved: (profile: Profile) => void
  onCancel: () => void
  onDirtyChange: (dirty: boolean) => void
}

const FIELDS = ['name', 'description', 'technologies'] as const

/** Form of one project: add or edit (006-08). It saves by itself, not with the section. */
function ProjectForm({ project, onSaved, onCancel, onDirtyChange }: FormProps) {
  const initial = {
    name: project?.name ?? '',
    description: project?.description ?? '',
    technologies: project?.technologies ?? [],
  }
  const [name, setName] = useState(initial.name)
  const [description, setDescription] = useState(initial.description)
  const [technologies, setTechnologies] = useState<string[]>(initial.technologies)
  const { state, run } = useSubmit(onSaved)
  const titleId = useId()

  const dirty =
    name !== initial.name ||
    description !== initial.description ||
    technologies.join('\n') !== initial.technologies.join('\n')
  useEffect(() => {
    onDirtyChange(dirty)
    return () => onDirtyChange(false)
  }, [dirty, onDirtyChange])

  const input = { name, description, technologies }
  return (
    <div className="project-form" role="group" aria-labelledby={titleId}>
      <h3 id={titleId} className="project-form__title">
        {project ? t.editTitle : t.newTitle}
      </h3>
      <SectionForm
        state={state}
        saveLabel={t.save}
        otherProblems={otherMessages(state, FIELDS)}
        onCancel={onCancel}
        onSubmit={() =>
          void run(() => (project ? updateProject(project.id, input) : addProject(input)))
        }
      >
        <TextField
          label={t.name}
          name="name"
          autoComplete="off"
          maxLength={PROFILE_LIMITS.projectNameMaxLength}
          value={name}
          onChange={(event) => setName(event.target.value)}
          error={messageFor(state, 'name')}
        />
        <TextAreaField
          label={t.description}
          hint={t.descriptionHint}
          counter={t.counter(description.length, PROFILE_LIMITS.projectDescriptionMaxLength)}
          name="description"
          maxLength={PROFILE_LIMITS.projectDescriptionMaxLength}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          error={messageFor(state, 'description')}
        />
        <TagField
          maxItems={8}
          label={t.technologies}
          hint={t.technologiesHint}
          addLabel={t.techAdd}
          removeLabel={t.techRemove}
          listLabel={t.techList}
          maxLength={PROFILE_LIMITS.technologyMaxLength}
          values={technologies}
          onChange={setTechnologies}
          error={messageFor(state, 'technologies')}
        />
      </SectionForm>
    </div>
  )
}

interface ConfirmProps {
  project: ProfileProject
  onDeleted: (profile: Profile) => void
  onCancel: () => void
}

/** Asks before deleting (006-18). The safe answer, "Cancelar", has the focus. */
function DeleteConfirm({ project, onDeleted, onCancel }: ConfirmProps) {
  const session = useSession()
  const { refresh } = session
  const ref = useRef<HTMLDivElement>(null)
  const [state, setState] = useState<'idle' | 'deleting' | 'failed'>('idle')
  const titleId = useId()

  useEffect(() => {
    ref.current?.querySelector<HTMLElement>('[data-cancel]')?.focus()
  }, [])

  async function confirm() {
    if (state === 'deleting') return
    setState('deleting')
    try {
      onDeleted(await deleteProject(project.id))
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) void refresh()
      setState('failed')
    }
  }

  return (
    <div ref={ref} className="project-confirm" role="group" aria-labelledby={titleId}>
      <p id={titleId} className="project-confirm__title">
        {t.confirmTitle(project.name)}
      </p>
      <p>{t.confirmBody}</p>
      {state === 'failed' && (
        <p className="project-confirm__error" role="alert">
          {t.removeFailed}
        </p>
      )}
      <div className="project-confirm__actions">
        <Button variant="danger" disabled={state === 'deleting'} onClick={() => void confirm()}>
          {state === 'deleting' ? t.removing : t.confirmYes}
        </Button>
        <Button variant="quiet" data-cancel disabled={state === 'deleting'} onClick={onCancel}>
          {es.profile.actions.cancel}
        </Button>
      </div>
    </div>
  )
}

type Mode =
  { kind: 'idle' } | { kind: 'new' } | { kind: 'edit'; id: string } | { kind: 'delete'; id: string }

interface EditorProps {
  profile: Profile
  /** A change was saved. The card stays open: the person may keep working on projects. */
  onChanged: (profile: Profile) => void
  onClose: () => void
  onDirtyChange: (dirty: boolean) => void
}

/** Edit mode of "Proyectos": list with edit/delete, add, and up to 10 (006-18). */
export function ProjectsEditor({ profile, onChanged, onClose, onDirtyChange }: EditorProps) {
  const [mode, setMode] = useState<Mode>({ kind: 'idle' })
  const [focusRequest, setFocusRequest] = useState<{ selector: string } | null>(null)
  const containerRef = useRef<HTMLDivElement>(null)

  // Puts the focus back where the person was after a form or a confirmation disappears.
  useEffect(() => {
    if (focusRequest) {
      containerRef.current?.querySelector<HTMLElement>(focusRequest.selector)?.focus()
    }
  }, [focusRequest])

  const atLimit = profile.projects.length >= PROFILE_LIMITS.maxProjects
  const formOpen = mode.kind === 'new' || mode.kind === 'edit'

  function finish(next: Profile, focus: string) {
    setMode({ kind: 'idle' })
    setFocusRequest({ selector: focus })
    onChanged(next)
  }

  return (
    <div ref={containerRef} className="projects-editor">
      <p className="projects-editor__hint">{t.hint}</p>

      {profile.projects.length > 0 && (
        <ul className="projects__list" aria-label={t.listLabel}>
          {profile.projects.map((project) => {
            if (mode.kind === 'edit' && mode.id === project.id) {
              return (
                <li key={project.id} className="project project--editing">
                  <ProjectForm
                    project={project}
                    onSaved={(next) => finish(next, `[data-edit-for="${project.id}"]`)}
                    onCancel={() => {
                      setMode({ kind: 'idle' })
                      setFocusRequest({ selector: `[data-edit-for="${project.id}"]` })
                    }}
                    onDirtyChange={onDirtyChange}
                  />
                </li>
              )
            }
            const deleting = mode.kind === 'delete' && mode.id === project.id
            return (
              <li
                key={project.id}
                className="project"
                data-state={deleting ? 'deleting' : undefined}
              >
                <ProjectBody project={project} />
                {deleting ? (
                  <DeleteConfirm
                    project={project}
                    onCancel={() => {
                      setMode({ kind: 'idle' })
                      setFocusRequest({ selector: `[data-delete-for="${project.id}"]` })
                    }}
                    onDeleted={(next) => finish(next, '[data-after-change]')}
                  />
                ) : (
                  <div className="project__actions">
                    <Button
                      variant="quiet"
                      size="sm"
                      data-edit-for={project.id}
                      disabled={formOpen || mode.kind === 'delete'}
                      aria-label={t.editLabel(project.name)}
                      onClick={() => setMode({ kind: 'edit', id: project.id })}
                    >
                      {t.edit}
                    </Button>
                    <Button
                      variant="quiet"
                      size="sm"
                      data-delete-for={project.id}
                      disabled={formOpen || mode.kind === 'delete'}
                      aria-label={t.removeLabel(project.name)}
                      onClick={() => setMode({ kind: 'delete', id: project.id })}
                    >
                      {t.remove}
                    </Button>
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}

      {mode.kind === 'new' && (
        <ProjectForm
          onSaved={(next) => finish(next, '[data-after-change]')}
          onCancel={() => {
            setMode({ kind: 'idle' })
            setFocusRequest({ selector: '[data-after-change]' })
          }}
          onDirtyChange={onDirtyChange}
        />
      )}

      {!formOpen && (
        <div className="projects-editor__footer">
          {atLimit ? (
            <p className="projects-editor__limit" role="status">
              {t.limitReached}
            </p>
          ) : (
            <Button
              variant="quiet"
              data-after-change
              data-autofocus={profile.projects.length === 0 ? true : undefined}
              disabled={mode.kind === 'delete'}
              onClick={() => setMode({ kind: 'new' })}
            >
              <span aria-hidden="true">+</span> {t.add}
            </Button>
          )}
          <Button
            data-after-change={atLimit ? true : undefined}
            disabled={mode.kind === 'delete'}
            onClick={onClose}
          >
            {es.profile.actions.done}
          </Button>
        </div>
      )}
    </div>
  )
}
