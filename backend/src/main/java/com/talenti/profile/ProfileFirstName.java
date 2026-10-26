package com.talenti.profile;

import java.util.Optional;
import java.util.UUID;

/**
 * Interfaz pública aparte de {@link ProfileReader} para que DORA llame a la persona por su nombre (spec 012, 012-24,
 * D-42): entrega SOLO el primer nombre (la primera palabra de "Nombres"), nunca apellidos, correo ni teléfono. Va
 * separada a propósito: {@link ProfileReader} alimenta cálculos y no trae datos personales (006-13), y quien no
 * necesita el nombre no lo puede pedir por error.
 */
public interface ProfileFirstName {

	/** El primer nombre si la persona lo declaró; vacío si no. */
	Optional<String> firstName(UUID userId);

}
