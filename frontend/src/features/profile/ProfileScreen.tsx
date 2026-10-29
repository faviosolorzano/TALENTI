import { ProfileSavingContext } from './profileSavingContext'
import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { Navigate, useSearchParams, useBlocker, useLocation, useNavigate } from 'react-router'
import { Dialog } from '../../components/Dialog'
import { Button } from '../../components/Button'
import { safeReturnTo } from '../../session/returnTo'
import { ApiError } from '../../api/client'
import { fetchProfile, type Profile } from '../../api/profile'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { useSession } from '../../session/useSession'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import { AboutForm, AboutSummary } from './AboutSection'
import { AcademicForm, AcademicSummary } from './AcademicSection'
import { AvailabilityForm, AvailabilitySummary } from './AvailabilitySection'
import { ProfileSkeleton } from './ProfileSkeleton'
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

const TITLES: Record<SectionKey, string> = {
  about: es.profile.about.title,
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
  const [query] = useSearchParams()
  const githubOutcome = query.get('github')

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

  if (githubOutcome === 'linked' || githubOutcome === 'error')
    return <Navigate replace to={'/perfil/github?github=' + githubOutcome} />
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
  const location = useLocation()
  const navigate = useNavigate()
  const returnTo = safeReturnTo(params.get('returnTo'))
  const editorRef = useRef<HTMLDivElement>(null)
  const tabsRef = useRef<HTMLElement>(null)

  // `?seccion=` opens a card at the start; an unknown value is ignored (006-20).
  const fromAddress = sectionFromParam(params.get('seccion'))

  const [profile, setProfile] = useState(initial)
  const [saving, setSaving] = useState(false)
  const [selected, setSelected] = useState<SectionKey>(fromAddress ?? 'about')
  const [promptHidden, setPromptHidden] = useState(false)
  const [pendingSection, setPendingSection] = useState<SectionKey | 'cancel' | null>(null)
  const [openKey, setOpenKey] = useState<SectionKey | null>(fromAddress)
  const [bringKey, setBringKey] = useState<SectionKey | null>(fromAddress)
  // Cards with unsaved changes stay open when the person opens another one.
  const [dirty, setDirty] = useState<ReadonlySet<SectionKey>>(new Set())
  const [savedKey, setSavedKey] = useState<SectionKey | null>(null)

  useEffect(() => {
    const revealSelected = () => {
      const nav = tabsRef.current
      const tab = nav?.querySelector<HTMLElement>('[aria-current="page"]')
      if (!nav || !tab) return
      const region = nav.getBoundingClientRect()
      const item = tab.getBoundingClientRect()
      if (item.left < region.left) nav.scrollLeft -= region.left - item.left
      else if (item.right > region.right) nav.scrollLeft += item.right - region.right
    }
    revealSelected()
    window.addEventListener('resize', revealSelected)
    return () => window.removeEventListener('resize', revealSelected)
  }, [selected])

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
      about: (value: boolean) => reportDirty('about', value),
      academic: (value: boolean) => reportDirty('academic', value),
      skills: (value: boolean) => reportDirty('skills', value),
      availability: (value: boolean) => reportDirty('availability', value),
      projects: (value: boolean) => reportDirty('projects', value),
    }),
    [reportDirty],
  )

  const blocker = useBlocker(
    ({ currentLocation, nextLocation }) =>
      dirty.size > 0 &&
      currentLocation.pathname + currentLocation.search !==
        nextLocation.pathname + nextLocation.search,
  )
  useEffect(() => {
    if (!dirty.size) return
    const warn = (event: BeforeUnloadEvent) => {
      event.preventDefault()
      event.returnValue = ''
    }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [dirty.size])
  const isOpen = (section: SectionKey) => openKey === section

  function openSection(section: SectionKey, bring = false) {
    setPromptHidden(false)
    if (dirty.size && section !== selected) {
      setPendingSection(section)
      return
    }
    setSelected(section)
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
    reportDirty(section, false)
    if (blocker.state === 'blocked') {
      blocker.proceed()
      return
    }
    if (pendingSection) {
      const target = pendingSection
      setPendingSection(null)
      if (target === 'cancel') closeSection(section)
      else {
        setSelected(target)
        setOpenKey(target)
      }
      return
    }
    if (returnTo && !keepOpen) {
      navigate(returnTo, { state: location.state })
      return
    }
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
    about: card(
      'about',
      <AboutSummary profile={profile} />,
      <AboutForm
        profile={profile}
        onSaved={(next) => saved('about', next)}
        onCancel={() =>
          dirty.size ? (setPromptHidden(false), setPendingSection('cancel')) : closeSection('about')
        }
        onDirtyChange={dirtyReporters.about}
      />,
    ),
    academic: card(
      'academic',
      <AcademicSummary profile={profile} />,
      <AcademicForm
        profile={profile}
        onSaved={(next) => saved('academic', next)}
        onCancel={() =>
          dirty.size
            ? (setPromptHidden(false), setPendingSection('cancel'))
            : closeSection('academic')
        }
        onDirtyChange={dirtyReporters.academic}
      />,
    ),
    skills: card(
      'skills',
      <SkillsSummary profile={profile} />,
      <SkillsForm
        profile={profile}
        onSaved={(next) => saved('skills', next)}
        onCancel={() =>
          dirty.size
            ? (setPromptHidden(false), setPendingSection('cancel'))
            : closeSection('skills')
        }
        onDirtyChange={dirtyReporters.skills}
      />,
    ),
    availability: card(
      'availability',
      <AvailabilitySummary profile={profile} />,
      <AvailabilityForm
        profile={profile}
        onSaved={(next) => saved('availability', next)}
        onCancel={() =>
          dirty.size
            ? (setPromptHidden(false), setPendingSection('cancel'))
            : closeSection('availability')
        }
        onDirtyChange={dirtyReporters.availability}
      />,
    ),
    projects: card(
      'projects',
      <ProjectsSummary profile={profile} />,
      <ProjectsEditor
        profile={profile}
        onChanged={(next) => saved('projects', next, true)}
        onClose={() =>
          dirty.size
            ? (setPromptHidden(false), setPendingSection('cancel'))
            : closeSection('projects')
        }
        onDirtyChange={dirtyReporters.projects}
      />,
    ),
  }

  return (
    <ProfileSavingContext value={setSaving}>
      <div className="container profile">
        <div className="profile__head">
          {returnTo && (
            <Button
              disabled={saving}
              variant="quiet"
              size="sm"
              to={returnTo}
              state={location.state}
            >
              {es.redesign.backVacancy}
            </Button>
          )}
          <h1 className="profile__title">{openKey ? TITLES[selected] : es.profile.title}</h1>
          <p className="profile__lead">{es.redesign.profileChoice}</p>
          <div className="profile__tools">
            <Button
              disabled={saving}
              to={returnTo ? `/perfil/cv?returnTo=${encodeURIComponent(returnTo)}` : '/perfil/cv'}
            >
              {es.redesign.useCv}
            </Button>
            <Button disabled={saving} variant="quiet" to="/perfil/github">
              {es.redesign.github}
            </Button>
          </div>
        </div>
        <nav className="profile-tabs" aria-label={es.profile.title} ref={tabsRef}>
          {SECTION_ORDER.map((section) => (
            <button
              key={section}
              type="button"
              disabled={saving}
              aria-current={section === selected ? 'page' : undefined}
              onClick={() => openSection(section, true)}
            >
              {TITLES[section]}
            </button>
          ))}
        </nav>
        <div className="profile__sections" ref={editorRef}>
          {savedKey && <p role="status">{es.profile.actions.saved}</p>}
          {dirty.size > 0 && (
            <p className="profile__dirty" role="status">
              {es.redesign.unsaved}
            </p>
          )}
          {cards[selected]}
        </div>
        {!promptHidden && (pendingSection || blocker.state === 'blocked') && (
          <Dialog
            busy={saving}
            title={es.redesign.leaveTitle}
            onClose={() => {
              setPendingSection(null)
              if (blocker.state === 'blocked') blocker.reset()
            }}
          >
            <p>{es.redesign.leaveBody}</p>
            <div className="profile__tools">
              <Button
                disabled={saving}
                onClick={() => {
                  setPromptHidden(true)
                  requestAnimationFrame(() =>
                    editorRef.current?.querySelector<HTMLFormElement>('form')?.requestSubmit(),
                  )
                }}
              >
                {es.redesign.saveLeave}
              </Button>
              <Button
                disabled={saving}
                variant="quiet"
                onClick={() => {
                  const target = pendingSection
                  setPendingSection(null)
                  setDirty(new Set())
                  if (blocker.state === 'blocked') blocker.proceed()
                  else if (target === 'cancel') setOpenKey(null)
                  else if (target) {
                    setSelected(target)
                    setOpenKey(target)
                  }
                }}
              >
                {es.redesign.discard}
              </Button>
              <Button
                disabled={saving}
                variant="quiet"
                onClick={() => {
                  setPendingSection(null)
                  if (blocker.state === 'blocked') blocker.reset()
                }}
              >
                {es.redesign.stay}
              </Button>
            </div>
          </Dialog>
        )}
      </div>
    </ProfileSavingContext>
  )
}
