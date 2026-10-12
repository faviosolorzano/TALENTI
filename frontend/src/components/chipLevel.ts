/** Level of a skill chip: 0 "No lo sé", 1 "Lo básico", 2 "Lo domino". */
export type ChipLevel = 0 | 1 | 2

/** The cycle of a chip: none, basic, mastered, none... */
export function nextLevel(level: ChipLevel): ChipLevel {
  return ((level + 1) % 3) as ChipLevel
}
