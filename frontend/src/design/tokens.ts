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

/** Tones of the brand blue (hover step) and the alpha of its soft and line variants. */
export const blueTones = {
  hover: '#1D4ED8',
  softAlpha: 0.08,
  lineAlpha: 0.22,
} as const

/** Lighter steps of the same blue, used inside the DORA orb. */
export const orbColors = {
  highlight: '#FFFFFF',
  100: '#BFDBFE',
  300: '#93C5FD',
  400: '#60A5FA',
  500: '#2563EB',
  700: '#1D4ED8',
} as const

/** Blue-gray neutrals. */
export const neutralColors = {
  50: '#F6F8FB',
  100: '#EDF1F7',
  200: '#E3E8F1',
  300: '#C3CCDB',
  400: '#8D99B0',
  500: '#5F6B85',
  600: '#475269',
  700: '#343D52',
} as const

/** Light grays for text on navy. */
export const onDarkColors = {
  soft: '#CFD6E6',
  muted: '#B9C3DA',
} as const

/** Only for success, warning and error states. */
export const stateColors = {
  success: '#14804A',
  successSoft: '#DCFCE7',
  warning: '#A15C00',
  warningSoft: '#FEF3C7',
  danger: '#B42318',
  dangerSoft: '#FEE2E2',
} as const

/**
 * Company avatar tones (no logos): background and text. The third tone is 14 % of the brand
 * blue over white; `avatarSoftAlpha` is that percentage.
 */
export const avatarTones = {
  softAlpha: 0.14,
  tones: [
    { background: brandColors.navy, text: brandColors.white },
    { background: brandColors.blue, text: brandColors.white },
    { background: '#E0E9FC', text: brandColors.navy },
  ],
} as const

/** Spacing scale in rem (4 px base: key N is N * 4 px). */
export const spacing = {
  0: 0,
  1: 0.25,
  2: 0.5,
  3: 0.75,
  4: 1,
  5: 1.25,
  6: 1.5,
  7: 1.75,
  8: 2,
  9: 2.25,
  10: 2.5,
  11: 2.75,
  12: 3,
  14: 3.5,
  16: 4,
  19: 4.75,
  24: 6,
  26: 6.5,
} as const

/** Radii in rem (`full` is a pixel value for pills and circles). */
export const radii = {
  xs: 0.5,
  sm: 0.75,
  button: 0.875,
  md: 1,
  lg: 1.75,
  full: 9999,
} as const

/** Motion durations in milliseconds. Use 0 when the user asks to reduce motion. */
export const durations = {
  fast: 150,
  base: 200,
  slow: 300,
  enter: 450,
  panel: 500,
  ring: 1000,
} as const

/** Count-up of the number inside the compatibility ring (slightly shorter than the arc fill). */
export const ringCountDuration = 900

/** Minimum size of any button or control a finger must reach, in rem (44 px). */
export const touchTargetMin = 2.75

/**
 * The three widths that must be reviewed on every screen (docs/diseno.md, section 5).
 */
export const reviewWidths = {
  phone: 360,
  tablet: 768,
  desktop: 1280,
} as const

/**
 * Breakpoints used by the CSS (mobile first, `min-width`), in px. Media queries cannot read
 * CSS variables, so the CSS writes them in rem (px / 16).
 */
export const breakpoints = {
  gutter: 520,
  menu: 860,
  columns: 980,
} as const
