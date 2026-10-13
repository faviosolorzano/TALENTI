/**
 * User-facing texts (Spanish). Components must not contain literal texts:
 * they read them from here. A few entries are small functions that fill in a value.
 */
export const es = {
  app: {
    name: 'Talenti',
    logoAlt: 'Talenti',
    homeLink: 'Talenti, inicio',
    skipToContent: 'Saltar al contenido',
    mainNavLabel: 'Navegación principal',
  },
  nav: {
    home: 'Inicio',
    opportunities: 'Oportunidades',
    how: 'Cómo funciona',
    dora: 'DORA',
    register: 'Crear cuenta',
    login: 'Ingresar',
    dashboard: 'Mi panel',
    menu: 'Menú',
  },
  placeholder: {
    underConstruction: 'En construcción',
    description: 'Esta pantalla todavía no está lista. Estamos trabajando en ella.',
  },
  pages: {
    home: { title: 'Inicio' },
    register: { title: 'Crear cuenta' },
    login: { title: 'Ingresar' },
    opportunities: { title: 'Oportunidades' },
    vacancy: { title: 'Vacante' },
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

  // ---- Reusable pieces ----
  ai: {
    label: 'DORA · inteligencia artificial',
  },
  badge: {
    required: 'obligatorio',
    desirable: 'deseable',
  },
  levels: {
    none: 'No lo sé',
    basic: 'Lo básico',
    mastered: 'Lo domino',
  },
  levelChip: {
    /** Example: "Java, obligatorio, nivel actual: Lo domino. Tocar para cambiar". */
    label: (name: string, kind: string, level: string) =>
      `${name}, ${kind}, nivel actual: ${level}. Tocar para cambiar`,
  },
  ring: {
    caption: 'compatibilidad',
    announce: (percent: number) => `${percent} % de compatibilidad`,
    announceNone: 'Compatibilidad sin calcular: información insuficiente',
  },

  // ---- Vacancies and opportunities (spec 004) ----
  common: {
    retry: 'Reintentar',
  },
  vacancies: {
    sampleData: 'Datos de ejemplo',
    sampleFootnote: 'Las empresas y vacantes son ficticias; las reales llegarán con los convenios.',
    modality: {
      REMOTE: 'Remoto',
      HYBRID: 'Híbrido',
      ONSITE: 'Presencial',
    } as const,
    /** Minimum level of a requirement (RN-03). */
    levels: {
      1: 'básico',
      2: 'intermedio',
      3: 'avanzado',
    } as Readonly<Record<number, string>>,
    hoursPerWeek: (hours: number) => `${hours} h por semana`,
    publishedToday: 'Publicada hoy',
    publishedYesterday: 'Publicada ayer',
    publishedOn: (date: string) => `Publicada el ${date}`,
    moreSkills: (count: number) => `+${count}`,
    moreSkillsLabel: (count: number) =>
      count === 1 ? 'y 1 tecnología más' : `y ${count} tecnologías más`,
    skillsLabel: 'Tecnologías',
    companyAndCity: (company: string, city: string) => `${company} · ${city}`,
    companyVacancies: (count: number) => (count === 1 ? '1 vacante' : `${count} vacantes`),
    loadingLabel: 'Cargando…',
  },
  landingVacancies: {
    featuredTitle: 'Vacantes destacadas',
    openCount: (count: number) =>
      count === 1 ? '1 vacante abierta' : `${count} vacantes abiertas`,
    seeAll: 'Ver todas las vacantes',
    featuredError: 'No pudimos cargar las vacantes destacadas.',
    companiesTitle: 'Empresas que buscan talento',
    companiesError: 'No pudimos cargar las empresas.',
    companiesList: 'Empresas destacadas',
  },
  opportunities: {
    title: 'Oportunidades',
    lead: 'Explora vacantes de prácticas preprofesionales sin crear cuenta.',
    searchLabel: 'Buscar vacantes',
    queryLabel: '¿Qué sabes hacer?',
    queryPlaceholder: 'Por ejemplo: Java, SQL o React',
    locationLabel: 'Ubicación',
    locationAll: 'Todas las ubicaciones',
    submit: 'Buscar',
    filters: {
      title: 'Filtros',
      toggle: (count: number) => `Filtros (${count})`,
      panelLabel: 'Filtros de búsqueda',
      modality: 'Modalidad',
      skills: 'Tecnologías',
      option: (name: string, count: number) => `${name} (${count})`,
      showAllSkills: (count: number) => `Ver las ${count} tecnologías`,
      showFewerSkills: 'Ver menos tecnologías',
      clear: 'Limpiar filtros',
      unavailable: 'No pudimos cargar los filtros.',
    },
    sort: {
      label: 'Ordenar por',
      relevance: 'Más relevantes',
      recent: 'Más recientes',
      company: 'Empresa (A-Z)',
    },
    results: {
      title: 'Vacantes',
      searching: 'Buscando vacantes…',
      count: (count: number) => (count === 1 ? '1 vacante' : `${count} vacantes`),
      range: (from: number, to: number, total: number) => `Mostrando ${from}–${to} de ${total}`,
      listLabel: 'Resultados de la búsqueda',
      emptyTitle: 'No encontramos vacantes con estos filtros',
      emptyHint: 'Prueba con menos filtros o con otra palabra.',
      noMatchesTitle: (text: string) => `No encontramos vacantes para «${text}»`,
      suggestionsLabel: 'Sugerencias',
      suggestionSpelling: 'Revisa cómo lo escribiste.',
      suggestionFilters: 'Quita algún filtro para ampliar la búsqueda.',
      suggestionTechnology: 'Prueba con una tecnología, como Java o React.',
      removeFilters: 'Quitar filtros',
      recentTitle: 'Vacantes recientes que pueden interesarte',
      recentListLabel: 'Vacantes recientes',
      noneYetTitle: 'Aún no hay vacantes publicadas',
      noneYetHint: 'Vuelve pronto para ver las nuevas.',
      errorTitle: 'No pudimos cargar las vacantes.',
    },
    pagination: {
      label: 'Paginación',
      previous: 'Anterior',
      next: 'Siguiente',
      page: (page: number) => `Página ${page}`,
    },
  },
  vacancyDetail: {
    back: 'Volver a los resultados',
    companyProfile: 'Ver perfil de la empresa',
    companyProfileSoon: 'Próximamente',
    about: 'Sobre la vacante',
    company: 'Sobre la empresa',
    requirements: 'Requisitos',
    required: 'Obligatorios',
    desirable: 'Deseables',
    minLevel: (level: string) => `Nivel mínimo: ${level}`,
    conditions: 'Condiciones',
    modality: 'Modalidad',
    location: 'Ubicación',
    noLocation: 'Sin ciudad fija',
    hours: 'Dedicación',
    published: 'Publicación',
    process: 'Etapas del proceso',
    processNote: 'Solo para que sepas qué viene. La decisión final es de la empresa.',
    stageSent: 'Enviada',
    stageSoon: 'Próximamente',
    stagesNext: ['Examen técnico', 'Entrevista', 'Revisión de la empresa'] as readonly string[],
    apply: 'Postular',
    applyLoginNotice: 'Inicia sesión para ver tu compatibilidad',
    applyDisabled: 'Postular: disponible pronto',
    closedTitle: 'Esta vacante ya no está abierta',
    closedHint: 'Puedes mirar otras vacantes que sí lo están.',
    seeOthers: 'Ver otras vacantes',
    notFoundTitle: 'Esta vacante no existe o ya no está disponible',
    notFoundLink: 'Ver todas las oportunidades',
    errorTitle: 'No pudimos cargar la vacante.',
  },

  // ---- Landing ----
  hero: {
    titleStart: 'Tu primera práctica empieza por ',
    titleEmphasis: 'lo que ya sabes hacer.',
    lead: 'Cuéntanos qué sabes. Talenti te muestra qué vacantes encajan contigo y te explica por qué.',
    primaryAction: 'Ver mis vacantes',
    secondaryAction: 'Cómo funciona',
  },
  card: {
    avatar: 'AT',
    title: 'Practicante Backend Java',
    subtitle: 'Andes Tech · Lima · Remoto',
    chipsTitle: 'Marca lo que sabes',
    chipsHint: 'Toca para cambiar el nivel',
    chipsGroupLabel: 'Habilidades que sabes',
    sampleNote: 'Datos y explicación de ejemplo.',
    verdict: {
      strong: 'Encajas muy bien',
      partial: 'Encajas con algo de refuerzo',
      low: 'Aún te falta camino',
      capped: 'Te falta un requisito obligatorio',
      insufficient: 'Información insuficiente',
    },
    verdictSub: {
      normal: 'Cada parte del resultado se explica abajo.',
      capped: (cap: number) => `Con un requisito obligatorio en cero, el máximo es ${cap} %.`,
      insufficient:
        'Marca al menos una habilidad para calcular tu compatibilidad. No mostramos un porcentaje que no podamos respaldar.',
    },
    groups: {
      match: 'Coinciden',
      reinforce: 'Para reforzar',
      missing: 'Te faltan',
      unknown: 'Aún no sabemos',
    },
    evidence: {
      declared: 'declarado',
      notDeclared: 'no declarado',
      /** Example: "pide nivel intermedio". */
      asks: (level: string) => `pide nivel ${level}`,
      missingData: 'falta dato',
    },
    minLevel: {
      1: 'básico',
      2: 'intermedio',
    } as Readonly<Record<number, string>>,
    availability: 'Tu disponibilidad semanal',
    dora: {
      insufficient: 'Marca lo que sabes y te explico cómo encajas con esta vacante.',
      /** Example: "Para esta vacante SQL es obligatorio y todavía no lo marcas. ..." */
      capped: (names: string, plural: boolean, cap: number) =>
        plural
          ? `Para esta vacante ${names} son obligatorios y todavía no los marcas. Con eso, el máximo es ${cap} %.`
          : `Para esta vacante ${names} es obligatorio y todavía no lo marcas. Con eso, el máximo es ${cap} %.`,
      matches: (names: string) => `Coincides en ${names}.`,
      reinforce: (names: string) => `Para subir tu compatibilidad, refuerza ${names}.`,
      extras: (names: string) => `Podrías sumar ${names} como extra.`,
      nothingMissing: 'No te falta nada para esta vacante.',
    },
    how: {
      summary: 'Cómo se calcula este porcentaje',
      intro: 'Lo calcula el sistema con reglas visibles, no la inteligencia artificial.',
      weights: 'Peso de cada parte',
      required: 'Requisitos obligatorios',
      desirable: 'Requisitos deseables',
      conditions: 'Modalidad, ubicación y disponibilidad',
      similarity: 'Parecido de tus proyectos con la vacante',
      cap: (cap: number) =>
        `Si falta por completo un requisito obligatorio, el máximo es ${cap} %.`,
    },
    skills: {
      java: 'Java',
      spring: 'Spring Boot',
      sql: 'SQL',
      git: 'Git',
      docker: 'Docker',
    },
  },
  how: {
    title: 'Tres pasos, ninguno a ciegas.',
    tablistLabel: 'Pasos',
    steps: [
      {
        title: 'Cuéntanos qué sabes',
        description: 'Sube tu CV o márcalo a mano. Tú confirmas cada dato antes de que se guarde.',
      },
      {
        title: 'Mira por qué te encajan',
        description:
          'Cada vacante te dice qué coincide, qué te falta y qué aún no sabemos. Sin cajas negras.',
      },
      {
        title: 'Practica y postula',
        description:
          'Ensaya entrevistas en privado con DORA y postula cuando te sientas listo. La empresa decide.',
      },
    ],
    chips: ['Java', 'Spring Boot', 'SQL', 'Git', 'Docker', 'React'],
    bars: ['Requisitos obligatorios', 'Requisitos deseables', 'Condiciones'],
    flow: [
      { label: 'Sesión de práctica', state: 'Privada' },
      { label: 'Postulación enviada', state: 'Hoy' },
      { label: 'Revisión de la empresa', state: 'Próximamente' },
    ],
  },
  dora: {
    title: 'Practica con DORA antes de que cuente.',
    lead: 'Una asistente de inteligencia artificial que te pregunta sobre tu propio perfil. Es privada: nadie más la ve.',
    statesLabel: 'Estado de DORA',
    states: {
      idle: 'Reposo',
      listening: 'Escuchando',
      thinking: 'Pensando',
      speaking: 'Hablando',
    },
    captions: {
      idle: 'Lista cuando tú lo estés.',
      listening: 'Te escucho. Cuéntame con calma.',
      thinking: 'Dame un momento para pensarlo…',
      speaking: 'Háblame de un proyecto del que te sientas orgulloso.',
    },
    orbLabel: 'DORA, la asistente de voz de Talenti',
    mic: {
      start: 'Probar con mi micrófono',
      stop: 'Detener micrófono',
      active: 'El audio se queda en tu navegador y no se guarda.',
      failed: 'No pude usar el micrófono; el orbe sigue con una simulación.',
    },
    reducedMotion: 'Reducir movimiento está activado: el orbe no se anima.',
  },

  // ---- Legal framework and cookies (spec 002). The legal documents themselves come from the
  // API (GET /legal/current); only the frame around them is written here. ----
  footer: {
    label: 'Información legal',
    terms: 'Términos y Condiciones',
    privacy: 'Política de Privacidad',
    cookies: 'Política de Cookies',
    transparency: 'Transparencia',
    cookiePreferences: 'Preferencias de cookies',
  },
  legal: {
    draftNotice: 'Borrador: pendiente de revisión legal y sujeto a cambios',
    /** Heading shown while the document loads or fails (the real title comes from the API). */
    fallbackTitles: {
      terms: 'Términos y Condiciones',
      privacy: 'Política de Privacidad',
      cookies: 'Política de Cookies',
    },
    version: (version: string, date: string) => `Versión ${version} · Publicada el ${date}`,
    versionPendingDate: (version: string) => `Versión ${version} · Fecha de publicación pendiente`,
    toc: 'Contenido',
    loading: 'Cargando el documento…',
    errorMessage: 'No pudimos cargar el documento. Inténtalo de nuevo.',
    tableLabel: 'Tabla desplazable',
    opensInNewTab: '(se abre en otra pestaña)',
  },
  transparency: {
    title: 'Transparencia: cómo usamos tus datos',
    lead: 'Aquí te lo explicamos en pocas palabras. El detalle completo está en la [Política de Privacidad](/privacidad).',
    blocks: [
      {
        id: 'resumen',
        heading: 'En pocas palabras',
        body: [
          '- **Tú decides.** Lo que la IA sugiere sobre ti, lo confirmas tú. La empresa decide si te contacta; tú decides a qué vacantes postulas.',
          '- **Pedimos permiso por separado** para leer tu CV y para usar tu voz, justo cuando hace falta. Si no lo das, hay una alternativa: completar tu perfil a mano o practicar por texto.',
          '- **No vendemos tus datos** ni los usamos para publicidad.',
          '- **Tus sesiones de práctica son privadas.** Nunca las ven las empresas.',
        ].join('\n'),
        links: [{ label: 'Leer la Política de Privacidad completa', to: '/privacidad' }],
      },
      {
        id: 'datos',
        heading: 'Qué datos usamos',
        body: 'Tu correo y tu contraseña (protegida), lo que escribes en tu perfil, el CV que decidas subir, tus respuestas en las sesiones de práctica y tus postulaciones.\n\nNo necesitamos tu DNI, dirección, fecha de nacimiento, estado civil ni foto: los quitamos del CV antes de enviarlo a la IA.',
        links: [
          { label: 'Ver todos los datos', to: '/privacidad#seccion-2' },
          { label: 'Ver qué datos no necesitamos', to: '/privacidad#seccion-3' },
        ],
      },
      {
        id: 'finalidades',
        heading: 'Para qué los usamos',
        body: 'Para crear tu cuenta, recomendarte vacantes y explicarte por qué te encajan, dejarte postular y ayudarte a practicar con DORA.',
        links: [{ label: 'Ver todas las finalidades', to: '/privacidad#seccion-4' }],
      },
      {
        id: 'compartir',
        heading: 'Con quién los compartimos',
        body: [
          '- **Con las empresas**, solo cuando postulas y solo la parte de tu perfil que te mostramos antes de confirmar.',
          '- **Con proveedores que nos prestan servicios**, como IA, voz y alojamiento, que reciben solo lo necesario. Pueden estar fuera del Perú.',
          '- **Nunca** enviamos tu contraseña ni tu correo a los proveedores de IA y voz.',
        ].join('\n'),
        links: [
          { label: 'Ver con quién compartimos', to: '/privacidad#seccion-5' },
          { label: 'Ver las transferencias al extranjero', to: '/privacidad#seccion-6' },
        ],
      },
      {
        id: 'conservacion',
        heading: 'Cuánto tiempo los conservamos',
        body: 'El PDF de tu CV se elimina cuando confirmas o descartas las sugerencias, o a los 7 días. **Talenti no guarda tu audio**, solo su transcripción. El resto se conserva mientras tu cuenta exista o hasta que lo borres.',
        links: [{ label: 'Ver los plazos', to: '/privacidad#seccion-7' }],
      },
      {
        id: 'dora',
        heading: 'Cómo funciona DORA',
        body: 'DORA es una **inteligencia artificial**, no una persona, y siempre se marca como tal. La IA **sugiere** y tú **confirmas**. El **porcentaje de compatibilidad no lo calcula la IA**: lo calcula el sistema con reglas visibles y se explica con coincidencias y brechas. No inferimos emociones, acento ni apariencia.',
        links: [{ label: 'Ver cómo funciona la IA', to: '/privacidad#seccion-8' }],
      },
      {
        id: 'derechos',
        heading: 'Tus derechos y tu control',
        body: 'Puedes ver, corregir y borrar tus datos, retirar cualquier permiso, borrar tu CV y tus sesiones, pedir una explicación de un resultado y eliminar tu cuenta cuando quieras.',
        links: [{ label: 'Ver tus derechos', to: '/privacidad#seccion-10' }],
      },
    ],
  },
  cookies: {
    banner: {
      label: 'Aviso de cookies',
      onlyNecessary: 'Solo usamos cookies necesarias.',
      withOptional: (names: string) =>
        `Usamos cookies necesarias y, solo si lo aceptas, estas otras: ${names}. Empiezan desactivadas.`,
      policyLink: 'Política de Cookies',
      accept: 'Aceptar',
      reject: 'Rechazar',
      configure: 'Configurar',
    },
    panel: {
      title: 'Preferencias de cookies',
      intro:
        'Elige qué cookies permites. Las necesarias siempre están activas porque sin ellas el sitio no funciona.',
      required: 'Siempre activas',
      save: 'Guardar preferencias',
      close: 'Cerrar sin guardar',
      policyLink: 'Leer la Política de Cookies',
    },
    saved: 'Preferencias guardadas',
    categories: {
      necessary: {
        name: 'Necesarias',
        description:
          'Mantienen tu sesión iniciada, protegen tus acciones y recuerdan esta elección.',
      },
    },
  },
  acceptDocuments: {
    title: 'Actualizamos nuestros documentos',
    lead: 'Cambiamos los Términos y Condiciones y la Política de Privacidad. Para seguir usando Talenti, léelos y acéptalos.',
    readTitle: 'Léelos aquí',
    checkbox: 'He leído y acepto los documentos nuevos.',
    mustAccept:
      'Para continuar necesitas aceptar los documentos nuevos. Si prefieres no hacerlo, puedes cerrar sesión.',
    outdated:
      'Los documentos cambiaron mientras leías. Ya cargamos el texto actual: léelo y vuelve a aceptar.',
    failed: 'No pudimos guardar tu aceptación. Inténtalo de nuevo.',
    submit: 'Aceptar y continuar',
    saving: 'Guardando…',
    logout: 'Cerrar sesión',
    loggingOut: 'Cerrando sesión…',
    loading: 'Cargando…',
    errorMessage: 'No pudimos cargar los documentos. Inténtalo de nuevo.',
    logoutFailed: 'No pudimos cerrar tu sesión. Inténtalo de nuevo.',
  },
} as const
