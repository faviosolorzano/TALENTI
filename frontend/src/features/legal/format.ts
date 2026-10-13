const dateFormat = new Intl.DateTimeFormat('es-PE', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  // The publication date is a calendar day (the backend sends midnight UTC): reading it in
  // another time zone would show the day before in Peru.
  timeZone: 'UTC',
})

/** "11 de octubre de 2026", or `null` when there is no date or it cannot be read. Never invents one. */
export function formatPublishedDate(value: string | null | undefined): string | null {
  if (!value) return null
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : dateFormat.format(date)
}
