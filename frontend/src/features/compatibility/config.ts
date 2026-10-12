export interface CompatibilityConfig {
  /** Points of weight of each component. They add up to 100. */
  weights: {
    required: number
    desirable: number
    conditions: number
    similarity: number
  }
  /** Highest percentage when a required requirement is completely missing. */
  cap: number
  /** Credit of a requirement the profile has below the minimum level. */
  partialCredit: number
  /** Cosine similarity mapped to 0 (at `low` or less) and 1 (at `high` or more). */
  cosine: { low: number; high: number }
  /** Fewer evaluable points of weight than this means "insufficient information" (RN-02). */
  minEvaluableWeight: number
  /** Thresholds of the demo verdict (the real ones are defined in S4). */
  verdict: { strong: number; partial: number }
}

/**
 * Parameters of the DEMONSTRATION compatibility calculation (RN-01, RN-02, RN-14).
 * They live here, not inside the function, so they can be calibrated and shown on screen.
 * The real formula runs in the backend (S4); these values only feed the landing page card.
 */
export const COMPATIBILITY_CONFIG: CompatibilityConfig = {
  weights: { required: 50, desirable: 15, conditions: 20, similarity: 15 },
  cap: 69,
  partialCredit: 0.5,
  cosine: { low: 0.2, high: 0.6 },
  minEvaluableWeight: 50,
  verdict: { strong: 80, partial: 60 },
}
