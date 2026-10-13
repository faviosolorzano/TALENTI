import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useSearchParams } from 'react-router'
import { ApiError } from '../../api/client'
import { fetchProfile, type Profile } from '../../api/profile'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { useSession } from '../../session/useSession'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import { AcademicForm, AcademicSummary } from './AcademicSection'
import { AvailabilityForm, AvailabilitySummary } from './AvailabilitySection'
import { CvCard } from './CvCard'
import { ProfileSkeleton } from './ProfileSkeleton'
import { ProfileSummary } from './ProfileSummary'
import { ProjectsEditor, ProjectsSummary } from './ProjectsSection'
import { SectionCard } from './SectionCard'
import { SkillsForm, SkillsSummary } from './SkillsSection'
import {
  isSectionDone,
  sectionFromParam,
  sectionOfItem,
  SECTION_ORDER,
  type SectionKey,
} from './sections'
import './ProfileScreen.css'

/** How long "Guardado" stays next to the title of a card. */
const SAVED_VISIBLE_MS = 4000

const TITLES: Record<SectionKey, string> = {
  academic: es.profile.academic.title,
  skills: es.profile.skills.title,
  availability: es.profile.availability.title,
  projects: es.profile.projects.title,
}

/**
 * Page /perfil (spec 006). It loads the profile and, once loaded, `ProfileView` takes over:
 * the profile is kept in memory only, and every save replaces it with the whole profile the
 * server answers (percentage included).
 */
export function ProfileScreen() {
  const { refresh } = useSession()
  const resource = useApiResource('profile', async (signal) => {
    try {
      return await fetchProfile(signal)
    } catch (error) {
      // A session that expired is told by the server; the guard then goes to /ingresar.
      if (error instanceof ApiError && error.status === 401) void refresh()
      throw error
    }
  })

  if (resource.state.status === 'loading') return <ProfileSkeleton />

  if (resource.state.status === 'error') {
    return (
      <div className="container profile">
        <div className="profile__head">
          <h1 className="profile__title">{es.profile.title}</h1>
        </div>
        <ErrorNotice message={es.profile.loadError} onRetry={resource.retry} />
      </div>
    )
  }

  return <ProfileView initial={resource.state.data} />
}

function ProfileView({ initial }: { initial: Profile }) {
  const [params] = useSearchParams()
  // `?seccion=` opens a card at the start; an unknown value is ignored (006-20).
  const fromAddress = sectionFromParam(params.get('seccion'))

  const [profile, setProfile] = useState(initial)
  const [openKey, setOpenKey] = useState<SectionKey | null>(fromAddress)
  const [bringKey, setBringKey] = useState<SectionKey | null>(fromAddress)
  // Cards with unsaved changes stay open when the person opens another one.
  const [dirty, setDirty] = useState<ReadonlySet<SectionKey>>(new Set())
  const [savedKey, setSavedKey] = useState<SectionKey | null>(null)

  useEffect(() => {
    if (savedKey === null) return
    const timer = setTimeout(() => setSavedKey(null), SAVED_VISIBLE_MS)
    return () => clearTimeout(timer)
  }, [savedKey])

  const reportDirty = useCallback((section: SectionKey, value: boolean) => {
    setDirty((current) => {
      if (current.has(section) === value) return current
      const next = new Set(current)
      if (value) next.add(section)
      else next.delete(section)
      return next
    })
  }, [])

  // One stable callback per card, so the forms do not report again on every render.
  const dirtyReporters = useMemo(
    () => ({
      academic: (value: boolean) => reportDirty('academic', value),
      skills: (value: boolean) => reportDirty('skills', value),
      availability: (value: boolean) => reportDirty('availability', value),
      projects: (value: boolean) => reportDirty('projects', value),
    }),
    [reportDirty],
  )

  const isOpen = (section: SectionKey) => openKey === section || dirty.has(section)

  function openSection(section: SectionKey, bring = false) {
    setOpenKey(section)
    setBringKey(bring ? section : null)
  }

  const closeSection = useCallback(
    (section: SectionKey) => {
      setOpenKey((current) => (current === section ? null : current))
      reportDirty(section, false)
    },
    [reportDirty],
  )

  /** A section saved: show the new profile (and its percentage) and go back to read mode. */
  function saved(section: SectionKey, next: Profile, keepOpen = false) {
    setProfile(next)
    setSavedKey(section)
    if (!keepOpen) closeSection(section)
  }

  const { completeness } = profile
  const nextSection = completeness.nextStep ? sectionOfItem(completeness.nextStep) : null

  const card = (section: SectionKey, summary: ReactNode, form: ReactNode) => (
    <SectionCard
      key={section}
      section={section}
      title={TITLES[section]}
      done={isSectionDone(completeness, section)}
      open={isOpen(section)}
      emphasized={nextSection === section}
      justSaved={savedKey === section}
      bringIntoView={bringKey === section}
      onEdit={() => openSection(section)}
      summary={summary}
    >
      {form}
    </SectionCard>
  )

  const cards: Record<SectionKey, ReactNode> = {
    academic: card(
      'academic',
      <AcademicSummary profile={profile} />,
      <AcademicForm
        profile={profile}
        onSaved={(next) => saved('academic', next)}
        onCancel={() => closeSection('academic')}
        onDirtyChange={dirtyReporters.academic}
      />,
    ),
    skills: card(
      'skills',
      <SkillsSummary profile={profile} />,
      <SkillsForm
        profile={profile}
        onSaved={(next) => saved('skills', next)}
        onCancel={() => closeSection('skills')}
        onDirtyChange={dirtyReporters.skills}
      />,
    ),
    availability: card(
      'availability',
      <AvailabilitySummary profile={profile} />,
      <AvailabilityForm
        profile={profile}
        onSaved={(next) => saved('availability', next)}
        onCancel={() => closeSection('availability')}
        onDirtyChange={dirtyReporters.availability}
      />,
    ),
    projects: card(
      'projects',
      <ProjectsSummary profile={profile} />,
      <ProjectsEditor
        profile={profile}
        onChanged={(next) => saved('projects', next, true)}
        onClose={() => closeSection('projects')}
        onDirtyChange={dirtyReporters.projects}
      />,
    ),
  }

  return (
    <div className="container profile">
      <div className="profile__head">
        <h1 className="profile__title">{es.profile.title}</h1>
        <p className="profile__lead">{es.profile.lead}</p>
      </div>

      <div className="profile__layout">
        <div className="profile__aside">
          <ProfileSummary
            completeness={completeness}
            onOpenSection={(section) => openSection(section, true)}
          />
          <CvCard />
        </div>
        <div className="profile__sections">{SECTION_ORDER.map((section) => cards[section])}</div>
      </div>
    </div>
  )
}
