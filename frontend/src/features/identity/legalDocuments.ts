import type { LegalCatalog } from '../../api/legal'

/** The document of the data consent for the account (`consent-account`), if it came. */
export function consentDocument(catalog: LegalCatalog) {
  return catalog.documents.find((document) => document.key === 'consent-account') ?? null
}
