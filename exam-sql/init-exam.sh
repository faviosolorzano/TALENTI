#!/bin/sh
set -eu
: "${EXAM_SQL_OWNER_PASSWORD:?Falta EXAM_SQL_OWNER_PASSWORD}"
: "${EXAM_SQL_READER_PASSWORD:?Falta EXAM_SQL_READER_PASSWORD}"
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  --set=db="$POSTGRES_DB" --set=owner_password="$EXAM_SQL_OWNER_PASSWORD" \
  --set=reader_password="$EXAM_SQL_READER_PASSWORD" <<'SQL'
CREATE ROLE exam_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'owner_password';
CREATE ROLE exam_reader LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'reader_password';
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT, CREATE ON DATABASE :"db" TO exam_owner;
GRANT CONNECT ON DATABASE :"db" TO exam_reader;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;
ALTER ROLE exam_reader SET default_transaction_read_only = on;
ALTER ROLE exam_reader SET statement_timeout = '2s';
-- La petición del estudiante no puede desactivar el plazo ni ejecutar SQL dinámico por funciones auxiliares.
DO $$
DECLARE f record;
BEGIN
  FOR f IN SELECT oid::regprocedure::text AS signature FROM pg_proc
    WHERE pronamespace='pg_catalog'::regnamespace AND
      (proname='set_config' OR proname LIKE 'query_to_xml%' OR proname LIKE 'schema_to_xml%'
       OR proname LIKE 'database_to_xml%' OR proname LIKE 'table_to_xml%' OR proname LIKE 'cursor_to_xml%'
       OR proname IN ('pg_read_file','pg_read_binary_file','pg_ls_dir','lo_import','lo_export'))
  LOOP
    EXECUTE format('REVOKE EXECUTE ON FUNCTION %s FROM PUBLIC', f.signature);
  END LOOP;
END;
$$;
SQL
