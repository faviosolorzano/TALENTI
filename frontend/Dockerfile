# syntax=docker/dockerfile:1

# Las etiquetas de las imágenes base están fijadas a una versión concreta para que
# la construcción sea reproducible. Para actualizarlas, verifica antes con
# `docker manifest inspect <imagen:etiqueta>`.

# ---- Etapa 1: compilar los archivos estáticos con Node (LTS 24) ----
FROM node:24.21.0-alpine3.24 AS build
WORKDIR /app

# Se instalan primero las dependencias para que Docker pueda reutilizar esta capa.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---- Etapa 2: servir con nginx como usuario sin privilegios ----
FROM nginxinc/nginx-unprivileged:1.30.5-alpine3.24 AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

# La imagen corre como el usuario "nginx" (uid 101) y escucha en el puerto 8080,
# así no necesita privilegios ni capacidades. Es compatible con `read_only`,
# `cap_drop: ALL` y `no-new-privileges`: el pid y las cachés temporales de nginx
# van a /tmp (el contenedor debe montar un tmpfs en /tmp cuando use `read_only`).
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1
