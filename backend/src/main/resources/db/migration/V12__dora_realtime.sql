-- Spec 012, enmienda 2 (D-63/D-64): DORA en tiempo real. Modulo practice y cambio en shared.
-- Nombres en ingles, claves UUID, fechas timestamptz (UTC). El audio y el SDP (lleva direcciones IP) NUNCA se guardan:
-- solo el texto de la conversacion que reporta el navegador, las etapas y los tiempos medidos por el servidor.

-- ---------------------------------------------------------------------------
-- La sesion recuerda por que canal conversa y con que modelo y voz en tiempo real (principio 11).
-- ends_at: fin decidido por el RELOJ DEL SERVIDOR al abrir la primera llamada (012-49); una reconexion no lo alarga.
-- ---------------------------------------------------------------------------
ALTER TABLE practice_session ADD COLUMN channel varchar(12) NOT NULL DEFAULT 'TURN_BASED';
ALTER TABLE practice_session ADD COLUMN realtime_model varchar(60);
ALTER TABLE practice_session ADD COLUMN realtime_voice varchar(40);
ALTER TABLE practice_session ADD COLUMN transcription_model varchar(60);
ALTER TABLE practice_session ADD COLUMN ends_at timestamptz;
ALTER TABLE practice_session ADD CONSTRAINT ck_practice_session_channel CHECK (channel IN ('TURN_BASED', 'REALTIME'));

-- ---------------------------------------------------------------------------
-- Cada llamada en tiempo real de una sesion (la primera y las reconexiones, 012-52). Una sola abierta por sesion.
-- ---------------------------------------------------------------------------
CREATE TABLE realtime_call (
    id                   uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id           uuid         NOT NULL REFERENCES practice_session (id) ON DELETE CASCADE,
    -- Id de la llamada en el proveedor (para colgarla). Nulo mientras se crea o si fallo.
    provider_call_id     varchar(120),
    status               varchar(8)   NOT NULL,
    -- Version de las instrucciones de la entrevistadora con que se abrio (principio 11).
    instructions_version varchar(40)  NOT NULL,
    start_phase          varchar(20)  NOT NULL,
    started_at           timestamptz  NOT NULL,
    ended_at             timestamptz,
    end_reason           varchar(20),
    -- Segundos medidos por el servidor (creacion a cierre; tras un reinicio, hasta la ultima actividad, 012-50).
    measured_seconds     integer,
    CONSTRAINT ck_realtime_call_status CHECK (status IN ('OPEN', 'CLOSED', 'FAILED')),
    CONSTRAINT ck_realtime_call_start_phase CHECK (start_phase IN ('GREETING', 'RESUME')),
    CONSTRAINT ck_realtime_call_end_reason CHECK (end_reason IS NULL OR end_reason IN ('FINISHED', 'FALLBACK',
        'USER_LEFT', 'ERROR', 'TIME_LIMIT', 'SESSION_CLOSED', 'REPLACED', 'DELETED', 'PROVIDER_ERROR')),
    CONSTRAINT ck_realtime_call_seconds CHECK (measured_seconds IS NULL OR measured_seconds >= 0),
    CONSTRAINT ck_realtime_call_closed CHECK ((status = 'OPEN') = (ended_at IS NULL))
);

-- 012-51 y 012-43: una sola llamada abierta por sesion (la segunda pestana recibe 409 realtime-call-open).
CREATE UNIQUE INDEX uq_realtime_call_open_per_session ON realtime_call (session_id) WHERE status = 'OPEN';
CREATE INDEX ix_realtime_call_session ON realtime_call (session_id, started_at);

