import type { CompatibilityInput } from './computeCompatibility'

/**
 * EXAMPLE data for the landing page card (vacancy "Practicante Backend Java"). It is not a
 * real vacancy and it is different from the seed data of S2.
 * Chip levels: 0 "No lo sé", 1 "Lo básico", 2 "Lo domino".
 */
export type DemoSkillId = 'java' | 'spring' | 'sql' | 'git' | 'docker'

export const DEMO_REQUIREMENTS: CompatibilityInput['requirements'] = [
  { id: 'java', type: 'required', minLevel: 2 },
  { id: 'spring', type: 'required', minLevel: 1 },
  { id: 'sql', type: 'required', minLevel: 2 },
  { id: 'git', type: 'desirable', minLevel: 1 },
  { id: 'docker', type: 'desirable', minLevel: 1 },
]

/** Remote vacancy, enough hours: conditions component is 1. */
export const DEMO_CONDITIONS = 1

/** Cosine similarity between the example projects and the vacancy description. */
export const DEMO_COSINE = 0.55

export const DEMO_INITIAL_LEVELS: Readonly<Record<DemoSkillId, number>> = {
  java: 2,
  spring: 2,
  sql: 1,
  git: 2,
  docker: 0,
}
