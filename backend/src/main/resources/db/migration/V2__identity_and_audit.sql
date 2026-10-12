-- Spec 001 (identidad y consentimiento), seccion 5.
-- Se ejecuta con el rol de la aplicacion (dueno del esquema public), sin superusuario.
-- Convenciones: nombres en ingles, claves UUID, fechas timestamptz (siempre UTC).

-- ---------------------------------------------------------------------------
-- Modulo identity: usuarios
-- ---------------------------------------------------------------------------
CREATE TABLE app_user (
    id                 uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    email              varchar(254) NOT NULL,
    password_hash      varchar(255) NOT NULL,
    role               varchar(20)  NOT NULL,
    failed_login_count integer      NOT NULL DEFAULT 0,
    locked_until       timestamptz,
    -- Sin uso todavia (001-24): la verificacion del correo llegara con el envio de correos.
    email_verified_at  timestamptz,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    updated_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_email UNIQUE (email),
    CONSTRAINT ck_app_user_email_lowercase CHECK (email = lower(email)),
    CONSTRAINT ck_app_user_role CHECK (role IN ('STUDENT', 'COMPANY', 'ADMIN')),
    CONSTRAINT ck_app_user_failed_login_count CHECK (failed_login_count >= 0)
);

-- ---------------------------------------------------------------------------
-- Modulo identity: permisos por finalidad (RF-47, RN-15). Solo se insertan.
-- ---------------------------------------------------------------------------
CREATE TABLE consent_record (
    id              uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- S6 borrara los consentimientos junto con la cuenta (RN-12).
    user_id         uuid        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    purpose         varchar(20) NOT NULL,
    consent_version varchar(20) NOT NULL,
    accepted_at     timestamptz NOT NULL,
    CONSTRAINT ck_consent_record_purpose CHECK (purpose IN ('TERMS', 'ACCOUNT_DATA', 'CV_AI', 'VOICE_AI'))
);

CREATE INDEX ix_consent_record_user_purpose ON consent_record (user_id, purpose);

-- ---------------------------------------------------------------------------
-- Modulo shared: auditoria (RF-46). Solo inserciones; sin datos personales en details.
-- ---------------------------------------------------------------------------
CREATE TABLE audit_event (
    id            uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at   timestamptz  NOT NULL,
    event_type    varchar(50)  NOT NULL,
    -- Sin clave foranea a proposito: el registro sobrevive a la cuenta (RN-12).
    actor_user_id uuid,
    subject_type  varchar(50),
    subject_id    uuid,
    correlation_id uuid,
    details       jsonb        NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX ix_audit_event_actor ON audit_event (actor_user_id, occurred_at);
CREATE INDEX ix_audit_event_type ON audit_event (event_type, occurred_at);
