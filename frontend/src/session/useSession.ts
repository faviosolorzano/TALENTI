import { useContext } from 'react'
import { SessionContext, type SessionState } from './sessionContext'

export type { SessionState, SessionStatus, SessionNotice } from './sessionContext'

/**
 * The session of the person using the app (spec 001). It comes from `SessionProvider`, which
 * asks the backend with `GET /auth/me`: the interface never decides who someone is.
 */
export function useSession(): SessionState {
  return useContext(SessionContext)
}
