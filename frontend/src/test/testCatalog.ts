import type { CookieCatalog } from '../features/cookies/catalog'

/** A catalog with one optional category, to prove that adding one needs no component change (002-17). */
export const TEST_CATALOG: CookieCatalog = {
  version: 2,
  categories: [
    { id: 'necessary', required: true, name: 'Necesarias', description: 'Imprescindibles.' },
    { id: 'analytics', required: false, name: 'Analítica', description: 'Mide el uso del sitio.' },
  ],
}
