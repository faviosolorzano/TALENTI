-- Spec 004 (vacantes y oportunidades), seccion 5. Modulo vacancy.
-- Se ejecuta con el rol de la aplicacion (dueno del esquema public), sin superusuario.
-- Convenciones: nombres en ingles, claves UUID, fechas timestamptz (siempre UTC).

-- ---------------------------------------------------------------------------
-- 004-01: la busqueda sin tildes usa la extension unaccent. El rol de la aplicacion no es superusuario ni
-- tiene CREATE en la base, asi que la crea db/init/01-init-roles.sh con el administrador de la imagen.
-- Si el volumen es anterior a esa linea, la migracion se detiene aqui con un mensaje claro.
-- ---------------------------------------------------------------------------
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'unaccent') THEN
        RAISE EXCEPTION 'Falta la extension unaccent en la base de datos. La crea el script db/init/01-init-roles.sh '
            'solo al crear un volumen vacio: ejecuta "docker compose down -v" para recrearlo (borra los datos locales) '
            'o, en una base existente, pide a un administrador "CREATE EXTENSION unaccent;".';
    END IF;
END
$$;

-- ---------------------------------------------------------------------------
-- Empresas. Ficticias en el E2: sin logo, web ni contacto.
-- ---------------------------------------------------------------------------
CREATE TABLE company (
    id                uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    name              varchar(80)  NOT NULL,
    city              varchar(60)  NOT NULL,
    short_description varchar(160) NOT NULL,
    -- Marca de los datos de ejemplo para la portada (RF-53); no es un pago ni un factor de orden.
    featured          boolean      NOT NULL DEFAULT false,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_company_name UNIQUE (name),
    CONSTRAINT ck_company_name_not_blank CHECK (btrim(name) <> ''),
    CONSTRAINT ck_company_city_not_blank CHECK (btrim(city) <> '')
);

-- ---------------------------------------------------------------------------
-- Catalogo de habilidades con sinonimos (RN-03). Lo usara S3 (perfil) por la interfaz SkillCatalog.
-- ---------------------------------------------------------------------------
CREATE TABLE skill (
    id      uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    slug    varchar(40) NOT NULL,
    name    varchar(60) NOT NULL,
    -- Sinonimos ("JS" para JavaScript). Ninguno repite el nombre o sinonimo de otra habilidad (lo prueba 004-02).
    aliases text[]      NOT NULL DEFAULT '{}',
    CONSTRAINT uq_skill_slug UNIQUE (slug),
    CONSTRAINT uq_skill_name UNIQUE (name),
    CONSTRAINT ck_skill_slug_format CHECK (slug ~ '^[a-z0-9-]+$')
);

-- ---------------------------------------------------------------------------
-- Vacantes.
-- ---------------------------------------------------------------------------
CREATE TABLE vacancy (
    id             uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    company_id     uuid         NOT NULL REFERENCES company (id) ON DELETE RESTRICT,
    title          varchar(120) NOT NULL,
    summary        varchar(200) NOT NULL,
    description    text         NOT NULL,
    modality       varchar(10)  NOT NULL,
    -- Ciudad. Obligatoria si la vacante no es remota y nula si lo es ("remoto" es modalidad, no ciudad).
    location       varchar(60),
    hours_per_week integer      NOT NULL,
    published_at   timestamptz  NOT NULL,
    status         varchar(10)  NOT NULL DEFAULT 'OPEN',
    featured       boolean      NOT NULL DEFAULT false,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_vacancy_title_not_blank CHECK (btrim(title) <> ''),
    CONSTRAINT ck_vacancy_modality CHECK (modality IN ('REMOTE', 'HYBRID', 'ONSITE')),
    CONSTRAINT ck_vacancy_status CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT ck_vacancy_hours_per_week CHECK (hours_per_week BETWEEN 1 AND 40),
    CONSTRAINT ck_vacancy_location CHECK (
        (modality = 'REMOTE' AND location IS NULL)
        OR (modality <> 'REMOTE' AND location IS NOT NULL AND btrim(location) <> ''))
);

