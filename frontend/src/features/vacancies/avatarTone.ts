/** Number of avatar tones defined in the design tokens (`--color-avatar-N-*`). */
export const AVATAR_TONES = 3

/** Two letters for the avatar: the first letters of the first two words, or the first two. */
export function companyInitials(name: string): string {
  const words = name.trim().split(/\s+/).filter(Boolean)
  const letters =
    words.length >= 2
      ? [Array.from(words[0] ?? '')[0], Array.from(words[1] ?? '')[0]]
      : Array.from(words[0] ?? '').slice(0, 2)
  const text = letters.filter(Boolean).join('')
  return text ? text.toLocaleUpperCase('es') : '?'
}

/**
 * Which of the three tones a company uses. It depends only on the name, so the same company
 * always has the same color.
 */
export function companyTone(name: string): number {
  let hash = 5381
  for (const char of name.trim().toLocaleLowerCase('es')) {
    hash = (hash * 33 + (char.codePointAt(0) ?? 0)) % 2_147_483_647
  }
  return hash % AVATAR_TONES
}
