/// <reference types="node" />
import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, relative, sep } from 'node:path'
import { describe, expect, it } from 'vitest'
import { COMPATIBILITY_CONFIG } from './config'
import { computeCompatibility, type CompatibilityInput } from './computeCompatibility'
import { DEMO_COSINE, DEMO_CONDITIONS, DEMO_REQUIREMENTS } from './demoVacancy'

/** Example of RN-01: Java 2/min 2, Spring Boot 1/min 1, SQL 1/min 2, Git yes, Docker no. */
const rn01Example: CompatibilityInput = {
  requirements: [
    { id: 'java', type: 'required', minLevel: 2 },
    { id: 'spring', type: 'required', minLevel: 1 },
    { id: 'sql', type: 'required', minLevel: 2 },
    { id: 'git', type: 'desirable', minLevel: 1 },
    { id: 'docker', type: 'desirable', minLevel: 1 },
  ],
  levels: { java: 2, spring: 1, sql: 1, git: 1, docker: 0 },
  conditions: 1,
  cosine: 0.55,
}

describe('computeCompatibility', () => {
  // 003-10
  it('returns 82 for the example of RN-01, with A = 0.833, B = 0.5 and D = 0.875', () => {
    const result = computeCompatibility(rn01Example)

    expect(result.percent).toBe(82)
    expect(result.insufficient).toBe(false)
    expect(result.capped).toBe(false)
    expect(result.components.required).toBeCloseTo(0.833, 3)
    expect(result.components.desirable).toBeCloseTo(0.5, 5)
    expect(result.components.conditions).toBe(1)
    expect(result.components.similarity).toBeCloseTo(0.875, 5)
  })

  it('gives 82 with the initial levels of the landing card (Spring Boot "Lo domino")', () => {
    const result = computeCompatibility({
      requirements: DEMO_REQUIREMENTS,
      levels: { java: 2, spring: 2, sql: 1, git: 2, docker: 0 },
      conditions: DEMO_CONDITIONS,
      cosine: DEMO_COSINE,
    })
    expect(result.percent).toBe(82)
  })

  // 003-11
  it('caps the result at 69 when a required requirement is at level 0 (raw 81)', () => {
    const result = computeCompatibility({
      ...rn01Example,
      levels: { java: 0, spring: 2, sql: 2, git: 2, docker: 2 },
    })

    expect(result.percent).toBe(69)
    expect(result.capped).toBe(true)
  })

  it('does not cap when a required requirement is only below the minimum (credit 0.5)', () => {
    const result = computeCompatibility({
      ...rn01Example,
      levels: { java: 1, spring: 2, sql: 2, git: 2, docker: 2 },
    })

    expect(result.capped).toBe(false)
    expect(result.credits.find((c) => c.id === 'java')?.credit).toBe(0.5)
    expect(result.percent).toBeGreaterThan(69)
  })

  it('reports the credit of every requirement: 1, 0.5 or 0', () => {
    const { credits } = computeCompatibility(rn01Example)
    expect(credits.map((c) => [c.id, c.credit])).toEqual([
      ['java', 1],
      ['spring', 1],
      ['sql', 0.5],
      ['git', 1],
      ['docker', 0],
    ])
  })

  // 003-12
  it('returns percent null and insufficient when the profile has no skill', () => {
    const result = computeCompatibility({
      ...rn01Example,
      levels: { java: 0, spring: 0, sql: 0, git: 0, docker: 0 },
    })

    expect(result.percent).toBeNull()
    expect(result.insufficient).toBe(true)
    expect(result.capped).toBe(false)
  })

  it('returns insufficient when the evaluable components weigh less than 50 points (RN-02)', () => {
    const result = computeCompatibility({
      requirements: [{ id: 'git', type: 'desirable', minLevel: 1 }],
      levels: { git: 1 },
      conditions: 1,
      cosine: null,
    })

    expect(result.evaluatedWeight).toBe(35)
    expect(result.percent).toBeNull()
    expect(result.insufficient).toBe(true)
  })

  it('readjusts the weights when the evaluable components reach 50 points or more', () => {
    const result = computeCompatibility({ ...rn01Example, conditions: null, cosine: null })

    // A and B only: (0.8333 x 50 + 0.5 x 15) / 65 = 0.7564 -> 76
    expect(result.evaluatedWeight).toBe(65)
    expect(result.percent).toBe(76)
    expect(result.insufficient).toBe(false)
  })

  it('computes with exactly 50 points of weight', () => {
    const result = computeCompatibility({
      requirements: [{ id: 'java', type: 'required', minLevel: 2 }],
      levels: { java: 2 },
      conditions: null,
      cosine: null,
    })

    expect(result.evaluatedWeight).toBe(50)
    expect(result.percent).toBe(100)
  })

  // 003-13
  it('gives the same result for the same data', () => {
    const first = computeCompatibility(rn01Example)
    const second = computeCompatibility(structuredClone(rn01Example))
    expect(second).toEqual(first)
  })

  it('ignores a name, an institute or any other attribute that is not part of the input', () => {
    const withExtras = {
      ...rn01Example,
      name: 'Ana Quispe',
      institute: 'Tecsup',
      gender: 'f',
    } as CompatibilityInput
    expect(computeCompatibility(withExtras)).toEqual(computeCompatibility(rn01Example))
  })

  it('keeps the parameters in a configuration constant: weights 50/15/20/15, cap 69, cosine 0.20/0.60, minimum 50', () => {
    expect(COMPATIBILITY_CONFIG.weights).toEqual({
      required: 50,
      desirable: 15,
      conditions: 20,
      similarity: 15,
    })
    expect(COMPATIBILITY_CONFIG.cap).toBe(69)
    expect(COMPATIBILITY_CONFIG.cosine).toEqual({ low: 0.2, high: 0.6 })
    expect(COMPATIBILITY_CONFIG.minEvaluableWeight).toBe(50)
  })

  it('reads the parameters from the configuration it receives', () => {
    const result = computeCompatibility(
      { ...rn01Example, levels: { java: 0, spring: 2, sql: 2, git: 2, docker: 2 } },
      { ...COMPATIBILITY_CONFIG, cap: 40 },
    )
    expect(result.percent).toBe(40)
  })

  it('keeps the similarity between 0 and 1 for any cosine', () => {
    const low = computeCompatibility({ ...rn01Example, cosine: -0.3 })
    const high = computeCompatibility({ ...rn01Example, cosine: 0.99 })
    expect(low.components.similarity).toBe(0)
    expect(high.components.similarity).toBe(1)
  })
})

describe('computeCompatibility is only for the landing page (003-13)', () => {
  const srcDir = join(import.meta.dirname, '..', '..')
  const walk = (dir: string): string[] =>
    readdirSync(dir).flatMap((name) => {
      const path = join(dir, name)
      return statSync(path).isDirectory() ? walk(path) : [path]
    })

  it('no file outside features/landing and features/compatibility imports the demo (007-17)', () => {
    const allowed = new Set(['landing', 'compatibility'])
    const importers = walk(srcDir)
      .filter((file) => /\.(ts|tsx)$/.test(file))
      .filter((file) => {
        const parts = relative(srcDir, file).split(sep)
        return !(parts[0] === 'features' && allowed.has(parts[1] ?? ''))
      })
      .filter((file) =>
        /from\s+['"][^'"]*compatibility\/(computeCompatibility|config|demoVacancy)/.test(
          readFileSync(file, 'utf-8'),
        ),
      )
      .filter((file) => !file.endsWith('computeCompatibility.test.ts'))
      .map((file) => relative(srcDir, file))

    expect(importers).toEqual([])
  })
})