-- ---------------------------------------------------------------------------
-- Lo dicho en la conversacion (persona y DORA) y las etapas que reporta el navegador (012-44, 012-45). Las lineas de
-- DORA son generadas por IA y reportadas por el cliente: solo para mostrar, nunca para evaluar (012-48).
-- Idempotente por (session_id, event_id).
-- ---------------------------------------------------------------------------
CREATE TABLE practice_utterance (
    id                uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id        uuid          NOT NULL REFERENCES practice_session (id) ON DELETE CASCADE,
    call_id           uuid          NOT NULL REFERENCES realtime_call (id) ON DELETE CASCADE,
    -- Orden de llegada al servidor (la maquina de etapas solo acepta avances en este orden).
    seq               bigint        GENERATED ALWAYS AS IDENTITY,
    event_id          varchar(100)  NOT NULL,
    -- UTTERANCE: algo dicho o escrito; STAGE: un cambio de etapa aceptado.
    kind              varchar(10)   NOT NULL DEFAULT 'UTTERANCE',
    speaker           varchar(6),
    phase             varchar(20)   NOT NULL,
    question_position smallint,
    text              varchar(4000),
    input_mode        varchar(5),
    latency_ms        integer,
    -- Momento que reporta el navegador; en una etapa sin ese dato queda nulo.
    occurred_at       timestamptz,
    received_at       timestamptz   NOT NULL,
    CONSTRAINT uq_practice_utterance_event UNIQUE (session_id, event_id),
    CONSTRAINT ck_practice_utterance_kind CHECK (kind IN ('UTTERANCE', 'STAGE')),
    CONSTRAINT ck_practice_utterance_speaker CHECK (speaker IS NULL OR speaker IN ('PERSON', 'DORA')),
    CONSTRAINT ck_practice_utterance_phase CHECK (phase IN ('GREETING', 'INTRO', 'QUESTION', 'FOLLOW_UP',
        'CANDIDATE_QUESTIONS', 'CLOSING')),
    CONSTRAINT ck_practice_utterance_input_mode CHECK (input_mode IS NULL OR input_mode IN ('VOICE', 'TEXT')),
    CONSTRAINT ck_practice_utterance_latency CHECK (latency_ms IS NULL OR latency_ms >= 0),
    CONSTRAINT ck_practice_utterance_shape CHECK (
        (kind = 'UTTERANCE' AND speaker IS NOT NULL AND text IS NOT NULL AND input_mode IS NOT NULL
            AND occurred_at IS NOT NULL)
        OR (kind = 'STAGE' AND speaker IS NULL AND text IS NULL))
);

CREATE INDEX ix_practice_utterance_session ON practice_utterance (session_id, seq);
-- Latencia del canal en tiempo real (012-55).
CREATE INDEX ix_practice_utterance_latency ON practice_utterance (received_at DESC) WHERE latency_ms IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Una respuesta armada (pregunta + repregunta) puede llegar a 8 000 caracteres (duda E2-11).
-- ---------------------------------------------------------------------------
ALTER TABLE practice_turn ALTER COLUMN transcript_raw TYPE varchar(8000);
ALTER TABLE practice_turn ALTER COLUMN transcript_final TYPE varchar(8000);

-- ---------------------------------------------------------------------------
-- Hechos nuevos de la entrevista (solo inserciones, sin contenido).
-- ---------------------------------------------------------------------------
ALTER TABLE interview_event DROP CONSTRAINT ck_interview_event_kind;
ALTER TABLE interview_event ADD CONSTRAINT ck_interview_event_kind CHECK (kind IN ('NOTICE_ACCEPTED', 'STARTED',
    'ANSWERED', 'TRANSCRIPT_CORRECTED', 'DISCONNECTED', 'RESUMED', 'FINISHED', 'ABANDONED', 'PLATFORM_FAILURE',
    'FOLLOW_UP_ASKED', 'CHANNEL_FALLBACK', 'TIME_LIMIT'));

-- ---------------------------------------------------------------------------
-- Modulo shared: cada llamada en tiempo real deja una fila de consumo con sus segundos y su costo estimado (012-50).
-- ---------------------------------------------------------------------------
ALTER TABLE ai_usage_event DROP CONSTRAINT ck_ai_usage_event_function;
ALTER TABLE ai_usage_event ADD CONSTRAINT ck_ai_usage_event_function CHECK (function IN
    ('CV_READ', 'PRACTICE_SESSION', 'PRACTICE_FEEDBACK', 'EXPLANATION', 'STT', 'TTS', 'DORA_DIALOG',
     'INTERVIEW_SESSION', 'INTERVIEW_EVALUATION', 'EXAM_GENERATION', 'EXAM_GRADING', 'DORA_REALTIME'));
