# Iconos del rediseño

## Controles y estados

`sistema/` contiene 19 SVG individuales: search, arrow-right, arrow-left, check, warning, info, close, menu, location, clock, edit, document, upload, chevron-down, external-link, trash, user, education y briefcase.

Son geometrías originales simples preparadas para esta entrega, con `viewBox="0 0 24 24"`, trazo de 2 unidades, extremos redondeados y `currentColor`. Se entregan para uso, modificación y distribución dentro del proyecto TALENTI, sin depender de una biblioteca de iconos de terceros. No proceden de Devicon. Mantener la misma geometría y grosor en todo el producto; tamaños de presentación 20 o 24 px. El área del control sigue siendo al menos 44 px, aunque el dibujo sea menor.

Cada archivo incluye un título y nombre en español para su uso aislado. Dentro de un botón o enlace con texto, ocultar el SVG a la tecnología de asistencia y dejar que el control aporte su nombre. En un botón de solo icono, el botón necesita un nombre que describa la acción concreta, por ejemplo «Eliminar proyecto Sistema de inventario»; el nombre genérico del SVG no sustituye ese contexto. Una verificación o un aviso siempre acompaña a una palabra de estado: no confiar solo en forma o color.

## Tecnologías

Los SVG java, spring, git, docker, react, javascript, typescript, python, postgresql, html5 y css3 proceden de [Devicon](https://github.com/devicons/devicon/tree/4fbcc685c9a85434bc01b1c236c9076f54806236), commit registrado en `DEVICON-COMMIT.txt`. La licencia MIT original se conserva completa en `DEVICON-LICENSE.txt`. No se han convertido a un estilo de trazo propio: los símbolos identificadores mantienen su aspecto de origen.

`tecnologias/sql.svg` es un dibujo propio de una base de datos genérica en azul `#2357E8`. Se entrega con las mismas condiciones de reutilización en TALENTI que los iconos funcionales. **SQL no equivale a PostgreSQL:** para una habilidad denominada SQL, usar este recurso genérico; usar postgresql.svg solamente cuando el dato real sea PostgreSQL. No confundir SQL con un proveedor concreto.

Los nombres y símbolos de tecnologías identifican el conocimiento declarado o solicitado. Su inclusión no representa patrocinio, certificación ni alianza con sus titulares. Conservar los avisos de licencia de cada origen al redistribuir los archivos; no asignar la licencia de los dibujos propios a Devicon ni a las marcas de terceros.

## Reglas de aplicación

- Acción primaria: texto y, si ayuda, una sola flecha; no añadir un icono a cada palabra.
- Compatibilidad: check azul o warning ámbar con estado escrito; no medallas de competencia verificada.
- Ubicación y horario: apoyo a un dato explícito, sin cápsulas decorativas por defecto.
- Menú móvil: botón Menú, apertura y cierre previsibles; no barra lateral persistente.
- Desplegar: usar chevron-down únicamente donde haya un desplegable real. Los requisitos de una vacante son datos de lectura.
- Sin icono específico para una tecnología: texto legible o monograma neutro. No generar una marca que parezca oficial.
