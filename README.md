# Talenti

Plataforma web con IA que conecta a estudiantes de programación de institutos del Perú con prácticas preprofesionales. Proyecto de Fin de Carrera de TECSUP.

**Estado:** en construcción (Fase 0 lista: el sistema arranca; las pantallas aún dicen "En construcción"). Todos los datos de prueba son inventados.

## Estructura
```
backend/              API en Spring Boot 4.1 (Java 21) con PostgreSQL 18 y pgvector
frontend/             Aplicación web en React con TypeScript
docker-compose.yml    Levanta todo con un solo comando
.env.example          Variables de entorno de ejemplo (sin valores reales)
```

## Cómo probarlo
Necesitas **Docker Desktop** encendido y **Git**.

1. Clona el repositorio y entra a la carpeta.
2. Copia `.env.example` como `.env`. Los valores de ejemplo sirven para probar en tu computadora.
   ```bash
   cp .env.example .env
   ```
3. Levanta todo (la primera vez descarga imágenes y puede tardar varios minutos):
   ```bash
   docker compose up --build
   ```
4. Abre `http://localhost:3000` en el navegador.

Para apagar todo: `docker compose down`. Para borrar también los datos de la base de prueba: `docker compose down -v`.

La IA y la voz funcionan con adaptadores simulados: no necesitas ninguna cuenta ni clave para probar.

## Cuenta de demostración
Con los datos de ejemplo activos (Docker Compose los enciende por defecto, `TALENTI_DEMO_DATA=true`) existe una cuenta de estudiante con el perfil lleno, para presentar sin crear una cuenta:

| Dato | Valor |
|---|---|
| Correo | `demo@example.test` |
| Contraseña | `Demo-Talenti-2026` |

Es una cuenta inventada y su contraseña es pública a propósito: solo existe mientras la semilla de ejemplo esté activa (apagada por defecto y nunca para un despliegue real). Muestra una compatibilidad alta con la vacante "Practicante Backend Java".

## Pruebas
- Backend (necesita Docker, usa PostgreSQL real en un contenedor): `cd backend && ./mvnw test`
- Frontend: `cd frontend && npm install && npm run test`

## Si algo falla
- Comprueba que Docker Desktop esté abierto y diga que el motor está funcionando.
- Revisa que no haya otro programa usando el puerto `3000`.
- Si `docker compose up` dice que falta una variable, no copiaste `.env.example` como `.env`.
- Cuéntale a Favio qué viste en pantalla y qué comando ejecutaste.

## Reglas
Mira [CONTRIBUTING.md](CONTRIBUTING.md). Nunca subas el archivo `.env` ni claves.
