package com.talenti.profile;

import java.util.UUID;

/**
 * Interfaz pública de escritura del perfil para otros módulos (hoy, el lector de CV de la spec 009). Aplica las
 * mismas validaciones que las rutas de {@code /profile}. Se une a la transacción que ya esté abierta, así quien la
 * llama puede aplicar varias cosas juntas o ninguna.
 *
 * <p>Esta interfaz, {@link ProfileView} y {@link ConfirmedChanges} muestran el perfil completo (carrera, instituto,
 * nombre y presentación) o lo modifican: solo el módulo del lector de CV (paquete {@code cv}) puede usarlas. Los demás
 * módulos leen el perfil por {@link ProfileReader}. Lo vigila {@code ProfileArchitectureTests}.
 */
public interface ProfileEditor {

	/** El perfil completo de la persona, como lo ve ella. No crea filas. */
	ProfileView current(UUID userId);

	/**
	 * Aplica lo que la persona confirmó y devuelve el perfil resultante, con el porcentaje nuevo.
	 *
	 * @throws com.talenti.shared.web.ValidationFailedException 422 si algún valor incumple las reglas de 006 (con la
	 *         ruta del campo, por ejemplo {@code skills[2].level}) o si se pasa del límite de proyectos
	 */
	ProfileView applyConfirmed(UUID userId, ConfirmedChanges changes);

}
