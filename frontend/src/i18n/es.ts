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
} as const
