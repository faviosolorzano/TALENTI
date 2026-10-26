# syntax=docker/dockerfile:1
FROM eclipse-temurin:21.0.12.1_1-jdk-alpine AS java
FROM node:24.21.0-alpine3.24 AS node
FROM python:3.14-alpine3.24
RUN apk add --no-cache libstdc++ libseccomp tini \
    && addgroup -g 10001 runner && adduser -D -u 10001 -G runner -H runner \
    && mkdir /jobs && chown 10001:10001 /jobs && chmod 700 /jobs
COPY --from=java /opt/java/openjdk /opt/java/openjdk
COPY --from=node /usr/local/bin/node /usr/local/bin/node
COPY runner.py /opt/runner/runner.py
ENV JAVA_HOME=/opt/java/openjdk PATH=/opt/java/openjdk/bin:/usr/local/bin:/usr/bin:/bin PYTHONDONTWRITEBYTECODE=1
# El arranque conserva únicamente SETUID/SETGID/KILL y pasa inmediatamente a UID 10001,
# incluido tini. Los programas usan otros UID y cero capacidades.
ENTRYPOINT ["python", "-u", "/opt/runner/runner.py"]
