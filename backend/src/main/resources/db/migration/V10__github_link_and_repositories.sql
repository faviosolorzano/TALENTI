-- Spec 013 (GitHub con inicio de sesion OAuth), seccion 5. Modulo github.
-- Se ejecuta con el rol de la aplicacion, sin superusuario. Nombres en ingles, claves UUID, fechas timestamptz (UTC).
-- Las claves foraneas hacia app_user (identity) son integridad de datos: el modulo github no lee esa tabla.
-- NINGUNA tabla guarda el token de GitHub (013-27): vive solo en memoria durante la vuelta del inicio de sesion.

-- ---------------------------------------------------------------------------
-- Vinculo comprobado con una cuenta de GitHub. Solo existe comprobado (status VERIFIED).
-- ---------------------------------------------------------------------------
CREATE TABLE github_link (
    -- Borrar la cuenta borra el vinculo (RN-12).
    user_id          uuid         PRIMARY KEY REFERENCES app_user (id) ON DELETE CASCADE,
    login            varchar(39)  NOT NULL,
    -- Manda el id numerico, no el nombre (013-05). Unico: una cuenta de GitHub, una persona (013-28).
    github_user_id   bigint       NOT NULL,
    status           varchar(10)  NOT NULL DEFAULT 'VERIFIED',
    verified_at      timestamptz  NOT NULL,
    repos_etag       varchar(200),
    repos_fetched_at timestamptz,
    -- Habilidades sugeridas que la persona rechazo (013-12, duda 5); se borran al desvincular.
    dismissed_slugs  text[]       NOT NULL DEFAULT '{}',
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_github_link_github_user_id UNIQUE (github_user_id),
    CONSTRAINT ck_github_link_status CHECK (status IN ('VERIFIED')),
    CONSTRAINT ck_github_link_login CHECK (btrim(login) <> '')
);

-- ---------------------------------------------------------------------------
-- "state" pendiente del inicio de sesion con GitHub (013-25, 013-26). Se guarda solo su hash SHA-256, ligado a la
-- persona y al hash del id de su sesion de Talenti. Uno vigente por persona. Se borra al usarse o al vencer.
-- ---------------------------------------------------------------------------
CREATE TABLE github_oauth_state (
    state_hash  varchar(64)  PRIMARY KEY,
    user_id     uuid         NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    session_ref varchar(64)  NOT NULL,
    expires_at  timestamptz  NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_github_oauth_state_user UNIQUE (user_id)
);

CREATE INDEX ix_github_oauth_state_expires ON github_oauth_state (expires_at);

-- ---------------------------------------------------------------------------
-- Repositorios publicos de la persona (013-06). Solo los datos que la spec pide: ni estrellas, ni seguidores, ni
-- forks contados, ni commits.
-- ---------------------------------------------------------------------------
CREATE TABLE github_repository (
    id                   uuid          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id              uuid          NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    github_repo_id       bigint        NOT NULL,
    name                 varchar(100)  NOT NULL,
    description          varchar(350),
    html_url             varchar(300)  NOT NULL,
    primary_language     varchar(100),
    topics               text[]        NOT NULL DEFAULT '{}',
    is_fork              boolean       NOT NULL DEFAULT false,
    is_archived          boolean       NOT NULL DEFAULT false,
    pushed_at            timestamptz,
    selected             boolean       NOT NULL DEFAULT false,
    -- [{name, bytes, percent}], calculado por el codigo (013-08); nulo si aun no se leyo.
    languages            jsonb,
    languages_etag       varchar(200),
    languages_fetched_at timestamptz,
    created_at           timestamptz   NOT NULL DEFAULT now(),
    updated_at           timestamptz   NOT NULL DEFAULT now(),
    CONSTRAINT uq_github_repository_user_repo UNIQUE (user_id, github_repo_id),
    CONSTRAINT ck_github_repository_html_url CHECK (html_url LIKE 'https://github.com/%'),
    CONSTRAINT ck_github_repository_topics CHECK (cardinality(topics) <= 20),
    CONSTRAINT ck_github_repository_name CHECK (btrim(name) <> '')
);
