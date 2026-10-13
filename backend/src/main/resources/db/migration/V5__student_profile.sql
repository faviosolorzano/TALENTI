-- Spec 006 (perfil del estudiante), seccion 5. Modulo profile.
-- Se ejecuta con el rol de la aplicacion (dueno del esquema public), sin superusuario.
-- Convenciones: nombres en ingles, claves UUID, fechas timestamptz (siempre UTC).
-- Las claves foraneas hacia app_user (identity) y skill (vacancy) son integridad de datos (006, duda 11):
-- el modulo profile no lee esas tablas; usa SkillCatalog para el catalogo.

-- ---------------------------------------------------------------------------
-- Perfil del estudiante. La fila nace con la primera escritura (upsert); leer no crea nada.
-- ---------------------------------------------------------------------------
CREATE TABLE student_profile (
    -- S6 borrara la cuenta y, con ella, el perfil (RN-12).
    user_id             uuid         PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    career              varchar(80),
    institute           varchar(80),
    cycle               smallint,
    hours_per_week      smallint,
    -- Modalidades que el estudiante acepta (006, duda 2): REMOTE, HYBRID, ONSITE; puede estar vacia.
    accepted_modalities varchar(10)[] NOT NULL DEFAULT '{}',
    location            varchar(60),
    created_at          timestamptz  NOT NULL DEFAULT now(),
    updated_at          timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT ck_student_profile_career CHECK (career IS NULL OR btrim(career) <> ''),
    CONSTRAINT ck_student_profile_institute CHECK (institute IS NULL OR btrim(institute) <> ''),
    CONSTRAINT ck_student_profile_cycle CHECK (cycle IS NULL OR cycle BETWEEN 1 AND 10),
    CONSTRAINT ck_student_profile_hours_per_week CHECK (hours_per_week IS NULL OR hours_per_week BETWEEN 1 AND 40),
    CONSTRAINT ck_student_profile_modalities CHECK (accepted_modalities <@ ARRAY['REMOTE', 'HYBRID', 'ONSITE']::varchar[]),
    CONSTRAINT ck_student_profile_location CHECK (location IS NULL OR btrim(location) <> '')
);

-- ---------------------------------------------------------------------------
-- Habilidades del perfil: solo del catalogo (RN-03), con nivel 1 a 3.
-- ---------------------------------------------------------------------------
CREATE TABLE student_skill (
    user_id    uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    skill_id   uuid        NOT NULL REFERENCES skill (id) ON DELETE RESTRICT,
    -- RN-03: 1 basico, 2 intermedio, 3 avanzado.
    level      smallint    NOT NULL,
    -- Solo DECLARED se usa en el E2 (lo dijo la persona; nadie lo ha verificado).
    evidence   varchar(10) NOT NULL DEFAULT 'DECLARED',
    updated_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, skill_id),
    CONSTRAINT ck_student_skill_level CHECK (level BETWEEN 1 AND 3),
    CONSTRAINT ck_student_skill_evidence CHECK (evidence IN ('DECLARED', 'OBSERVED', 'EVALUATED'))
);

-- La clave primaria cubre las busquedas por usuario; este indice cubre las que parten de la habilidad.
CREATE INDEX ix_student_skill_skill ON student_skill (skill_id);

-- ---------------------------------------------------------------------------
-- Proyectos del estudiante. Las tecnologias son texto libre: no son habilidades del catalogo ni cuentan para
-- los requisitos de una vacante (006, duda 5).
-- ---------------------------------------------------------------------------
CREATE TABLE student_project (
    id           uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      uuid          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    name         varchar(80)   NOT NULL,
    description  varchar(500)  NOT NULL,
    technologies varchar(30)[] NOT NULL,
    created_at   timestamptz   NOT NULL DEFAULT now(),
    updated_at   timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT ck_student_project_name CHECK (btrim(name) <> ''),
    CONSTRAINT ck_student_project_description CHECK (btrim(description) <> ''),
    CONSTRAINT ck_student_project_technologies CHECK (cardinality(technologies) BETWEEN 1 AND 8)
);

CREATE INDEX ix_student_project_user ON student_project (user_id);
