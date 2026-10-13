import { createContext } from 'react'
import type { SessionUser } from '../api/identity'

/**
 * - `loading`: the first answer of `GET /auth/me` has not arrived.
 * - `visitor`: no session (401).
 * - `authenticated`: a person with a session; `user.role` says who.
 * - `unavailable`: the session could not be checked (network or server error).
 */
export type SessionStatus = 'loading' | 'visitor' | 'authenticated' | 'unavailable'

/** Why a visitor is at the sign-in page, when it is not their own doing. */
export type SessionNotice = 'expired' | 'signedOut' | null

export interface SessionState {
  status: SessionStatus
  user: SessionUser | null
  /** True when a student has a session. */
  isStudent: boolean
  /** The backend says a newer version of the legal documents must be accepted. */
  legalAcceptancePending: boolean
  notice: SessionNotice
  /** The last attempt to sign out failed (the person is still signed in). */
  signOutFailed: boolean
  /** Asks the server who is signed in. `onlyIfStale` skips the call if it was just made. */
  refresh: (options?: { onlyIfStale?: boolean }) => Promise<void>
  /** Signs out on the server. Resolves `true` when the person is now a visitor. */
  signOut: () => Promise<boolean>
}

/** Without a provider (isolated component tests) everybody is a visitor. */
export const visitorSession: SessionState = {
  status: 'visitor',
  user: null,
  isStudent: false,
  legalAcceptancePending: false,
  notice: null,
  signOutFailed: false,
  refresh: () => Promise.resolve(),
  signOut: () => Promise.resolve(true),
}

export const SessionContext = createContext<SessionState>(visitorSession)
