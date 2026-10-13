package com.talenti.profile;

import java.util.UUID;

/**
 * Interfaz pública de lectura del perfil para otros módulos (compatibilidad, postulación, inicio). Solo entrega
 * lo que puede entrar a un cálculo (006-13): nunca el correo, la carrera, el instituto ni el ciclo.
 *
 * <p>Leer no crea filas: un estudiante sin perfil guardado recibe un perfil vacío con 0 % de avance.
 */
public interface ProfileReader {

	ProfileSnapshot snapshot(UUID userId);

	ProfileCompleteness completeness(UUID userId);

}
