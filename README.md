# Talenti

Plataforma web con IA que conecta a estudiantes de programación de institutos del Perú con prácticas preprofesionales. Proyecto de Fin de Carrera de TECSUP.

**Estado:** en construcción (Fase 0). Todos los datos de prueba son inventados.

## Estructura
```
backend/              API en Spring Boot (Java 21) con PostgreSQL
frontend/             Aplicación web en React con TypeScript
docker-compose.yml    Levanta todo con un solo comando (llega con la Fase 0)
.env.example          Variables de entorno de ejemplo (sin valores reales)
```

## Cómo probarlo
Necesitas **Docker Desktop** encendido y **Git**.

1. Clona el repositorio y entra a la carpeta.
2. Copia `.env.example` como `.env`. Los valores de ejemplo ya sirven para probar en tu computadora.
3. Levanta todo:
   ```bash
   docker compose up
   ```
4. Abre `http://localhost:3000` en el navegador.

> Los pasos 3 y 4 funcionarán cuando exista el `docker-compose.yml`. Esta guía se actualiza en ese momento.

La IA y la voz funcionan con adaptadores simulados: no necesitas ninguna cuenta ni clave para probar.

## Si algo falla
- Comprueba que Docker Desktop esté abierto y diga que el motor está funcionando.
- Revisa que no haya otro programa usando los puertos `3000` y `8080`.
- Cuéntale a Favio qué viste en pantalla y qué comando ejecutaste.

## Reglas
Mira [CONTRIBUTING.md](CONTRIBUTING.md). Nunca subas el archivo `.env` ni claves.
