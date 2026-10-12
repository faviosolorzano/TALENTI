# syntax=docker/dockerfile:1

# ---------- Etapa 1: compilar con Maven y JDK 21 ----------
FROM maven:3.9.12-eclipse-temurin-21-alpine AS build
WORKDIR /build

# Primero solo el pom: la capa de dependencias se reutiliza mientras el pom no cambie.
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline

COPY src ./src
# Las pruebas (Testcontainers) se ejecutan en el CI y en la máquina del equipo, no dentro de la imagen.
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests \
    && cp target/talenti-backend-*.jar /build/app.jar

# ---------- Etapa 2: ejecutar con JRE 21 y usuario sin privilegios ----------
FROM eclipse-temurin:21.0.12.1_1-jre-alpine
RUN addgroup -S -g 10001 talenti && adduser -S -u 10001 -G talenti -H -s /sbin/nologin talenti
WORKDIR /app
COPY --from=build --chown=talenti:talenti /build/app.jar /app/app.jar

USER 10001:10001
EXPOSE 8080

# No hay secretos aquí: la base de datos llega por SPRING_DATASOURCE_* al ejecutar el contenedor.
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Duser.timezone=UTC", "-jar", "/app/app.jar"]
