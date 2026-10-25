-- Spec 012 (DORA por voz, sesion de practica y entrevista evaluada), seccion 5. Modulo practice y cambio en shared.
-- Se ejecuta con el rol de la aplicacion, sin superusuario. Nombres en ingles, claves UUID, fechas timestamptz (UTC).
-- El audio NUNCA se guarda (D-13, RN-12): solo transcripciones, tamanos y tiempos.
-- Las claves foraneas hacia app_user (identity), vacancy (vacancy) y application (application) son integridad de
-- datos: el modulo practice no lee esas tablas; usa ProfileReader, VacancyLookup y ApplicationStages.

-- ---------------------------------------------------------------------------
-- Una sesion con DORA: practica libre (privada) o entrevista evaluada de una postulacion.
-- ---------------------------------------------------------------------------
CREATE TABLE practice_session (
    id                 uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    -- RN-12: al borrar la cuenta se borra todo lo de DORA.
    user_id            uuid         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    mode               varchar(10)  NOT NULL,
    basis              varchar(10)  NOT NULL,
    vacancy_id         uuid         REFERENCES vacancy (id) ON DELETE SET NULL,
    application_id     uuid         REFERENCES application (id) ON DELETE CASCADE,
    topic              varchar(40),
    question_count     smallint     NOT NULL,
    voice_enabled      boolean      NOT NULL,
    status             varchar(12)  NOT NULL DEFAULT 'CREATED',
    attempt            smallint     NOT NULL DEFAULT 1,
    notice_version     varchar(20),
    notice_accepted_at timestamptz,
    degraded_reasons   text[]       NOT NULL DEFAULT '{}',
    -- Trazabilidad (principio 11): quien produjo preguntas, voz y retroalimentacion.
    llm_adapter        varchar(40)  NOT NULL,
    llm_model          varchar(60),
    stt_model          varchar(60),
    tts_model          varchar(60),
    tts_voice          varchar(40),
    prompt_version     varchar(40)  NOT NULL,
    rubric_version     varchar(40)  NOT NULL,
    bank_version       varchar(40)  NOT NULL,
    created_at         timestamptz  NOT NULL,
    started_at         timestamptz,
    ended_at           timestamptz,
    last_activity_at   timestamptz  NOT NULL,
    CONSTRAINT ck_practice_session_mode CHECK (mode IN ('PRACTICE', 'INTERVIEW')),
    CONSTRAINT ck_practice_session_basis CHECK (basis IN ('PROFILE', 'VACANCY')),
    CONSTRAINT ck_practice_session_status CHECK (status IN ('CREATED', 'IN_PROGRESS', 'COMPLETED', 'ABANDONED', 'FAILED')),
    CONSTRAINT ck_practice_session_question_count CHECK (question_count BETWEEN 1 AND 5),
    CONSTRAINT ck_practice_session_attempt CHECK (attempt >= 1),
    -- La entrevista siempre es de una postulacion; la practica nunca (T5).
    CONSTRAINT ck_practice_session_interview CHECK ((mode = 'INTERVIEW') = (application_id IS NOT NULL)),
    CONSTRAINT ck_practice_session_topic CHECK (topic IS NULL OR btrim(topic) <> '')
);

-- Una sola sesion abierta por persona (012-02, 409 session-open).
CREATE UNIQUE INDEX uq_practice_session_open_per_user ON practice_session (user_id)
    WHERE status IN ('CREATED', 'IN_PROGRESS');
CREATE INDEX ix_practice_session_user_created ON practice_session (user_id, created_at DESC);
CREATE INDEX ix_practice_session_application ON practice_session (application_id, attempt);
-- Barrido de inactividad (012-14).
CREATE INDEX ix_practice_session_open_activity ON practice_session (last_activity_at)
    WHERE status IN ('CREATED', 'IN_PROGRESS');

-- ---------------------------------------------------------------------------
-- Preguntas planificadas de una sesion (numero fijo, sin repreguntas: duda 6).
-- ---------------------------------------------------------------------------
CREATE TABLE practice_question (
    id         uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id uuid         NOT NULL REFERENCES practice_session (id) ON DELETE CASCADE,
    position   smallint     NOT NULL,
    text       varchar(500) NOT NULL,
    source     varchar(10)  NOT NULL,
    basis_ref  varchar(60)  NOT NULL,
    CONSTRAINT uq_practice_question_position UNIQUE (session_id, position),
    CONSTRAINT ck_practice_question_source CHECK (source IN ('AI', 'BANK')),
    CONSTRAINT ck_practice_question_position CHECK (position BETWEEN 1 AND 5)
);

