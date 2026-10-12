#!/bin/sh
# Inicialización de la base de datos de Talenti.
#
# La imagen de PostgreSQL ejecuta este script UNA sola vez, al crear un volumen vacío, con el
# superusuario de la imagen (POSTGRES_USER). Hace dos cosas:
#   1. Habilita las extensiones pgvector y unaccent (solo un superusuario puede crearlas en este diseño: el rol de
#      la aplicación no tiene CREATE en la base). unaccent la usa la búsqueda de vacantes sin tildes (spec 004).
#   2. Crea el rol de la aplicación SIN privilegios de superusuario y lo hace dueño del esquema
#      "public". Flyway y el backend se conectan con este rol, nunca con el superusuario.
#
# Variables (vienen del .env por docker-compose.yml; el script no contiene secretos):
#   POSTGRES_USER, POSTGRES_DB   superusuario y base que crea la imagen
#   APP_DB_USER, APP_DB_PASSWORD rol de la aplicación y su contraseña
#
# Si cambias estos valores en .env después de crear el volumen, no surten efecto:
# recrea el volumen con "docker compose down -v" (borra los datos locales).
#
# IMPORTANTE (spec 004): un volumen creado ANTES de que este script creara unaccent no la tiene, y como el script
# solo corre sobre un volumen vacío, el backend no arrancará (la migración V3 falla con un mensaje que lo explica).
# Solución: "docker compose down -v" y volver a levantar. Se pierden los datos locales.

set -eu

: "${APP_DB_USER:?falta APP_DB_USER}"
: "${APP_DB_PASSWORD:?falta APP_DB_PASSWORD}"

# Las variables de psql (:"nombre" para identificadores y :'nombre' para literales) evitan
# armar el SQL concatenando texto y protegen de comillas dentro de la contraseña.
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
	-v app_user="$APP_DB_USER" -v app_password="$APP_DB_PASSWORD" -v app_db="$POSTGRES_DB" <<'SQL'
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS unaccent;

CREATE ROLE :"app_user" WITH LOGIN PASSWORD :'app_password'
	NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;

REVOKE ALL ON DATABASE :"app_db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"app_db" TO :"app_user";

ALTER SCHEMA public OWNER TO :"app_user";
SQL
