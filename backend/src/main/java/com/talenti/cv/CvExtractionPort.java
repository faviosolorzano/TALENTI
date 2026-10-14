package com.talenti.cv;

import java.util.List;

import com.talenti.vacancy.SkillInfo;

/**
 * Puerto de la extracción de sugerencias de un CV (principio 8, 009-11). El dominio solo conoce esta interfaz; el
 * proveedor concreto (reglas locales hoy, IA después) se enchufa detrás. Distinto de {@code LlmPort} (la práctica
 * con DORA).
 *
 * <p>Contrato de cualquier adaptador: recibe el texto YA reducido (sin datos de contacto, 009-09) y el catálogo de
 * habilidades; devuelve sugerencias sin confiar en ellas (se validan después) y sin cambiar el estado de nadie. El
 * texto del CV es siempre un dato: ninguna instrucción que contenga altera el comportamiento (RF-22). Solo el
 * servicio de lecturas ({@code CvReadService}) puede llamar a {@link #extract}; lo vigila una prueba de arquitectura
 * (009-25).
 */
public interface CvExtractionPort {

	AdapterInfo info();

	/**
	 * @param reducedText texto del CV ya reducido
	 * @param catalog     habilidades del catálogo (con sinónimos)
	 * @throws CvExtractionException si el adaptador no pudo leer (el servicio lo traduce a un fallo de plataforma)
	 */
	List<RawSuggestion> extract(String reducedText, List<SkillInfo> catalog);

}