-- ---------------------------------------------------------------------------
-- Un turno: la respuesta a una pregunta. Sin audio (012-07, 012-12).
-- ---------------------------------------------------------------------------
CREATE TABLE practice_turn (
    id               uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    question_id      uuid          NOT NULL REFERENCES practice_question (id) ON DELETE CASCADE,
    -- Idempotencia del turno (012-06): repetir el mismo clientTurnId devuelve lo guardado.
    client_turn_id   uuid          NOT NULL,
    attempt          smallint      NOT NULL DEFAULT 1,
    input_mode       varchar(5)    NOT NULL,
    transcript_raw   varchar(4000) NOT NULL,
    transcript_final varchar(4000) NOT NULL,
    audio_ms         integer,
    stt_ms           integer,
    first_audio_ms   integer,
    total_ms         integer,
    bridge_source    varchar(10),
    corrected_at     timestamptz,
    created_at       timestamptz   NOT NULL,
    CONSTRAINT uq_practice_turn_client UNIQUE (client_turn_id),
    CONSTRAINT uq_practice_turn_attempt UNIQUE (question_id, attempt),
    CONSTRAINT ck_practice_turn_input_mode CHECK (input_mode IN ('VOICE', 'TEXT')),
    CONSTRAINT ck_practice_turn_bridge_source CHECK (bridge_source IS NULL OR bridge_source IN ('AI', 'TEMPLATE')),
    CONSTRAINT ck_practice_turn_attempt CHECK (attempt >= 1)
);

CREATE INDEX ix_practice_turn_question ON practice_turn (question_id, attempt DESC);
-- Latencia de los ultimos N turnos (012-15).
CREATE INDEX ix_practice_turn_created ON practice_turn (created_at DESC) WHERE first_audio_ms IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Retroalimentacion de practica y evaluacion de entrevista, por rubrica (012-18, 012-25). La genera la IA y la
-- valida el codigo; el total, si existe, lo calcula el codigo.
-- ---------------------------------------------------------------------------
CREATE TABLE practice_feedback (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    turn_id        uuid        NOT NULL REFERENCES practice_turn (id) ON DELETE CASCADE,
    status         varchar(12) NOT NULL,
    criteria       jsonb       NOT NULL DEFAULT '[]'::jsonb,
    total          smallint,
    model          varchar(60),
    prompt_version varchar(40) NOT NULL,
    rubric_version varchar(40) NOT NULL,
    created_at     timestamptz NOT NULL,
    CONSTRAINT uq_practice_feedback_turn UNIQUE (turn_id),
    CONSTRAINT ck_practice_feedback_status CHECK (status IN ('PENDING', 'READY', 'UNAVAILABLE')),
    CONSTRAINT ck_practice_feedback_total CHECK (total IS NULL OR total BETWEEN 0 AND 100)
);

-- ---------------------------------------------------------------------------
-- Hechos de la entrevista (012-26). Solo inserciones y SIN contenido: nunca califica fraude (principio 6).
-- ---------------------------------------------------------------------------
CREATE TABLE interview_event (
    id          uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id  uuid        NOT NULL REFERENCES practice_session (id) ON DELETE CASCADE,
    kind        varchar(30) NOT NULL,
    occurred_at timestamptz NOT NULL,
    data        jsonb       NOT NULL DEFAULT '{}'::jsonb,
    CONSTRAINT ck_interview_event_kind CHECK (kind IN ('NOTICE_ACCEPTED', 'STARTED', 'ANSWERED',
        'TRANSCRIPT_CORRECTED', 'DISCONNECTED', 'RESUMED', 'FINISHED', 'ABANDONED', 'PLATFORM_FAILURE'))
);

CREATE INDEX ix_interview_event_session ON interview_event (session_id, occurred_at);

-- ---------------------------------------------------------------------------
-- Modulo shared: el consumo de IA tambien mide cada llamada de DORA (012-30). read_id pasa a ser el id de cada
-- llamada (o de la sesion, en PRACTICE_SESSION e INTERVIEW_SESSION). Sigue SIN contenido.
-- ---------------------------------------------------------------------------
ALTER TABLE ai_usage_event DROP CONSTRAINT ck_ai_usage_event_function;
ALTER TABLE ai_usage_event ADD CONSTRAINT ck_ai_usage_event_function CHECK (function IN
    ('CV_READ', 'PRACTICE_SESSION', 'PRACTICE_FEEDBACK', 'EXPLANATION', 'STT', 'TTS', 'DORA_DIALOG',
     'INTERVIEW_SESSION', 'INTERVIEW_EVALUATION'));
-- Costo estimado en millonesimas de dolar, con los precios de la configuracion (a confirmar con el proveedor).
ALTER TABLE ai_usage_event ADD COLUMN estimated_cost_micros bigint;
ALTER TABLE ai_usage_event ADD CONSTRAINT ck_ai_usage_event_cost CHECK (estimated_cost_micros IS NULL OR estimated_cost_micros >= 0);
-- Tope mensual de gasto (012-30).
CREATE INDEX ix_ai_usage_event_time_cost ON ai_usage_event (occurred_at) WHERE estimated_cost_micros IS NOT NULL;
