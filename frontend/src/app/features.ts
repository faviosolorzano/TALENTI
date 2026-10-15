/**
 * Which slices are already part of the app (spec 005, section 4). Until a slice is integrated,
 * the Home block and the menu entry that depend on it say "Próximamente" and call no API.
 * Each slice sets its constant to `true` when it is integrated; once all are, this file goes.
 */
export const features = {
  /** Spec 006: the profile. */
  profile: true,
  /** Spec 007: compatibility (turns "Vacantes destacadas" into "Vacantes para ti"). */
  compatibility: false,
  /** Spec 008: applications. */
  applications: true,
  /** Spec S8: practice sessions with DORA. */
  practice: false,
} as const
