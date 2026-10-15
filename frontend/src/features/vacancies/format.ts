import { es } from '../../i18n/es'

const DATE_FORMAT = new Intl.DateTimeFormat('es-PE', {
  day: 'numeric',
  month: 'long',
  year: 'numeric',
})

const DAY_MS = 24 * 60 * 60 * 1000

function startOfDay(date: Date): number {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

/**
 * "Publicada hoy", "Publicada ayer" or "Publicada el 3 de octubre de 2026". A date in the
 * future is shown as "hoy" (spec 004, edge cases). `now` can be given for tests.
 */
export function publishedLabel(publishedAt: string, now: Date = new Date()): string {
  const published = new Date(publishedAt)
  if (Number.isNaN(published.getTime())) return ''
  const days = Math.round((startOfDay(now) - startOfDay(published)) / DAY_MS)
  if (days <= 0) return es.vacancies.publishedToday
  if (days === 1) return es.vacancies.publishedYesterday
  return es.vacancies.publishedOn(DATE_FORMAT.format(published))
}

/** "3 de octubre de 2026", or an empty text when the date is not valid. */
export function formatDate(iso: string): string {
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '' : DATE_FORMAT.format(date)
}
