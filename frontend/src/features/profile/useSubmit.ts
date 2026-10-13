import { useCallback, useRef, useState } from 'react'
import { ApiError } from '../../api/client'
import { fieldProblems, type FieldProblem } from '../../api/identity'
import type { Profile } from '../../api/profile'
import { es } from '../../i18n/es'
import { useSession } from '../../session/useSession'

/**
 * What a section save looks like for the screen (006-19):
 * - `saving`: the button says "Guardando…" and is disabled.
 * - `invalid`: the server answered 422 with a rule broken in some fields.
 * - `failed`: network or server error; what was typed stays and "Reintentar" repeats the save.
 */
export type SubmitState =
  | { status: 'idle' }
  | { status: 'saving' }
  | { status: 'invalid'; problems: FieldProblem[] }
  | { status: 'failed'; message: string }

const IDLE: SubmitState = { status: 'idle' }

/** The text of a failure that is not about a field. */
function failureMessage(error: unknown): string {
  if (error instanceof ApiError && error.status === 0) return es.errors.network
  return es.profile.saveFailed
}

/**
 * Runs the save of one section and tracks its state. A 401 asks the session again: if the
 * server says it expired, the guard sends the person to /ingresar with "Tu sesión expiró"
 * (001-14); what was typed is not kept anywhere else.
 */
export function useSubmit(onSaved: (profile: Profile) => void) {
  const session = useSession()
  const { refresh } = session
  const [state, setState] = useState<SubmitState>(IDLE)
  // A double click or Enter twice must send only once.
  const busy = useRef(false)

  const run = useCallback(
    async (action: () => Promise<Profile>) => {
      if (busy.current) return
      busy.current = true
      setState({ status: 'saving' })
      try {
        const profile = await action()
        setState(IDLE)
        onSaved(profile)
      } catch (error) {
        if (error instanceof ApiError && error.status === 401) void refresh()
        const problems = fieldProblems(error)
        setState(
          problems.length > 0
            ? { status: 'invalid', problems }
            : { status: 'failed', message: failureMessage(error) },
        )
      } finally {
        busy.current = false
      }
    },
    [onSaved, refresh],
  )

  return { state, run, saving: state.status === 'saving' }
}

/**
 * The messages the server wrote for a field, joined. `field` also matches its items:
 * "technologies" gets the problems of "technologies[2]".
 */
export function messageFor(state: SubmitState, field: string): string | undefined {
  if (state.status !== 'invalid') return undefined
  const messages = state.problems
    .filter((item) => item.field === field || item.field.startsWith(`${field}[`))
    .map((item) => item.message.trim() || es.identity.form.fieldInvalid)
  const unique = [...new Set(messages)]
  return unique.length > 0 ? unique.join(' ') : undefined
}

/** Messages of problems whose field the form does not draw, so none is lost. */
export function otherMessages(state: SubmitState, fields: readonly string[]): string[] {
  if (state.status !== 'invalid') return []
  const known = (name: string) =>
    fields.some((field) => name === field || name.startsWith(`${field}[`))
  return [
    ...new Set(
      state.problems
        .filter((item) => !known(item.field))
        .map((item) => item.message.trim() || es.identity.form.fieldInvalid),
    ),
  ]
}
