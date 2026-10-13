import { es } from '../../i18n/es'

/** Name of the cookie where the visitor's choice is remembered (spec 002, section 5). */
export const COOKIE_NAME = 'talenti_cookies'

/** 12 months, in seconds (002-13). */
export const COOKIE_MAX_AGE_SECONDS = 31_536_000

/**
 * Version of the category catalog. Raise it whenever a category is added or changed: everyone who
 * chose with an older version is asked again (002-16). A new version of the Cookie Policy text
 * without a change in categories does NOT raise it.
 */
export const CATALOG_VERSION = 1

export interface CookieCategory {
  id: string
  /** A required category is always on and has no switch. */
  required: boolean
  /** Texts shown in the notice and the panel. */
  name: string
  description: string
}

export interface CookieCatalog {
  version: number
  categories: readonly CookieCategory[]
}

/**
 * Categories of this version: only the necessary ones (no analytics, advertising or tracking).
 * To add one, add it here with `required: false` and raise `CATALOG_VERSION`; the notice, the
 * panel and `isAllowed` need no change (002-17).
 */
export const CATALOG: CookieCatalog = {
  version: CATALOG_VERSION,
  categories: [
    {
      id: 'necessary',
      required: true,
      name: es.cookies.categories.necessary.name,
      description: es.cookies.categories.necessary.description,
    },
  ],
}
