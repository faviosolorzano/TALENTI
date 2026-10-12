/** Page numbers to draw: first, last, the current one and its neighbours; `null` is a gap. */
export function pageItems(page: number, totalPages: number): Array<number | null> {
  const wanted = new Set([1, totalPages, page - 1, page, page + 1])
  const numbers = [...wanted].filter((n) => n >= 1 && n <= totalPages).sort((a, b) => a - b)
  const items: Array<number | null> = []
  numbers.forEach((n, index) => {
    const previous = numbers[index - 1]
    if (previous !== undefined && n - previous > 1) items.push(null)
    items.push(n)
  })
  return items
}
