package com.talenti.cv;

/**
 * Quién produjo una lectura (RF-45, principio 11): se guarda con cada análisis.
 *
 * @param name           nombre estable del adaptador, por ejemplo {@code rules} u {@code openai}
 * @param version        versión del adaptador
 * @param ruleSetVersion versión del conjunto de reglas o de las instrucciones
 * @param model          modelo de IA; nulo en el adaptador local
 */
public record AdapterInfo(String name, String version, String ruleSetVersion, String model) {
}
