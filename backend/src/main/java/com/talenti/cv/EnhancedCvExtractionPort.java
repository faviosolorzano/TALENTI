package com.talenti.cv;

/**
 * Marca del adaptador de la lectura mejorada (IA externa, 009-27). Si hay uno y {@code talenti.ai.cv.enabled=true},
 * la persona puede pedir la lectura mejorada; el adaptador por reglas nunca lleva esta marca.
 */
public interface EnhancedCvExtractionPort extends CvExtractionPort {
}
