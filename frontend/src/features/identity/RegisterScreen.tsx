import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, Navigate, useNavigate, useSearchParams } from 'react-router'
import { safeReturnTo } from '../../session/returnTo'
import { registerStudent } from '../../api/identity'
import { fetchLegalCatalog } from '../../api/legal'
import { Button } from '../../components/Button'
import { CheckboxField } from '../../components/CheckboxField'
import { Notice } from '../../components/Notice'
import { PasswordField } from '../../components/PasswordField'
import { TextField } from '../../components/TextField'
import { useApiResource } from '../../hooks/useApiResource'
import { es } from '../../i18n/es'
import { landingPathFor, sessionPaths } from '../../session/sessionPaths'
import { useSession } from '../../session/useSession'
import { LEGAL_PATHS } from '../legal/paths'
import { AuthLayout } from './AuthLayout'
import { ConsentPanel } from './ConsentPanel'
import { NO_ERRORS, registerErrors, type FormErrors } from './errors'
import { consentDocument } from './legalDocuments'

/** Link that opens a legal text in ANOTHER tab, so what was typed here is kept. */
function NewTabLink({ href, children }: { href: string; children: string }) {
  return (
    <a href={href} target="_blank" rel="noopener noreferrer">
      {children} <span className="visually-hidden">{es.identity.consent.opensInNewTab}</span>
    </a>
  )
}

/** Public page /registro (spec 001, HU-02). Every rule is decided by the backend. */
export function RegisterScreen() {
  const session = useSession()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const returnTo = safeReturnTo(params.get('returnTo'))
  const legal = useApiResource('legal-current', (signal) => fetchLegalCatalog(signal))

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [accepted, setAccepted] = useState(false)
  const [errors, setErrors] = useState<FormErrors>(NO_ERRORS)
  const [submitting, setSubmitting] = useState(false)
  // Set once the account exists, so the "already signed in" redirect does not take over.
  const [leaving, setLeaving] = useState(false)

  const emailRef = useRef<HTMLInputElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)
  const consentRef = useRef<HTMLInputElement>(null)
  const generalRef = useRef<HTMLDivElement>(null)

  const catalog = legal.state.status === 'success' ? legal.state.data : null
  // What was not read cannot be accepted: no text, no sign-up.
  const readable = catalog !== null && consentDocument(catalog) !== null

  // After a failed send: focus goes to the first field with a problem, or to the general message.
  useEffect(() => {
    if (errors === NO_ERRORS) return
    const target =
      (errors.fields.email && emailRef.current) ||
      (errors.fields.password && passwordRef.current) ||
      (errors.fields.consent && consentRef.current) ||
      generalRef.current
    target?.focus()
  }, [errors])

  if (session.status === 'authenticated' && !leaving) {
    return <Navigate to={landingPathFor(session.user?.role)} replace />
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!catalog || !readable || submitting) return
    setSubmitting(true)
    setErrors(NO_ERRORS)
    try {
      await registerStudent({
        email,
        password,
        legalAccepted: accepted,
        legalVersion: catalog.version,
      })
      setLeaving(true)
      await session.refresh()
      navigate(returnTo ?? sessionPaths.home, { replace: true, state: { welcome: true } })
    } catch (error) {
      const failure = registerErrors(error)
      if (failure.legalOutdated) {
        // The text changed: load it again and ask for the acceptance again.
        setAccepted(false)
        legal.retry()
      }
      setErrors(failure)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthLayout title={es.identity.register.title} lead={es.identity.register.lead}>
      <form className="auth-form" onSubmit={onSubmit} noValidate aria-busy={submitting}>
        {errors.general && (
          <Notice tone="error" announce="alert" tabIndex={-1} ref={generalRef}>
            <p>{errors.general}</p>
          </Notice>
        )}

        <TextField
          ref={emailRef}
          label={es.identity.form.emailLabel}
          type="email"
          name="email"
          autoComplete="email"
          inputMode="email"
          autoCapitalize="off"
          spellCheck={false}
          maxLength={254}
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          error={
            errors.fields.email && (
              <>
                {errors.fields.email}
                {errors.emailTaken && (
                  <>
                    {' '}
                    <Link
                      to={
                        returnTo
                          ? `${sessionPaths.login}?returnTo=${encodeURIComponent(returnTo)}`
                          : sessionPaths.login
                      }
                    >
                      {es.identity.register.emailTakenAction}
                    </Link>
                  </>
                )}
              </>
            )
          }
        />

        <PasswordField
          ref={passwordRef}
          label={es.identity.form.passwordLabel}
          name="password"
          autoComplete="new-password"
          hint={es.identity.register.passwordHint}
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          error={errors.fields.password}
        />

        <ConsentPanel legal={legal.state} onRetry={legal.retry} />

        <CheckboxField
          ref={consentRef}
          name="legalAccepted"
          checked={accepted}
          disabled={!readable}
          onChange={(event) => setAccepted(event.target.checked)}
          hint={es.identity.form.newTabHint}
          error={errors.fields.consent}
        >
          {es.identity.consent.checkboxStart}
          <NewTabLink href={LEGAL_PATHS.terms}>{es.identity.consent.terms}</NewTabLink>
          {es.identity.consent.checkboxMiddle}
          <NewTabLink href={LEGAL_PATHS.privacy}>{es.identity.consent.privacy}</NewTabLink>
          {es.identity.consent.checkboxEnd}
        </CheckboxField>

        <Button type="submit" className="auth-form__submit" disabled={!readable || submitting}>
          {submitting ? es.identity.register.submitting : es.identity.register.submit}
        </Button>
        <p className="visually-hidden" role="status">
          {submitting ? es.identity.register.submitting : ''}
        </p>

        <p className="auth__alt">
          {es.identity.register.haveAccount}{' '}
          <Link
            to={
              returnTo
                ? `${sessionPaths.login}?returnTo=${encodeURIComponent(returnTo)}`
                : sessionPaths.login
            }
          >
            {es.identity.register.goToLogin}
          </Link>
        </p>
      </form>
    </AuthLayout>
  )
}
