/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { brandColors, neutralColors, reviewWidths, stateColors } from './tokens'

const tokensCss = readFileSync(join(import.meta.dirname, 'tokens.css'), 'utf-8')

/** Reads the value of a CSS variable declared in tokens.css. */
function cssValue(name: string): string | undefined {
  return tokensCss
    .match(new RegExp(`${name}:\\s*([^;]+);`))?.[1]
    ?.trim()
    .toUpperCase()
}

describe('Design tokens', () => {
  it('define exactly the three brand colors in CSS and in TypeScript', () => {
    expect(cssValue('--color-brand-navy')).toBe(brandColors.navy)
    expect(cssValue('--color-brand-blue')).toBe(brandColors.blue)
    expect(cssValue('--color-brand-white')).toBe(brandColors.white)
    expect(Object.keys(brandColors)).toHaveLength(3)
  })

  it('keep the neutral colors in CSS and in TypeScript in sync', () => {
    for (const [step, hex] of Object.entries(neutralColors)) {
      expect(cssValue(`--color-neutral-${step}`)).toBe(hex)
    }
  })

  it('keep the state colors in CSS and in TypeScript in sync', () => {
    expect(cssValue('--color-success')).toBe(stateColors.success)
    expect(cssValue('--color-success-soft')).toBe(stateColors.successSoft)
    expect(cssValue('--color-warning')).toBe(stateColors.warning)
    expect(cssValue('--color-warning-soft')).toBe(stateColors.warningSoft)
    expect(cssValue('--color-danger')).toBe(stateColors.danger)
    expect(cssValue('--color-danger-soft')).toBe(stateColors.dangerSoft)
  })

  it('document the three review widths: 360, 768 and 1280 px', () => {
    expect(reviewWidths).toEqual({ phone: 360, tablet: 768, desktop: 1280 })
  })

  it('collapse the motion durations when the user prefers reduced motion', () => {
    expect(tokensCss).toMatch(/@media \(prefers-reduced-motion: reduce\)/)
  })

  it('derive the shadows from the navy token and define the border width', () => {
    expect(cssValue('--border-width')).toBe('1PX')
    for (const name of ['--shadow-sm', '--shadow-md', '--shadow-lg']) {
      expect(cssValue(name)).toContain('VAR(--COLOR-BRAND-NAVY)')
    }
    expect(tokensCss).not.toMatch(/rgb\(\s*11\s+19\s+43/i)
  })
})