CREATE INDEX ix_vacancy_status_published ON vacancy (status, published_at DESC);
CREATE INDEX ix_vacancy_company ON vacancy (company_id);

-- ---------------------------------------------------------------------------
-- Requisitos estructurados de cada vacante (habilidad, tipo, nivel minimo). Los usara el motor de compatibilidad.
-- ---------------------------------------------------------------------------
CREATE TABLE vacancy_requirement (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    vacancy_id uuid        NOT NULL REFERENCES vacancy (id) ON DELETE CASCADE,
    skill_id   uuid        NOT NULL REFERENCES skill (id) ON DELETE RESTRICT,
    kind       varchar(10) NOT NULL,
    -- RN-03: 1 basico, 2 intermedio, 3 avanzado.
    min_level  integer     NOT NULL,
    CONSTRAINT uq_vacancy_requirement UNIQUE (vacancy_id, skill_id),
    CONSTRAINT ck_vacancy_requirement_kind CHECK (kind IN ('REQUIRED', 'DESIRABLE')),
    CONSTRAINT ck_vacancy_requirement_min_level CHECK (min_level BETWEEN 1 AND 3)
);

CREATE INDEX ix_vacancy_requirement_skill ON vacancy_requirement (skill_id);

-- ---------------------------------------------------------------------------
-- Catalogo de habilidades (004-02). Es parte del esquema, no de los datos de ejemplo: S3 lo necesita siempre.
-- ---------------------------------------------------------------------------
INSERT INTO skill (slug, name, aliases) VALUES
    ('java',        'Java',        ARRAY['Java SE', 'Java 17']),
    ('spring-boot', 'Spring Boot', ARRAY['Spring', 'SpringBoot']),
    ('sql',         'SQL',         ARRAY['Structured Query Language', 'Consultas SQL']),
    ('postgresql',  'PostgreSQL',  ARRAY['Postgres', 'PSQL']),
    ('mysql',       'MySQL',       ARRAY['MariaDB']),
    ('mongodb',     'MongoDB',     ARRAY['Mongo']),
    ('git',         'Git',         ARRAY['GitHub', 'GitLab', 'Control de versiones']),
    ('docker',      'Docker',      ARRAY['Contenedores', 'Docker Compose']),
    ('javascript',  'JavaScript',  ARRAY['JS', 'ECMAScript']),
    ('typescript',  'TypeScript',  ARRAY['TS']),
    ('html',        'HTML',        ARRAY['HTML5']),
    ('css',         'CSS',         ARRAY['CSS3']),
    ('react',       'React',       ARRAY['ReactJS', 'React.js']),
    ('vue',         'Vue',         ARRAY['VueJS', 'Vue.js']),
    ('node-js',     'Node.js',     ARRAY['NodeJS', 'Node']),
    ('python',      'Python',      ARRAY['Python3', 'Python 3']),
    ('csharp',      'C#',          ARRAY['CSharp', 'C Sharp']),
    ('dotnet',      '.NET',        ARRAY['dotnet', 'ASP.NET', '.NET Core']),
    ('php',         'PHP',         ARRAY[]::text[]),
    ('laravel',     'Laravel',     ARRAY[]::text[]),
    ('kotlin',      'Kotlin',      ARRAY[]::text[]),
    ('android',     'Android',     ARRAY['Android Studio', 'Desarrollo Android']),
    ('swift',       'Swift',       ARRAY['SwiftUI']),
    ('testing',     'Testing',     ARRAY['Pruebas', 'Pruebas de software', 'QA', 'Control de calidad']),
    ('postman',     'Postman',     ARRAY[]::text[]),
    ('selenium',    'Selenium',    ARRAY['Selenium WebDriver']),
    ('rest-apis',   'REST APIs',   ARRAY['API REST', 'REST', 'RESTful', 'APIs']),
    ('linux',       'Linux',       ARRAY['GNU/Linux', 'Ubuntu']),
    ('aws',         'AWS',         ARRAY['Amazon Web Services']),
    ('scrum',       'Scrum',       ARRAY['Metodologías ágiles', 'Agile']);
