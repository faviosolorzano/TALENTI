-- Spec 004, enmienda 1 (004-31): la busqueda tolerante a errores de tipeo usa similarity() de la extension pg_trgm.
-- Igual que unaccent (V3), el rol de la aplicacion no es superusuario ni tiene CREATE en la base: la crea
-- db/init/01-init-roles.sh con el administrador de la imagen. V3 ya se aplico en bases existentes, por eso esta
-- comprobacion vive en una migracion nueva. Si el volumen es anterior a esa linea, se detiene aqui con un mensaje claro.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
        RAISE EXCEPTION 'Falta la extension pg_trgm en la base de datos. La crea el script db/init/01-init-roles.sh '
            'solo al crear un volumen vacio: ejecuta "docker compose down -v" para recrearlo (borra los datos locales) '
            'o, en una base existente, pide a un administrador "CREATE EXTENSION pg_trgm;".';
    END IF;
END
$$;
