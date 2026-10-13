import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { ApiError } from '../api/client'
import { fetchMe, logoutUser, PROBLEM_CODES, problemCode } from '../api/identity'
import { SessionContext, type SessionNotice, type SessionState } from './sessionContext'
import type { SessionUser } from '../api/identity'

/** A check made less than this many ms ago is trusted when the caller asks `onlyIfStale`. */
const FRESH_FOR_MS = 1500

interface Core {
  status: SessionState['status']
  user: SessionUser | null
  legalAcceptancePending: boolean
  notice: SessionNotice
}

type Update = (previous: Core) => Core

const INITIAL: Core = {
  status: 'loading',
  user: null,
  legalAcceptancePending: false,
  notice: null,
}

/** Asks the server who is signed in and returns the change to make (it changes nothing itself). */
async function checkSession(): Promise<Update> {
  try {
    const me = await fetchMe()
    return () => ({
      status: 'authenticated',
      user: { id: me.id, email: me.email, role: me.role },
      legalAcceptancePending: me.legalAcceptancePending,
      notice: null,
    })
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      const expired = problemCode(error) === PROBLEM_CODES.sessionExpired
      return (previous) => ({
        status: 'visitor',
        user: null,
        legalAcceptancePending: false,
        // Keep "signed out" if that is why they are here; an expired cookie replaces it.
        notice: expired ? 'expired' : previous.notice,
      })
    }
    // Could not check: do not pretend to know. A person already shown as signed in stays so.
    return (previous) =>
      previous.status === 'authenticated' ? previous : { ...previous, status: 'unavailable' }
  }
}

/**
 * Holds the session in memory only: nothing is written to localStorage or sessionStorage (the
 * real session is the HttpOnly cookie). On mount it asks `GET /auth/me`: 200 = signed in,
 * 401 = visitor (`session-expired` is remembered so the sign-in page can say so).
 */
export function SessionProvider({ children }: { children: ReactNode }) {
  const [core, setCore] = useState<Core>(INITIAL)
  // Only the answer of the LAST request counts (a slow old answer must not undo a new one).
  const lastRequest = useRef(0)
  const lastCheckedAt = useRef(0)

  const refresh = useCallback(({ onlyIfStale = false } = {}): Promise<void> => {
    if (onlyIfStale && Date.now() - lastCheckedAt.current < FRESH_FOR_MS) return Promise.resolve()
    const request = ++lastRequest.current
    lastCheckedAt.current = Date.now()
    return checkSession().then((update) => {
      if (request === lastRequest.current) setCore(update)
    })
  }, [])

  useEffect(() => {
    // The first check, when the app opens.
    void refresh()
  }, [refresh])

  const signOut = useCallback((): Promise<boolean> => {
    return logoutUser()
      .then(
        () => true,
        // 401 = there was no session anymore: the goal (being out) is already met.
        (error: unknown) => error instanceof ApiError && error.status === 401,
      )
      .then((done) => {
        if (!done) return false
        lastRequest.current += 1 // an older `me` answer must not bring the person back in
        lastCheckedAt.current = Date.now()
        setCore({
          status: 'visitor',
          user: null,
          legalAcceptancePending: false,
          notice: 'signedOut',
        })
        return true
      })
  }, [])

  const value = useMemo<SessionState>(
    () => ({
      ...core,
      isStudent: core.status === 'authenticated' && core.user?.role === 'STUDENT',
      refresh,
      signOut,
    }),
    [core, refresh, signOut],
  )

  return <SessionContext value={value}>{children}</SessionContext>
}
