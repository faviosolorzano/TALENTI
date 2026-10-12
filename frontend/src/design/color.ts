/** Color helpers that work on `#RRGGBB` strings. Used by the canvas drawing and by tests. */

export type Rgb = readonly [number, number, number]

export function hexToRgb(hex: string): Rgb {
  const match = /^#([0-9a-f]{6})$/i.exec(hex)
  if (!match?.[1]) throw new Error(`Invalid hex color: ${hex}`)
  const value = parseInt(match[1], 16)
  return [(value >> 16) & 255, (value >> 8) & 255, value & 255]
}

/** `rgba(...)` string for canvas gradients and strokes. */
export function withAlpha(hex: string, alpha: number): string {
  const [r, g, b] = hexToRgb(hex)
  return `rgba(${r}, ${g}, ${b}, ${alpha})`
}

/** Color of `foreground` drawn over `background` with the given opacity (0 to 1). */
export function overlay(foreground: string, opacity: number, background: string): string {
  const fg = hexToRgb(foreground)
  const bg = hexToRgb(background)
  const channels = fg.map((value, index) =>
    Math.round(value * opacity + (bg[index] ?? 0) * (1 - opacity)),
  )
  return `#${channels.map((c) => c.toString(16).padStart(2, '0')).join('')}`.toUpperCase()
}

function linearize(channel: number): number {
  const c = channel / 255
  return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4
}

/** WCAG relative luminance. */
export function relativeLuminance(hex: string): number {
  const [r, g, b] = hexToRgb(hex)
  return 0.2126 * linearize(r) + 0.7152 * linearize(g) + 0.0722 * linearize(b)
}

/** WCAG contrast ratio between two colors (1 to 21). */
export function contrastRatio(a: string, b: string): number {
  const la = relativeLuminance(a)
  const lb = relativeLuminance(b)
  return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05)
}
