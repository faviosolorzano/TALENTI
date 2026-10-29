import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useSearchParams } from 'react-router'
import { acceptLegalDocuments, fetchLegalCatalog } from '../../api/legal'
import { ApiError } from '../../api/client'
import { Button } from '../../components/Button'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { loginPathWithReturn } from '../../session/returnTo'
import { useSession } from '../../session/useSession'
import { useSignOut } from '../../session/useSignOut'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import { DraftNotice } from './DraftNotice'
import { formatPublishedDate } from './format'
import { DEFAULT_RETURN_PATH, LEGAL_PATHS, safeReturnTo } from './paths'
import '../vacancies/Skeletons.css'
import './AcceptDocumentsPage.css'

type Problem = 'mustAccept' | 'outdated' | 'failed'

/**
 * `/aceptar-documentos` (002-18, 002-19): the Terms or the Privacy Policy changed since the
 * student accepted them. They read the new texts, tick the box (it starts unticked) and
 * continue to the page they asked for (`returnTo`, internal routes only); or they sign out.
 * Signing out and reading the documents are never blocked. Who is signed in, and whether
 * something is pending, comes from the session (`SessionProvider`, `GET /auth/me`); after
 * accepting or signing out the session is refreshed without reloading the page.
 */
export function AcceptDocumentsPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const location = useLocation()
  const session = useSession()
  const returnTo = safeReturnTo(params.get('returnTo')) ?? DEFAULT_RETURN_PATH
  const { state, retry, refreshing } = useApiResource('accept-documents', fetchLegalCatalog, {
    keepPrevious: true,
  })
  const [checked, setChecked] = useState(false)
  const [problem, setProblem] = useState<Problem | null>(null)
  const [saving, setSaving] = useState(false)
  // The same "Cerrar sesión" as the header and the panel: same destination, same message.
  const { signOut, pending: leaving, failed: logoutFailed } = useSignOut()
  const checkboxRef = useRef<HTMLInputElement>(null)
  const problemId = useId()
  const { refresh } = session

  // Each time the screen opens the answer of the server is checked again.
  useEffect(() => {
    void refresh({ onlyIfStale: true })
  }, [refresh])

  const signedOut = session.status === 'visitor'
  const upToDate = session.status === 'authenticated' && !session.legalAcceptancePending

  // No session (or it expired, 001-14): to the sign-in page, coming back here afterwards.
  useEffect(() => {
    if (!signedOut || leaving) return
    navigate(loginPathWithReturn(returnTo), {
      replace: true,
      state: { ...location.state, ...(session.notice ? { notice: session.notice } : {}) },
    })
  }, [signedOut, leaving, navigate, returnTo, session.notice, location.state])

  // Nothing pending: this screen has nothing to show ("con la versión al día no aparece nada").
  useEffect(() => {
    if (upToDate && !saving) navigate(returnTo, { replace: true, state: location.state })
  }, [upToDate, saving, navigate, returnTo, location.state])

  const pending = session.status === 'authenticated' && session.legalAcceptancePending
  const sessionLoading = session.status === 'loading'
  const sessionUnavailable = session.status === 'unavailable'

  const accept = async (version: string) => {
    if (!checked) {
      setProblem('mustAccept')
      checkboxRef.current?.focus()
      return
    }
    setProblem(null)
    setSaving(true)
    try {
      await acceptLegalDocuments(version)
      // The server now has the acceptance: ask who we are again (no page reload) and go on.
      await refresh()
      navigate(returnTo, { replace: true, state: location.state })
      return
    } catch (error) {
      const status = error instanceof ApiError ? error.status : 0
      if (status === 401) {
        navigate(loginPathWithReturn(returnTo), { replace: true, state: location.state })
        return
      }
      if (status === 409) {
        // The version changed while reading: load the current text and ask again (like 001-03).
        setChecked(false)
        setProblem('outdated')
        retry()
      } else if (status === 422) {
        setProblem('mustAccept')
        checkboxRef.current?.focus()
      } else {
        setProblem('failed')
      }
    }
    setSaving(false)
  }

  const logout = () => {
    setProblem(null)
    void signOut()
  }

  const submit = (event: FormEvent<HTMLFormElement>, version: string) => {
    event.preventDefault()
    void accept(version)
  }

  const shownProblem = problem ?? (logoutFailed ? 'logoutFailed' : null)
  const catalog = state.status === 'success' && pending ? state.data : null
  const loadingNow = state.status === 'loading' || sessionLoading
  const failedNow = state.status === 'error' || sessionUnavailable
  const retryAll = () => {
    retry()
    void refresh()
  }
  const date = catalog ? formatPublishedDate(catalog.publishedAt) : null

  return (
    <section className="container accept">
      <h1 className="accept__title">{es.acceptDocuments.title}</h1>

      {(loadingNow || failedNow) && (
        <div aria-busy={loadingNow}>
          {loadingNow && (
            <>
              <p className="visually-hidden" role="status">
                {es.acceptDocuments.loading}
              </p>
              <div className="accept__skeleton" aria-hidden="true">
                <span className="skeleton skeleton--line" />
                <span className="skeleton skeleton--line skeleton--short" />
                <span className="skeleton skeleton--block" />
              </div>
            </>
          )}
          {!loadingNow && failedNow && (
            <ErrorNotice message={es.acceptDocuments.errorMessage} onRetry={retryAll} />
          )}
        </div>
      )}

      {catalog && (
        <form
          className="accept__form"
          onSubmit={(event) => submit(event, catalog.version)}
          aria-busy={refreshing}
          noValidate
        >
          <p className="accept__lead">{es.acceptDocuments.lead}</p>
          <p className="accept__version">
            {date
              ? es.legal.version(catalog.version, date)
              : es.legal.versionPendingDate(catalog.version)}
          </p>
          <DraftNotice />

          <div className="accept__read">
            <h2 className="accept__read-title">{es.acceptDocuments.readTitle}</h2>
            <ul className="accept__links">
              {(
                [
                  [LEGAL_PATHS.terms, es.footer.terms],
                  [LEGAL_PATHS.privacy, es.footer.privacy],
                ] as const
              ).map(([to, label]) => (
                <li key={to}>
                  <Link to={to} target="_blank" rel="noopener noreferrer">
                    {label} <span className="accept__new-tab">{es.legal.opensInNewTab}</span>
                  </Link>
                </li>
              ))}
            </ul>
          </div>

          <label className="accept__check">
            <input
              ref={checkboxRef}
              type="checkbox"
              checked={checked}
              aria-invalid={problem === 'mustAccept' || undefined}
              aria-describedby={shownProblem ? problemId : undefined}
              onChange={(event) => setChecked(event.target.checked)}
            />
            {es.acceptDocuments.checkbox}
          </label>

          {shownProblem && (
            <p id={problemId} className="accept__problem" role="alert">
              {es.acceptDocuments[shownProblem]}
            </p>
          )}

          <div className="accept__actions">
            <Button type="submit" disabled={saving || leaving}>
              {saving ? es.acceptDocuments.saving : es.acceptDocuments.submit}
            </Button>
            <Button variant="quiet" disabled={leaving} onClick={logout}>
              {leaving ? es.acceptDocuments.loggingOut : es.acceptDocuments.logout}
            </Button>
          </div>
        </form>
      )}
    </section>
  )
}
