import { COMPATIBILITY_CONFIG, type CompatibilityConfig } from './config'

/**
 * DEMONSTRATION of the compatibility formula (RN-01 and RN-02), used only by the landing
 * page card with example data. The real percentage is calculated by the backend (S4); this
 * function must never show the percentage of a real vacancy (an ESLint rule and a test
 * keep it from being imported outside the landing page).
 *
 * Pure: same input, same output. It receives only skills, levels, conditions and the cosine
 * similarity: never a name, an institute or any protected attribute (RN-01, T3).
 */

export type RequirementType = 'required' | 'desirable'

export interface Requirement {
  id: string
  type: RequirementType
  /** Lowest level that fully meets the requirement (1 basic, 2 intermediate...). */
  minLevel: number
}

export interface CompatibilityInput {
  requirements: readonly Requirement[]
  /** Level of the profile in each requirement, by id. 0 or missing means "does not have it". */
  levels: Readonly<Record<string, number>>
  /** Component C (conditions) from 0 to 1, or null when it cannot be evaluated. */
  conditions: number | null
  /** Cosine similarity between the profile projects and the vacancy, or null if unknown. */
  cosine: number | null
}

export interface RequirementCredit {
  id: string
  type: RequirementType
  /** 1 meets the minimum, 0.5 has it below the minimum, 0 does not have it. */
  credit: number
}

export interface CompatibilityComponents {
  required: number | null
  desirable: number | null
  conditions: number | null
  similarity: number | null
}

export interface CompatibilityResult {
  /** Whole percentage, or null when the information is insufficient. */
  percent: number | null
  insufficient: boolean
  /** True when a required requirement is missing: the percentage cannot pass the cap. */
  capped: boolean
  components: CompatibilityComponents
  /** Points of weight of the components that could be evaluated. */
  evaluatedWeight: number
  credits: RequirementCredit[]
}

function clamp01(value: number): number {
  return Math.min(1, Math.max(0, value))
}

function creditFor(level: number, minLevel: number, config: CompatibilityConfig): number {
  if (level >= minLevel && level > 0) return 1
  if (level > 0) return config.partialCredit
  return 0
}

function average(values: readonly number[]): number | null {
  return values.length === 0 ? null : values.reduce((sum, v) => sum + v, 0) / values.length
}

export function computeCompatibility(
  input: CompatibilityInput,
  config: CompatibilityConfig = COMPATIBILITY_CONFIG,
): CompatibilityResult {
  const credits: RequirementCredit[] = input.requirements.map((req) => ({
    id: req.id,
    type: req.type,
    credit: creditFor(input.levels[req.id] ?? 0, req.minLevel, config),
  }))

  const hasAnySkill = Object.values(input.levels).some((level) => level > 0)
  const empty: CompatibilityComponents = {
    required: null,
    desirable: null,
    conditions: null,
    similarity: null,
  }
  if (!hasAnySkill) {
    return {
      percent: null,
      insufficient: true,
      capped: false,
      components: empty,
      evaluatedWeight: 0,
      credits,
    }
  }

  const creditsOf = (type: RequirementType) =>
    credits.filter((c) => c.type === type).map((c) => c.credit)

  const similarity =
    input.cosine === null
      ? null
      : clamp01((input.cosine - config.cosine.low) / (config.cosine.high - config.cosine.low))

  const components: CompatibilityComponents = {
    required: average(creditsOf('required')),
    desirable: average(creditsOf('desirable')),
    conditions: input.conditions === null ? null : clamp01(input.conditions),
    similarity,
  }

  let weighted = 0
  let evaluatedWeight = 0
  for (const key of Object.keys(config.weights) as Array<keyof CompatibilityComponents>) {
    const value = components[key]
    if (value === null) continue
    weighted += config.weights[key] * value
    evaluatedWeight += config.weights[key]
  }

  if (evaluatedWeight < config.minEvaluableWeight) {
    return {
      percent: null,
      insufficient: true,
      capped: false,
      components,
      evaluatedWeight,
      credits,
    }
  }

  const raw = Math.round((weighted / evaluatedWeight) * 100)
  const capped = credits.some((c) => c.type === 'required' && c.credit === 0)

  return {
    percent: capped ? Math.min(raw, config.cap) : raw,
    insufficient: false,
    capped,
    components,
    evaluatedWeight,
    credits,
  }
}
