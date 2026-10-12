/**
 * Hook point for the session state (spec 004, doubt 10).
 *
 * The identity screens (S1 frontend) do not exist yet, so for now EVERY visitor is treated as
 * a visitor without a session. When that slice arrives it replaces the body of this hook and
 * nothing else in the vacancy screens changes.
 */
export interface SessionState {
  /** True when a student is signed in. */
  isStudent: boolean
}

export function useSession(): SessionState {
  return { isStudent: false }
}
