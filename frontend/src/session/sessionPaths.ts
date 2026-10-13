/**
 * Routes that the session logic needs to know. Each address is written ONCE, here (the legal
 * slice re-exports `acceptDocuments` as `ACCEPT_DOCUMENTS_PATH`; the legal pages live in
 * `features/legal/paths.ts`).
 */
export const sessionPaths = {
  home: '/',
  opportunities: '/oportunidades',
  register: '/registro',
  login: '/ingresar',
  recover: '/recuperar-cuenta',
  dashboard: '/panel',
  /** Screen to accept a newer version of the legal documents (HU-33, spec 002). */
  acceptDocuments: '/aceptar-documentos',
} as const

/** Query parameter that carries the page to go back to after signing in or accepting. */
export const RETURN_TO_PARAM = 'returnTo'
