import { ApiError } from '../../api/client'
import { fieldProblems, PROBLEM_CODES, problemCode } from '../../api/identity'
import { es } from '../../i18n/es'

export type RegisterField = 'email' | 'password' | 'consent'

export interface FormErrors {
  /** A message about the whole form. */
  general: string | null
  /** A message under each field. */
  fields: Partial<Record<RegisterField, string>>
  /** The e-mail is already registered: the screen offers to sign in. */
  emailTaken?: boolean
  /** The legal documents changed: the screen reloads them. */
  legalOutdated?: boolean
}

export const NO_ERRORS: FormErrors = { general: null, fields: {} }

/** The field of the screen that a backend field name belongs to. */
function screenField(backendField: string): RegisterField | null {
  switch (backendField) {
    case 'email':
      return 'email'
    case 'password':
      return 'password'
    case 'legalAccepted':
    case 'legalVersion':
      return 'consent'
    default:
      return null
  }
}

/** The 422 messages written by the backend, grouped by the field of the screen. */
function fromValidation(error: unknown): FormErrors {
  const fields: FormErrors['fields'] = {}
  for (const problem of fieldProblems(error)) {
    const field = screenField(problem.field)
    if (!field) continue
    const message = problem.message.trim() || es.identity.form.fieldInvalid
    const previous = fields[field]
    if (!previous) fields[field] = message
    else if (!previous.includes(message)) fields[field] = `${previous} ${message}`
  }
  const hasFields = Object.keys(fields).length > 0
  return { general: hasFields ? null : es.identity.form.reviewFields, fields }
}

function transport(error: unknown): FormErrors | null {
  if (!(error instanceof ApiError)) return { general: es.errors.generic, fields: {} }
  if (error.status === 0) return { general: es.errors.network, fields: {} }
  return null
}

/** What to show for a failed `POST /auth/register` (001-02 to 001-05). */
export function registerErrors(error: unknown): FormErrors {
  const failed = transport(error)
  if (failed) return failed
  const status = (error as ApiError).status
  const code = problemCode(error)
  if (status === 409 && code === PROBLEM_CODES.emailAlreadyRegistered) {
    return { general: null, fields: { email: es.identity.register.emailTaken }, emailTaken: true }
  }
  if (status === 409 && code === PROBLEM_CODES.legalVersionOutdated) {
    return { general: es.identity.register.versionOutdated, fields: {}, legalOutdated: true }
  }
  if (status === 422) return fromValidation(error)
  if (status === 429) return { general: es.identity.register.tooManyRequests, fields: {} }
  return { general: es.errors.generic, fields: {} }
}

/**
 * What to show for a failed `POST /auth/login`. A wrong password, an unknown e-mail and a locked
 * account all answer 401 with the same text (001-09, 001-10): nothing here can tell them apart.
 */
export function loginErrors(error: unknown): FormErrors {
  const failed = transport(error)
  if (failed) return failed
  const status = (error as ApiError).status
  if (status === 401) return { general: es.identity.login.invalidCredentials, fields: {} }
  if (status === 429) return { general: es.identity.login.tooManyRequests, fields: {} }
  if (status === 422) return fromValidation(error)
  return { general: es.errors.generic, fields: {} }
}
