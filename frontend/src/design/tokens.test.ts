/// <reference types="node" />
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  avatarTones,
  blueTones,
  brandColors,
  controlColor,
  breakpoints,
  durations,
  neutralColors,
  onDarkColors,
  orbColors,
  radii,
  reviewWidths,
  spacing,
  stateColors,
  touchTargetMin,
} from './tokens'

const tokensCss = readFileSync(join(import.meta.dirname, 'tokens.css'), 'utf-8')

/** Reads the value of a CSS variable declared in tokens.css. */
function cssValue(name: string): string | undefined {
  return tokensCss
    .match(new RegExp(`${name}:\\s*([^;]+);`))?.[1]
    ?.trim()
    .toUpperCase()
}

/** Converts a rem value written in CSS ("0.75rem") into a number. */
function remValue(name: string): number {
  return Number.parseFloat(cssValue(name) ?? 'NaN')
}

describe('Design tokens', () => {
  it('keeps the control outline separate from decorative dividers', () => {
    expect(cssValue('--color-control')).toBe(controlColor)
    expect(cssValue('--color-border')).not.toBe(cssValue('--color-control'))
  })
  // 003-01
  it('define exactly the three brand colors in CSS and in TypeScript', () => {
    expect(cssValue('--color-brand-navy')).toBe(brandColors.navy)
    expect(cssValue('--color-brand-blue')).toBe(brandColors.blue)
    expect(cssValue('--color-brand-white')).toBe(brandColors.white)
    expect(Object.keys(brandColors)).toHaveLength(3)
  })

  it('define the tones of the brand blue: hover step, soft tint and line with transparency', () => {
    expect(cssValue('--color-brand-blue-hover')).toBe(blueTones.hover)
    expect(cssValue('--color-brand-blue-soft')).toContain('VAR(--COLOR-BRAND-BLUE) 8%')
    expect(cssValue('--color-brand-blue-line')).toContain('VAR(--COLOR-BRAND-BLUE) 22%')
    expect(blueTones.softAlpha).toBe(0.08)
    expect(blueTones.lineAlpha).toBe(0.22)
  })

  it('define the orb tones in CSS and in TypeScript', () => {
    expect(cssValue('--color-orb-100')).toBe(orbColors[100])
    expect(cssValue('--color-orb-300')).toBe(orbColors[300])
    expect(cssValue('--color-orb-400')).toBe(orbColors[400])
    expect(orbColors[700]).toBe(blueTones.hover)
    expect(orbColors[500]).toBe(brandColors.blue)
    expect(orbColors.highlight).toBe(brandColors.white)
  })

  it('keep the neutral colors in CSS and in TypeScript in sync', () => {
    for (const [step, hex] of Object.entries(neutralColors)) {
      expect(cssValue(`--color-neutral-${step}`)).toBe(hex)
    }
  })

  it('keep the company avatar tones in sync (004-16)', () => {
    const [first, second] = avatarTones.tones
    expect(cssValue('--color-avatar-1-bg')).toBe('VAR(--COLOR-BRAND-NAVY)')
    expect(cssValue('--color-avatar-2-bg')).toBe('VAR(--COLOR-BRAND-BLUE)')
    expect(cssValue('--color-avatar-3-bg')).toContain('VAR(--COLOR-BRAND-BLUE) 14%')
    expect(first.background).toBe(brandColors.navy)
    expect(second.background).toBe(brandColors.blue)
    expect(avatarTones.tones).toHaveLength(3)
  })

  it('keep the light grays used on navy in sync', () => {
    expect(cssValue('--color-on-dark-soft')).toBe(onDarkColors.soft)
    expect(cssValue('--color-on-dark-muted')).toBe(onDarkColors.muted)
  })

  it('keep the state colors in CSS and in TypeScript in sync, with the values of the sample', () => {
    expect(cssValue('--color-success')).toBe(stateColors.success)
    expect(cssValue('--color-success-soft')).toBe(stateColors.successSoft)
    expect(cssValue('--color-warning')).toBe(stateColors.warning)
    expect(cssValue('--color-warning-soft')).toBe(stateColors.warningSoft)
    expect(cssValue('--color-danger')).toBe(stateColors.danger)
    expect(cssValue('--color-danger-soft')).toBe(stateColors.dangerSoft)
    expect(stateColors.success).toBe('#14804A')
    expect(stateColors.warning).toBe('#A15C00')
    expect(stateColors.danger).toBe('#B42318')
  })

  it('keep the spacing scale and the radii in sync', () => {
    for (const [step, rem] of Object.entries(spacing)) {
      if (step === '0') continue
      expect(remValue(`--space-${step}`)).toBe(rem)
    }
    for (const [name, rem] of Object.entries(radii)) {
      if (name === 'full') continue
      expect(remValue(`--radius-${name}`)).toBe(rem)
    }
    expect(cssValue('--radius-full')).toBe(`${radii.full}PX`)
  })

  it('keep the motion durations and the easing curve of the sample', () => {
    for (const [name, ms] of Object.entries(durations)) {
      expect(cssValue(`--duration-${name}`)).toBe(`${ms}MS`)
    }
    expect(cssValue('--easing-standard')).toBe('CUBIC-BEZIER(0.2, 0.8, 0.2, 1)')
  })

  it('define the fluid type sizes of the sample', () => {
    expect(cssValue('--font-size-display')).toContain('CLAMP(2.375REM, 5.3VW, 4.125REM)')
    expect(cssValue('--font-size-title')).toContain('CLAMP(2.125REM, 4.6VW, 3.5REM)')
    expect(cssValue('--font-size-step')).toContain('CLAMP(1.5REM, 2.4VW, 2REM)')
    expect(cssValue('--font-size-lead')).toContain('CLAMP(1.125REM, 1.6VW, 1.3125REM)')
  })

  it('declare the self-hosted font families with a fallback stack', () => {
    expect(cssValue('--font-family-base')).toContain("'HANKEN GROTESK'")
    expect(cssValue('--font-family-heading')).toContain("'HANKEN GROTESK'")
    expect(cssValue('--font-family-base')).toContain('SANS-SERIF')
    expect(cssValue('--font-family-heading')).toContain('SANS-SERIF')
  })

  it('document the three review widths: 360, 768 and 1280 px', () => {
    expect(reviewWidths).toEqual({ phone: 360, tablet: 768, desktop: 1280 })
  })

  it('document the breakpoints (menu at 860 px, columns at 980 px) and keep them in the CSS', () => {
    expect(breakpoints.menu).toBe(860)
    expect(breakpoints.columns).toBe(980)
    expect(tokensCss).toContain(`@media (min-width: ${breakpoints.gutter / 16}rem)`)
    expect(tokensCss).toContain(`@media (min-width: ${breakpoints.columns / 16}rem)`)
  })

  it('make every touch target at least 44 px', () => {
    expect(touchTargetMin).toBe(2.75)
    expect(remValue('--touch-target-min')).toBe(touchTargetMin)
  })

  it('collapse the motion durations when the user prefers reduced motion', () => {
    expect(tokensCss).toMatch(/@media \(prefers-reduced-motion: reduce\)/)
    const reduced = tokensCss.split('@media (prefers-reduced-motion: reduce)')[1] ?? ''
    for (const name of Object.keys(durations)) {
      expect(reduced).toContain(`--duration-${name}: 0.01ms`)
    }
  })

  it('derive the shadows from the brand tokens and define the border width', () => {
    expect(cssValue('--border-width')).toBe('1PX')
    for (const name of [
      '--shadow-sm',
      '--shadow-md',
      '--shadow-lg',
      '--shadow-card',
      '--shadow-action',
      '--shadow-action-hover',
    ]) {
      expect(cssValue(name)).toMatch(/VAR\(--COLOR-BRAND-(NAVY|BLUE)\)/)
    }
    expect(tokensCss).not.toMatch(/rgb\(\s*11\s+19\s+43/i)
  })
})
