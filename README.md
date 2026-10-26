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

## Probar la lectura de CV con IA real
Por defecto todo es simulado y no se envía nada a nadie. Para probar con tu propia clave de OpenAI:
1. En la página de OpenAI crea una clave de API y, en la configuración de facturación, fija un **límite de gasto mensual** bajo. Copia la clave (empieza con `sk-`).
2. En la carpeta del código, en CMD: `copy .env.example .env` (si ya tienes un `.env`, solo edítalo). El `.env` no se sube al repositorio: no lo compartas.
3. Abre `.env` y cambia estas líneas:
   - `AI_PROVIDER=openai`
   - `OPENAI_API_KEY=` y pega tu clave
   - `TALENTI_AI_CV_ENABLED=true`
   - `TALENTI_AI_CV_MODEL=` un modelo económico, por ejemplo `gpt-4o-mini` (revisa el nombre y el precio vigentes en OpenAI)
4. Reinicia sin borrar nada: `docker compose --env-file .env up -d --build` (no uses `down -v`: borraría tu base de datos).
5. Entra a `http://localhost:3000`, ve a Mi perfil, sube un CV en PDF y pulsa **Leer mejor con IA**. Las sugerencias leídas por la IA llevan el rótulo de IA y tú decides cuáles aceptar.
6. Para volver al modo simulado: pon `AI_PROVIDER=simulated` y `TALENTI_AI_CV_ENABLED=false` (puedes dejar la clave vacía) y repite el paso 4.

Qué pasa si algo sale mal: cada persona tiene 3 lecturas con IA al mes y hay topes por día y por mes para todos (`TALENTI_AI_LIMITS_*`); al acabarse, la pantalla lo dice y la lectura básica sigue disponible. Si OpenAI falla o tarda más de 30 segundos, se usa la lectura básica y no se descuenta ninguna lectura. Si falta la clave o el modelo, el backend no arranca y el mensaje (`docker compose logs backend`) dice qué falta; la clave nunca aparece en los registros.

## Conectar GitHub (solo servidor por ahora)
Apagado por defecto: sin configurarlo, `POST /api/v1/github/oauth/start` responde 503 `github-oauth-disabled` y el resto sigue igual. Para encenderlo en tu computadora:
1. En GitHub: Settings > Developer settings > OAuth Apps > New OAuth App. *Homepage URL* `http://localhost:3000`; *Authorization callback URL* `http://localhost:3000/api/v1/github/oauth/callback`; sin marcar *Enable Device Flow*. Copia el *Client ID* y genera un *Client secret*.
2. En tu `.env` (nunca en otro archivo ni en el chat): `TALENTI_GITHUB_OAUTH_ENABLED=true`, `GITHUB_CLIENT_ID=...` y `GITHUB_CLIENT_SECRET=...`. Si usas otro puerto, cambia también `TALENTI_GITHUB_OAUTH_REDIRECT_URI` y `TALENTI_GITHUB_OAUTH_RETURN_URL` (y la URL de la OAuth App).
3. Reinicia sin borrar nada: `docker compose --env-file .env up -d --build`.

Talenti no pide permisos extra (sin `scope`: solo lo público) y no guarda la llave que le entrega GitHub. Si falta una variable con la conexión encendida, el backend no arranca y `docker compose logs backend` dice cuál.

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

## Examen técnico (servidor, spec 014)

El módulo `exam` prepara tres versiones por vacante, guarda un intento por postulación y califica sin devolver al estudiante notas, soluciones ni casos ocultos. Las rutas están bajo `/api/v1/exam-attempts`, `/api/v1/applications/{id}/exam` y `/api/v1/admin/vacancies/{id}/exam-set`. El cliente visual sigue pendiente.

Compose incorpora dos servicios necesarios: `code-runner` ejecuta Java 21, JavaScript y Python sin red; `exam-sql` ejecuta SQL en otra base, sin tablas de Talenti y con un lector sin permisos de escritura. El backend solo intercambia archivos con el ejecutor; no tiene acceso al motor Docker. Los programas usan usuarios separados y límites de tiempo, memoria, procesos y salida. La base del examen y la base principal no comparten usuarios ni datos.

Antes de iniciar esta versión, el responsable debe definir tres contraseñas nuevas y diferentes en su configuración local: `EXAM_SQL_BOOTSTRAP_PASSWORD`, `EXAM_SQL_OWNER_PASSWORD` y `EXAM_SQL_READER_PASSWORD`. El ejemplo público deja esos valores vacíos. Nunca se deben reutilizar las contraseñas de la base principal. La IA del examen permanece apagada (`TALENTI_AI_EXAM_ENABLED=false`); encenderla requiere elegir modelo y precios, y configurar la clave de OpenAI. Las pruebas automáticas usan un proveedor simulado.

Las pruebas `RunnerIsolationTests` y `SqlIsolationTests` construyen sus propias imágenes y prueban ataques en contenedores temporales. Para reutilizar una imagen del ejecutor recién construida: `docker build -t talenti-s14b-code-runner:dev code-runner`, después, desde `backend`, `mvn -Dtalenti.test.runner-image=talenti-s14b-code-runner:dev test`. No reutilizar una imagen antigua después de cambiar `code-runner`.
