-- Spec 014, enmienda 1 (D-68/D-69): seguridad y supervision del examen. Solo modulo exam.
-- Nombres en ingles, claves UUID, fechas timestamptz (UTC). Las fotos NUNCA se guardan en la base: solo su ficha
-- (ruta sin nombres, tamano, medidas, huella y plazo de borrado). La imagen vive en el almacenamiento de fotos.

-- ---------------------------------------------------------------------------
-- Nuevos hechos del intento (014-51). Ninguno cambia el estado ni la nota (014-52).
-- ---------------------------------------------------------------------------
ALTER TABLE exam_event DROP CONSTRAINT exam_event_kind_check;
ALTER TABLE exam_event ADD CONSTRAINT exam_event_kind_check CHECK (kind IN (
    'SCREEN_LEFT','SCREEN_RETURNED','FOCUS_LOST','COPY_ATTEMPT','PASTE_ATTEMPT','GAZE_AWAY','CONNECTION_LOST',
    'CONNECTION_RESTORED','WARNING_ISSUED','CLOSED_BY_RULE','STARTED','SUBMITTED','TIMED_OUT','WITHDRAWN',
    'FACE_MISSING','MULTIPLE_FACES','PHONE_VISIBLE','CAMERA_LOST','CAMERA_RESTORED','PRINT_ATTEMPT','CONTEXT_MENU',
    'DETECTION_UNAVAILABLE','NOTICE_ACCEPTED'));
-- Tope por minuto de eventos del cliente (014-51): se cuentan por intento y hora del servidor.
CREATE INDEX exam_event_client_recent ON exam_event(attempt_id, occurred_at) WHERE client_event_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Lo que el servidor entrego y recibio al empezar (014-38, principio 11). Nulo = intento con reglas v1.
-- ---------------------------------------------------------------------------
ALTER TABLE exam_attempt ADD COLUMN proctoring_version varchar(20);
ALTER TABLE exam_attempt ADD COLUMN proctoring_config jsonb;
ALTER TABLE exam_attempt ADD COLUMN detection_status varchar(11);
ALTER TABLE exam_attempt ADD COLUMN calibration jsonb;
ALTER TABLE exam_attempt ADD CONSTRAINT ck_exam_attempt_detection
    CHECK (detection_status IS NULL OR detection_status IN ('AVAILABLE','UNAVAILABLE'));
ALTER TABLE exam_attempt ADD CONSTRAINT ck_exam_attempt_proctoring
    CHECK ((proctoring_version IS NULL) = (proctoring_config IS NULL)
       AND (proctoring_version IS NULL) = (detection_status IS NULL));

-- ---------------------------------------------------------------------------
-- Fotos periodicas y por aviso (014-53 a 014-56). Se borran con el intento (cuenta eliminada) y al vencer el plazo.
-- ---------------------------------------------------------------------------
CREATE TABLE exam_snapshot (
    id               uuid         PRIMARY KEY,
    attempt_id       uuid         NOT NULL REFERENCES exam_attempt(id) ON DELETE CASCADE,
    reason           varchar(8)   NOT NULL CHECK (reason IN ('START','PERIODIC','EVENT')),
    event_client_id  uuid,
    storage_key      varchar(200) NOT NULL UNIQUE,
    byte_size        integer      NOT NULL CHECK (byte_size > 0 AND byte_size <= 153600),
    width            smallint     NOT NULL CHECK (width > 0 AND width <= 640),
    height           smallint     NOT NULL CHECK (height > 0 AND height <= 640),
    sha256           varchar(64)  NOT NULL,
    client_taken_at  timestamptz  NOT NULL,
    created_at       timestamptz  NOT NULL,
    purge_after      timestamptz  NOT NULL,
    CHECK ((reason = 'EVENT') = (event_client_id IS NOT NULL))
);
CREATE INDEX exam_snapshot_attempt ON exam_snapshot(attempt_id, created_at);
CREATE INDEX exam_snapshot_purge ON exam_snapshot(purge_after);
