/**
 * Leaves the screen with a full page load. Used after accepting the new documents and after
 * signing out: the whole app then starts again from what the server says (who is signed in and
 * whether anything is still pending), so no screen keeps a stale copy of the session. Tests
 * replace this function.
 */
export function leaveTo(path: string): void {
  window.location.assign(path)
}
