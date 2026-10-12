# Cómo se trabaja en este repositorio

## Ramas
`main` siempre está estable y no se programa directo en ella.
| Prefijo | Para qué | Ejemplo |
|---|---|---|
| `feat/` | Una funcionalidad | `feat/s1-identidad` |
| `fix/` | Corregir un error | `fix/login-bloqueo` |
| `chore/` | Configuración, herramientas, dependencias | `chore/ci-basico` |
| `docs/` | Solo documentación | `docs/readme` |

## Commits
Formato: `tipo(ámbito): resumen en presente`. Tipos: `feat`, `fix`, `docs`, `test`, `refactor`, `chore`, `build`, `ci`.
- `feat(identidad): registrar estudiante con consentimiento`
- `test(compatibilidad): cubrir el tope por requisito obligatorio ausente`

Un commit hace una sola cosa.

## Pull Requests
Cada rama se integra a `main` con un Pull Request. Se describe qué cambia, cómo probarlo y que las pruebas pasan. Se integra con "squash".

## Reglas de oro
- **Nunca** se suben secretos, claves ni el archivo `.env`.
- Los datos de prueba son siempre inventados.
- Todo se levanta con `docker compose up`, sin instalar bases de datos a mano.
- Antes de integrar: las pruebas pasan y el sistema arranca sin errores.
