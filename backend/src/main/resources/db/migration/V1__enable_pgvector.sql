-- Habilita pgvector para guardar y comparar los vectores de perfiles y vacantes (módulo matching).
-- La extensión ya la crea db/init/01-init-roles.sh con el superusuario de la imagen; el rol de la
-- aplicación (sin superusuario) no puede crearla, pero IF NOT EXISTS la omite si ya existe.
-- Esta línea solo actúa en una base de desarrollo local sin ese script.
CREATE EXTENSION IF NOT EXISTS vector;
