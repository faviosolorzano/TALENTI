-- Spec 009 (lector de CV), seccion 5 y 3.1. Modulos cv y shared.
-- Nota de orden: la V5 es del perfil y la V6 de los datos personales (rama feat/s3b-datos-personales, aun en un
-- Pull Request); esta es la V7 para no chocar con ella.
-- Se ejecuta con el rol de la aplicacion, sin superusuario. Nombres en ingles, claves UUID, fechas timestamptz (UTC).
-- Enmienda 1 de la spec: no hay permiso CV_AI aparte, asi que identity no cambia.
-- Decision de diseno: el PDF NO se guarda (ni siquiera de forma transitoria). El texto se extrae en la misma peticion
-- de subida (hace falta para validar los 200 caracteres de texto), asi que no hay motivo para escribir el archivo en
-- la base: es mas estricto que 009-10. De ahi que no exista la columna pdf; si se guarda su tamano y su huella.

-- ---------------------------------------------------------------------------
-- Modulo cv: un analisis de CV por lectura. La fila, ya sin contenido, se conserva para contar el limite de 24 horas.
-- ---------------------------------------------------------------------------
CREATE TABLE cv_analysis (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    status           varchar(10)  NOT NULL,
    failure_reason   varchar(30),
    file_size        integer      NOT NULL,
    -- Huella SHA-256 (hex) del archivo; nunca del texto ni del contenido (009-26).
    file_sha256      char(64)     NOT NULL,
    -- Texto ya reducido (009-09), solo mientras el analisis esta abierto (009-10); se borra al cerrarlo.
    reduced_text     text,
    input_truncated  boolean      NOT NULL DEFAULT false,
    -- BASIC (reglas, local) o ENHANCED (IA); ENHANCED solo cuando una lectura con IA se completo.
    level            varchar(10)  NOT NULL DEFAULT 'BASIC',
    reused           boolean      NOT NULL DEFAULT false,
    degraded         boolean      NOT NULL DEFAULT false,
    degraded_reason  varchar(30),
    adapter          varchar(40),
    adapter_version  varchar(40),
    rule_set_version varchar(40),
    -- Lectura en curso o ultima lectura: sirve de clave de idempotencia con ai_usage_event.read_id (009-32).
    current_read_id  uuid,
    created_at       timestamptz  NOT NULL,
    finished_at      timestamptz,
    expires_at       timestamptz  NOT NULL,
    CONSTRAINT ck_cv_analysis_status CHECK (status IN
        ('PENDING', 'ANALYZING', 'READY', 'FAILED', 'CONFIRMED', 'DISCARDED', 'EXPIRED')),
    CONSTRAINT ck_cv_analysis_level CHECK (level IN ('BASIC', 'ENHANCED')),
    CONSTRAINT ck_cv_analysis_file_size CHECK (file_size > 0)
);

-- Un solo analisis abierto por persona (009-07).
CREATE UNIQUE INDEX uq_cv_analysis_open_per_user ON cv_analysis (user_id)
    WHERE status IN ('PENDING', 'ANALYZING', 'READY');
-- Limite de 24 horas (009-07) y huella (009-26).
CREATE INDEX ix_cv_analysis_user_created ON cv_analysis (user_id, created_at);
CREATE INDEX ix_cv_analysis_user_sha ON cv_analysis (user_id, file_sha256);
-- Tarea de expiracion (009-10).
CREATE INDEX ix_cv_analysis_expiry ON cv_analysis (expires_at) WHERE status IN ('PENDING', 'ANALYZING', 'READY', 'FAILED');

-- ---------------------------------------------------------------------------
-- Modulo cv: sugerencias de un analisis. Se borran al confirmar, descartar o expirar.
-- ---------------------------------------------------------------------------
CREATE TABLE cv_suggestion (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_id uuid         NOT NULL REFERENCES cv_analysis (id) ON DELETE CASCADE,
    kind        varchar(10)  NOT NULL,
    field       varchar(10),
    payload     jsonb        NOT NULL,
    evidence    varchar(200) NOT NULL,
    position    integer      NOT NULL,
    CONSTRAINT ck_cv_suggestion_kind CHECK (kind IN ('SKILL', 'ACADEMIC', 'PROJECT')),
    CONSTRAINT ck_cv_suggestion_field CHECK (field IS NULL OR field IN ('CAREER', 'INSTITUTE', 'CYCLE'))
);

CREATE INDEX ix_cv_suggestion_analysis ON cv_suggestion (analysis_id, position);

-- ---------------------------------------------------------------------------
-- Modulo shared: medicion del consumo de IA (009-31). Solo inserciones. SIN contenido: ni texto del CV, ni
-- sugerencias, ni huella. La medicion sobrevive a la cuenta, anonimizada (RN-12).
-- ---------------------------------------------------------------------------
CREATE TABLE ai_usage_event (
    id          uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at timestamptz  NOT NULL,
    user_id     uuid         REFERENCES app_user (id) ON DELETE SET NULL,
    function    varchar(20)  NOT NULL,
    level       varchar(10)  NOT NULL,
    adapter     varchar(40)  NOT NULL,
    model       varchar(60),
    outcome     varchar(30)  NOT NULL,
    input_size  integer      NOT NULL DEFAULT 0,
    output_size integer      NOT NULL DEFAULT 0,
    -- Clave de idempotencia: una lectura se anota una sola vez (009-32).
    read_id     uuid         NOT NULL,
    CONSTRAINT uq_ai_usage_event_read UNIQUE (read_id),
    CONSTRAINT ck_ai_usage_event_function CHECK (function IN
        ('CV_READ', 'PRACTICE_SESSION', 'PRACTICE_FEEDBACK', 'EXPLANATION', 'STT', 'TTS')),
    CONSTRAINT ck_ai_usage_event_level CHECK (level IN ('BASIC', 'ENHANCED')),
    CONSTRAINT ck_ai_usage_event_outcome CHECK (outcome IN
        ('SUCCESS', 'FAILED_PLATFORM', 'BLOCKED_PERSONAL_QUOTA', 'BLOCKED_GLOBAL_QUOTA'))
);

CREATE INDEX ix_ai_usage_event_user_function ON ai_usage_event (user_id, function, occurred_at);
CREATE INDEX ix_ai_usage_event_function_time ON ai_usage_event (function, occurred_at);
