/**
 * Design tokens for use from TypeScript (for example canvas drawing or tests).
 * The CSS variables in `tokens.css` are the source for styling; the values here must
 * match them (`tokens.test.ts` checks it). Components should prefer the CSS variables.
 */

export const brandColors = {
  navy: '#0B132B',
  blue: '#2563EB',
  white: '#FFFFFF',
} as const

/** Blue-gray neutrals. */
export const neutralColors = {
  50: '#F6F8FB',
  100: '#EDF1F7',
  200: '#DDE3EE',
  300: '#C3CCDB',
  400: '#8D99B0',
  500: '#5F6B85',
  600: '#475269',
  700: '#343D52',
} as const

/** Only for success, warning and error states. */
export const stateColors = {
  success: '#15803D',
  successSoft: '#DCFCE7',
  warning: '#B45309',
  warningSoft: '#FEF3C7',
  danger: '#B91C1C',
  dangerSoft: '#FEE2E2',
} as const

/** Spacing scale in rem (4 px base). */
export const spacing = {
  0: 0,
  1: 0.25,
  2: 0.5,
  3: 0.75,
  4: 1,
  5: 1.25,
  6: 1.5,
  8: 2,
  10: 2.5,
  12: 3,
  16: 4,
  24: 6,
} as const

/** Radii in rem (`full` is a pixel value for pills and circles). */
export const radii = {
  sm: 0.375,
  md: 0.625,
  lg: 1,
  full: 9999,
} as const

/** Motion durations in milliseconds. Use 0 when the user asks to reduce motion. */
export const durations = {
  fast: 150,
  base: 200,
  slow: 300,
} as const

/**
 * The three widths that must be reviewed on every screen (docs/diseno.md, section 5).
 * Styles are mobile first: `tablet` and `desktop` are also the `min-width` breakpoints
 * (48rem and 80rem in CSS).
 */
export const reviewWidths = {
  phone: 360,
  tablet: 768,
  desktop: 1280,
} as const
