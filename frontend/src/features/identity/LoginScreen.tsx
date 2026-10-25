import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router'
import { loginUser } from '../../api/identity'
import { Button } from '../../components/Button'
import { Notice } from '../../components/Notice'
import { PasswordField } from '../../components/PasswordField'
import { TextField } from '../../components/TextField'
import { es } from '../../i18n/es'
import { safeReturnTo } from '../../session/returnTo'
import { landingPathFor, RETURN_TO_PARAM, sessionPaths } from '../../session/sessionPaths'
import { useSession } from '../../session/useSession'
import { AuthLayout } from './AuthLayout'
import { loginErrors, NO_ERRORS, type FormErrors } from './errors'

type LoginNotice = 'expired' | 'signedOut'

/** The reason for being here, passed in the navigation state by the guard or by "Cerrar sesión". */
function noticeFrom(state: unknown): LoginNotice | null {
  const notice = (state as { notice?: unknown } | null)?.notice
  return notice === 'expired' || notice === 'signedOut' ? notice : null
}

/** Public page /ingresar (spec 001, HU-03). A wrong password and a locked account look the same. */
export function LoginScreen() {
  const session = useSession()
  const navigate = useNavigate()
  const location = useLocation()
  const [params] = useSearchParams()

  const returnTo = safeReturnTo(params.get(RETURN_TO_PARAM))
  // Without `returnTo` a student goes to the Home and the other roles to /panel (005-05).
  const destinationFor = (role: string | undefined) => returnTo ?? landingPathFor(role)
  const notice = noticeFrom(location.state)

  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [errors, setErrors] = useState<FormErrors>(NO_ERRORS)
  const [submitting, setSubmitting] = useState(false)
  // Set once the sign-in worked, so the "already signed in" redirect does not take over.
  const [leaving, setLeaving] = useState(false)

  const emailRef = useRef<HTMLInputElement>(null)
  const passwordRef = useRef<HTMLInputElement>(null)
  const generalRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (errors === NO_ERRORS) return
    const target =
      (errors.fields.email && emailRef.current) ||
      (errors.fields.password && passwordRef.current) ||
      generalRef.current
    target?.focus()
  }, [errors])

  if (session.status === 'authenticated' && !leaving) {
    return <Navigate to={destinationFor(session.user?.role)} replace />
  }

  async function onSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (submitting) return
    setSubmitting(true)
    setErrors(NO_ERRORS)
    try {
      const user = await loginUser({ email, password })
      setLeaving(true)
      await session.refresh()
      navigate(destinationFor(user.role), { replace: true })
    } catch (error) {
      setPassword('')
      setErrors(loginErrors(error))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <AuthLayout
      title={es.identity.login.title}
      lead={returnTo ? es.identity.login.continueHint : es.identity.login.lead}
    >
      {notice && (
        <Notice tone="info" announce="status">
          <p>{es.identity.login.notices[notice]}</p>
        </Notice>
      )}

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
          autoComplete="username"
          inputMode="email"
          autoCapitalize="off"
          spellCheck={false}
          maxLength={254}
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          error={errors.fields.email}
        />

        <PasswordField
          ref={passwordRef}
          label={es.identity.form.passwordLabel}
          name="password"
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
          error={errors.fields.password}
        />

        <Button type="submit" className="auth-form__submit" disabled={submitting}>
          {submitting ? es.identity.login.submitting : es.identity.login.submit}
        </Button>
        <p className="visually-hidden" role="status">
          {submitting ? es.identity.login.submitting : ''}
        </p>

        <p className="auth__alt">
          {es.identity.login.noAccount}{' '}
          <Link to={sessionPaths.register}>{es.identity.login.goToRegister}</Link>
        </p>
      </form>
    </AuthLayout>
  )
}
