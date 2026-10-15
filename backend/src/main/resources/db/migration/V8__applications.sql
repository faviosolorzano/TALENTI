-- Spec 008 (postulacion y seguimiento), seccion 5. Modulo application.
-- Se ejecuta con el rol de la aplicacion, sin superusuario. Nombres en ingles, claves UUID, fechas timestamptz (UTC).
-- Las claves foraneas hacia app_user (identity) y vacancy (vacancy) son integridad de datos: el modulo application no
-- lee esas tablas; la vacante se consulta por VacancyLookup. No se guarda copia del perfil (spec 008, duda 2).

CREATE TABLE application (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- S6 borrara la cuenta y, con ella, sus postulaciones (RN-12).
    user_id      uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    vacancy_id   uuid        NOT NULL REFERENCES vacancy (id) ON DELETE RESTRICT,
    -- Los ocho estados de RN-06; en el E2 solo se llega a SUBMITTED y WITHDRAWN.
    status       varchar(20) NOT NULL DEFAULT 'SUBMITTED',
    submitted_at timestamptz NOT NULL,
    withdrawn_at timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_application_status CHECK (status IN ('SUBMITTED', 'WITHDRAWN', 'EXAM_PENDING', 'EXAM_DONE',
        'INTERVIEW_PENDING', 'INTERVIEW_DONE', 'EXPIRED', 'UNDER_REVIEW')),
    -- withdrawn_at existe si y solo si la postulacion esta retirada.
    CONSTRAINT ck_application_withdrawn_at CHECK ((status = 'WITHDRAWN') = (withdrawn_at IS NOT NULL))
);

-- Una sola postulacion activa por estudiante y vacante (RF-31); tras retirarla se puede postular de nuevo (RN-06).
CREATE UNIQUE INDEX uq_application_active ON application (user_id, vacancy_id)
    WHERE status NOT IN ('WITHDRAWN', 'EXPIRED');
CREATE INDEX ix_application_user_submitted ON application (user_id, submitted_at DESC);

-- Historial de estados: origen de la linea de etapas. Solo inserciones.
CREATE TABLE application_stage_event (
    id             uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    application_id uuid        NOT NULL REFERENCES application (id) ON DELETE CASCADE,
    from_status    varchar(20),
    to_status      varchar(20) NOT NULL,
    occurred_at    timestamptz NOT NULL
);

CREATE INDEX ix_application_stage_event_application ON application_stage_event (application_id, occurred_at);
