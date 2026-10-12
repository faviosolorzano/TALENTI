/**
 * User-facing texts (Spanish). Components must not contain literal texts:
 * they read them from here.
 */
export const es = {
  app: {
    name: 'Talenti',
    logoAlt: 'Talenti',
    skipToContent: 'Saltar al contenido',
    mainNavLabel: 'Navegación principal',
  },
  nav: {
    home: 'Inicio',
    register: 'Crear cuenta',
    login: 'Ingresar',
    dashboard: 'Mi panel',
  },
  placeholder: {
    underConstruction: 'En construcción',
    description: 'Esta pantalla todavía no está lista. Estamos trabajando en ella.',
  },
  pages: {
    home: { title: 'Inicio' },
    register: { title: 'Crear cuenta' },
    login: { title: 'Ingresar' },
    dashboard: { title: 'Mi panel' },
    notFound: {
      title: 'No encontramos esta página',
      description: 'Revisa la dirección o vuelve al inicio.',
      backHome: 'Volver al inicio',
    },
  },
  errors: {
    generic: 'Algo salió mal. Inténtalo de nuevo.',
    network: 'No pudimos conectarnos. Revisa tu conexión e inténtalo de nuevo.',
  },
} as const
