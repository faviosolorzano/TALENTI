package com.talenti.exam;

import java.util.Map;

/** Cuerpos comunes de las pruebas del examen (spec 014, enmienda 1). */
public final class ExamTestSupport {
	private ExamTestSupport() { }
	public static final String NOTICE="v2";
	/** Lo que manda el navegador al pulsar "Comenzar" con cámara, pantalla completa y detección calibrada (014-38). */
	public static final Map<String,Object> START=Map.of("cameraReady",true,"fullscreen",true,"detection","AVAILABLE",
			"calibration",Map.of("baseYawDeg",4.5,"basePitchDeg",-3.0));
}
