import type { TypedNumber } from '../../api/profile'

/**
 * What goes to the server for a number field: nothing as `null`, a whole number as a number,
 * and anything else as the typed text, so that the server (not the page) says it is invalid.
 */
export function typedNumber(text: string): TypedNumber {
  const clean = text.trim()
  if (clean === '') return null
  return /^\d{1,9}$/.test(clean) ? Number(clean) : clean
}
