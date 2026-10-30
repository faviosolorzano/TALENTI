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

## DORA: modelo de voz y gasto según modo

El servidor elige el modelo usando el modo guardado de la sesión. La práctica privada de entrevistas usa `gpt-realtime-mini`; la entrevista evaluada usa `gpt-realtime-2.1`. La pantalla, voz `marin`, controles, transcripción y evaluación por rúbrica se conservan. No se garantiza que los modelos tengan la misma fluidez o comprensión: se comparan con voz real.

| Propiedad bajo `talenti.ai.dora.realtime` | Variable | Predeterminado |
|---|---|---|
| `models.practice` | `TALENTI_AI_DORA_REALTIME_MODELS_PRACTICE` | `gpt-realtime-mini` |
| `models.interview` | `TALENTI_AI_DORA_REALTIME_MODELS_INTERVIEW` | `gpt-realtime-2.1` |
| `price-per-minute.practice` | `TALENTI_AI_DORA_REALTIME_PRICE_PER_MINUTE_PRACTICE` | `0.10` USD/min estimado |
| `price-per-minute.interview` | `TALENTI_AI_DORA_REALTIME_PRICE_PER_MINUTE_INTERVIEW` | `0.30` USD/min estimado |

Reemplazan `TALENTI_AI_DORA_REALTIME_MODEL` y `TALENTI_AI_DORA_REALTIME_PRICE_PER_MINUTE`; las variables antiguas ya no configuran el modelo ni el presupuesto. Compose normal y el complemento local pasan las cuatro variables nuevas y permiten sobrescribirlas. Con tiempo real encendido deben estar ambos modelos definidos y los precios deben ser positivos. Los topes mensuales existentes no cambian.

El modelo enviado al abrir con éxito queda en `versions.realtimeModel`; una reconexión conserva ese modelo aunque cambie la configuración. Se registra el alias solicitado al proveedor: la respuesta SDP no entrega un identificador de snapshot para resolverlo. Al colgar, el registro de consumo usa el modelo guardado y el precio del modo. Las reservas de otras llamadas abiertas usan sus propios modos. **Finalizar las llamadas abiertas antes de cambiar modelos/precios o desplegar**: las tarifas son configuración de arranque, no un historial de precios por llamada.

**Estimación, consulta 29-10-2026:** la [tabla oficial de precios](https://developers.openai.com/api/docs/pricing) (tabla completa también en su versión `.md`) indica audio por millón de tokens: mini entrada USD 10/salida 20; 2.1 entrada 32/salida 64. Texto mini 0.60/2.40; 2.1 4/24. La [guía de costos de voz](https://developers.openai.com/api/docs/guides/voice-latency-cost?voice-api=realtime) indica 600 tokens por minuto entrante y 1200 saliente. Un minuto entrante más uno saliente da una base de USD 0.030/0.096; usamos aproximadamente tres veces esa base, redondeada hacia arriba, como reserva inicial: 0.10/0.30. Historia, texto y transcripción aparte pueden aumentar el costo. No es tarifa oficial por minuto ni garantiza un techo de cobro; contrastar con el panel. Revisar el precio al cambiar un modelo.

OpenAI marca [gpt-realtime-mini como obsoleto](https://developers.openai.com/api/docs/models/gpt-realtime-mini), con [retiro anunciado para el 20-01-2027](https://developers.openai.com/api/docs/deprecations). Se mantiene el modelo solicitado y no se sustituye automáticamente.

### Comparación manual: dos prácticas de cinco minutos

La realiza Favio después, en un entorno separado autorizado con su clave configurada privadamente. No modificar `main` ni iniciar conversaciones pagadas como parte de las pruebas automáticas.

1. Antes de arrancar el entorno separado, fijar `TALENTI_AI_DORA_REALTIME_MODELS_PRACTICE=gpt-realtime-mini` y precio de práctica `0.10`. Crear una sesión nueva, mismo tema/voz/micrófono; conversar como máximo cinco minutos y colgar.
2. Con todas las llamadas cerradas, reiniciar solo ese entorno con modelo de práctica `gpt-realtime-2.1` y precio de práctica `0.30`. Crear otra sesión nueva y repetir el mismo recorrido durante cinco minutos. No usar una postulación evaluada para esta comparación.
3. Comparar comprensión, pausas, interrupciones y ruido. Revisar el modelo en versiones y consumo en OpenAI; la reserva orientativa es USD 0.50 + 1.50, más las otras funciones de IA, no una promesa de factura. Comprobar que quede cupo antes de cada llamada; no ampliar el tope mensual automáticamente.
4. Restaurar modelo/precio de práctica mini/0.10 para las sesiones nuevas. Entrevista conserva 2.1/0.30 durante toda la comparación. Las sesiones de comparación quedan privadas.

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
