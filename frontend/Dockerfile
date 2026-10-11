# syntax=docker/dockerfile:1

# ---- Stage 1: build the static files with Node (LTS) ----
FROM node:24-alpine AS build
WORKDIR /app

# Install dependencies first so Docker can cache this layer.
COPY package.json package-lock.json ./
RUN npm ci

COPY . .
RUN npm run build

# ---- Stage 2: serve with nginx as an unprivileged user ----
FROM nginxinc/nginx-unprivileged:stable-alpine AS runtime

COPY nginx.conf /etc/nginx/conf.d/default.conf
COPY --from=build /app/dist /usr/share/nginx/html

# The unprivileged image runs as user "nginx". Port 80 works inside Docker because
# Docker allows unprivileged processes to bind low ports in containers.
EXPOSE 80

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://127.0.0.1/healthz >/dev/null || exit 1
