import { describe, expect, it } from 'vitest'
import { contrastRatio, overlay } from './color'
import {
  avatarTones,
  brandColors,
  neutralColors,
  onDarkColors,
  orbColors,
  stateColors,
} from './tokens'

const { navy, blue, white } = brandColors
const blueSoft = overlay(blue, 0.08, white)
const navyOverlay = overlay(white, 0.08, navy)
const AA = 4.5

/** Every pair of text and background used by the interface (003-02). */
const pairs: ReadonlyArray<readonly [name: string, text: string, background: string]> = [
  ['white on blue (primary button)', white, blue],
  ['white on blue hover', white, orbColors[700]],
  ['navy on white (main text)', navy, white],
  ['navy on soft blue', navy, blueSoft],
  ['secondary text on white', neutralColors[600], white],
  ['secondary text on soft blue', neutralColors[600], blueSoft],
  ['blue on white (links)', blue, white],
  ['blue on soft blue (DORA label)', blue, blueSoft],
  ['success on white', stateColors.success, white],
  ['warning on white', stateColors.warning, white],
  ['danger on white', stateColors.danger, white],
  ['light gray on navy', onDarkColors.soft, navy],
  ['muted light gray on navy', onDarkColors.muted, navy],
  ['muted light gray on translucent white over navy', onDarkColors.muted, navyOverlay],
  ['soft light gray on translucent white over navy', onDarkColors.soft, navyOverlay],
  ['light blue #93C5FD on navy (DORA label)', orbColors[300], navy],
  ['white on navy', white, navy],
  ['navy on white (selected DORA state)', navy, white],
]

// 004-16: the text of every company avatar tone reaches 4.5:1.
const avatarPairs = avatarTones.tones.map(
  (tone, index) => [`avatar tone ${index + 1}`, tone.text, tone.background] as const,
)

describe('Color contrast (003-02)', () => {
  it.each(pairs)('%s reaches 4.5:1', (_name, text, background) => {
    expect(contrastRatio(text, background)).toBeGreaterThanOrEqual(AA)
  })

  it.each(avatarPairs)('%s (004-16) reaches 4.5:1', (_name, text, background) => {
    expect(contrastRatio(text, background)).toBeGreaterThanOrEqual(AA)
  })

  it('computes the soft avatar tone as the brand blue over white', () => {
    expect(avatarTones.tones[2].background).toBe(overlay(blue, avatarTones.softAlpha, white))
  })

  it('computes known reference values', () => {
    expect(contrastRatio(white, white)).toBeCloseTo(1, 5)
    expect(contrastRatio('#000000', white)).toBeCloseTo(21, 5)
  })
})
