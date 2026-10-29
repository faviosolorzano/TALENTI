-- Spec 015 (D-70/D-71): DORA explica la compatibilidad y que practicar. Modulo matching.
-- Nombres en ingles, claves UUID, fechas timestamptz (UTC). Solo se guardan explicaciones REDACTADAS POR LA IA y ya
-- validadas (las de plantilla son gratis y no se guardan, 015-12). Nunca se guarda lo enviado a la IA: solo su huella.
-- Sin cambios en ai_usage_event: la funcion EXPLANATION existe desde V7 (reservada para S11).

-- ---------------------------------------------------------------------------
-- Una explicacion vigente por persona y vacante; se sobrescribe al regenerar (huella distinta).
-- input_hash: SHA-256 de lo enviado + version de formula + version de instrucciones + modelo (015-12).
-- Se borra con la cuenta o con la vacante (T8, 015-19).
-- ---------------------------------------------------------------------------
CREATE TABLE compatibility_explanation (
    id               uuid         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id          uuid         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    vacancy_id       uuid         NOT NULL REFERENCES vacancy (id) ON DELETE CASCADE,
    input_hash       char(64)     NOT NULL,
    formula_version  varchar(10),
    prompt_version   varchar(40),
    template_version varchar(20),
    model            varchar(60),
    adapter          varchar(40),
    content          jsonb        NOT NULL,
    generated_at     timestamptz  NOT NULL,
    CONSTRAINT uq_compatibility_explanation_user_vacancy UNIQUE (user_id, vacancy_id),
    CONSTRAINT ck_compatibility_explanation_content_size CHECK (octet_length(content::text) <= 8000)
);

-- El borrado en cascada de una vacante busca por vacancy_id (la clave unica ya cubre user_id).
CREATE INDEX ix_compatibility_explanation_vacancy ON compatibility_explanation (vacancy_id);
