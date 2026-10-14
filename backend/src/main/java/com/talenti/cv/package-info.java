/**
 * Módulo cv: lector de CV (spec 009). Sube un PDF, extrae sugerencias de perfil por el puerto
 * {@link com.talenti.cv.CvExtractionPort} (adaptador local por reglas y adaptador de IA) y aplica al perfil solo lo que
 * la persona confirma, por {@code ProfileEditor}. Controla el consumo de IA con {@code AiUsageQuota} y
 * {@code AiUsageRecorder} (módulo shared).
 */
package com.talenti.cv;
