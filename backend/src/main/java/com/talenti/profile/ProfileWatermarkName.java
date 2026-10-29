package com.talenti.profile;

import java.util.Optional;
import java.util.UUID;

/**
 * Interfaz pública solo para la marca de agua del examen (spec 014, enmienda 1, 014-43): entrega el primer nombre y el
 * primer apellido declarados ("Ana Pérez"), nunca el correo, el teléfono ni el resto del perfil. Va separada de
 * {@link ProfileReader} (que alimenta cálculos y no trae datos personales) para que nadie más la pida por error.
 */
public interface ProfileWatermarkName {

	/** "Nombre Apellido" con lo declarado; solo uno de los dos si falta el otro; vacío si no hay ninguno. */
	Optional<String> watermarkName(UUID userId);

}
