import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import {
  acceptLegalDocuments,
  fetchLegalAcceptancePending,
  fetchLegalCatalog,
  logoutRequest,
} from '../../api/legal'
import { ApiError } from '../../api/client'
import { Button } from '../../components/Button'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { ErrorNotice } from '../vacancies/ErrorNotice'
import { DraftNotice } from './DraftNotice'
import { formatPublishedDate } from './format'
import { leaveTo } from './navigation'
import { DEFAULT_RETURN_PATH, LEGAL_PATHS, safeReturnTo } from './paths'
import '../vacancies/Skeletons.css'
import './AcceptDocumentsPage.css'

type Problem = 'mustAccept' | 'outdated' | 'failed' | 'logoutFailed'

/**
 * `/aceptar-documentos` (002-18, 002-19): the Terms or the Privacy Policy changed since the
 * student accepted them. They read the new texts, tick the box (it starts unticked) and
 * continue to the page they asked for (`returnTo`, internal routes only); or they sign out.
 * Signing out and reading the documents are never blocked.
 */
export function AcceptDocumentsPage() {
  const [params] = useSearchParams()
  const navigate = useNavigate()
  const returnTo = safeReturnTo(params.get('returnTo')) ?? DEFAULT_RETURN_PATH
  const { state, retry, refreshing } = useApiResource(
    'accept-documents',
    async (signal) => {
      const [pending, catalog] = await Promise.all([
        fetchLegalAcceptancePending(signal),
        fetchLegalCatalog(signal),
      ])
      return { pending, catalog }
    },
    { keepPrevious: true },
  )
  const [checked, setChecked] = useState(false)
  const [problem, setProblem] = useState<Problem | null>(null)
  const [saving, setSaving] = useState(false)
  const [leaving, setLeaving] = useState(false)
  const checkboxRef = useRef<HTMLInputElement>(null)
  const problemId = useId()

  const sessionExpired =
    state.status === 'error' && state.error instanceof ApiError && state.error.status === 401
  const upToDate = state.status === 'success' && !state.data.pending

  // No session (or it expired, 001-14): to the sign-in page, coming back here afterwards.
  useEffect(() => {
    if (!sessionExpired) return
    navigate(`/ingresar?returnTo=${encodeURIComponent(returnTo)}`, { replace: true })
  }, [sessionExpired, navigate, returnTo])

  // Nothing pending: this screen has nothing to show ("con la versión al día no aparece nada").
  useEffect(() => {
    if (upToDate) leaveTo(returnTo)
  }, [upToDate, returnTo])

  const handleSessionLost = () => {
    navigate(`/ingresar?returnTo=${encodeURIComponent(returnTo)}`, { replace: true })
  }

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
      leaveTo(returnTo)
      return
    } catch (error) {
      const status = error instanceof ApiError ? error.status : 0
      if (status === 401) {
        handleSessionLost()
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

  const logout = async () => {
    setProblem(null)
    setLeaving(true)
    try {
      await logoutRequest()
    } catch (error) {
      // Already signed out (401) is fine; anything else keeps the student here with a message.
      if (!(error instanceof ApiError && error.status === 401)) {
        setProblem('logoutFailed')
        setLeaving(false)
        return
      }
    }
    leaveTo('/')
  }

  const submit = (event: FormEvent<HTMLFormElement>, version: string) => {
    event.preventDefault()
    void accept(version)
  }

  const catalog = state.status === 'success' && state.data.pending ? state.data.catalog : null
  const date = catalog ? formatPublishedDate(catalog.publishedAt) : null

  return (
    <section className="container accept">
      <h1 className="accept__title">{es.acceptDocuments.title}</h1>

      {(state.status === 'loading' || (state.status === 'error' && !sessionExpired)) && (
        <div aria-busy={state.status === 'loading'}>
          {state.status === 'loading' && (
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
          {state.status === 'error' && (
            <ErrorNotice message={es.acceptDocuments.errorMessage} onRetry={retry} />
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
              aria-describedby={problem ? problemId : undefined}
              onChange={(event) => setChecked(event.target.checked)}
            />
            {es.acceptDocuments.checkbox}
          </label>

          {problem && (
            <p id={problemId} className="accept__problem" role="alert">
              {es.acceptDocuments[problem]}
            </p>
          )}

          <div className="accept__actions">
            <Button type="submit" disabled={saving || leaving}>
              {saving ? es.acceptDocuments.saving : es.acceptDocuments.submit}
            </Button>
            <Button variant="quiet" disabled={leaving} onClick={() => void logout()}>
              {leaving ? es.acceptDocuments.loggingOut : es.acceptDocuments.logout}
            </Button>
          </div>
        </form>
      )}
    </section>
  )
}
