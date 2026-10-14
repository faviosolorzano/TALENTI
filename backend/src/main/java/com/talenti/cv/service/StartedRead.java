package com.talenti.cv.service;

import java.util.UUID;

/**
 * Respuesta de subir un CV o pedir otra lectura (202): el análisis queda esperando y la persona consulta su estado.
 *
 * @param reused verdadero si se subió el mismo archivo con el análisis abierto y se devolvió ese (009-26)
 */
public record StartedRead(UUID id, String status, boolean reused, boolean degraded, String degradedReason) {
}
