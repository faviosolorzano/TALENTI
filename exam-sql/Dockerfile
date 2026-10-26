# Misma distribución PostgreSQL 18.4 ya verificada por las pruebas del proyecto.
# No se crean tablas de Talenti ni se habilita la extensión vector en esta base separada.
FROM pgvector/pgvector:0.8.2-pg18-trixie
COPY init-exam.sh /docker-entrypoint-initdb.d/10-exam.sh
RUN chmod 755 /docker-entrypoint-initdb.d/10-exam.sh
