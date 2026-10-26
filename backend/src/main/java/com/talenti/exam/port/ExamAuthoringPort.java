package com.talenti.exam.port;

import java.util.List;
import com.talenti.exam.domain.Blueprint;
import com.talenti.exam.domain.ExamQuestion;

/** Una versión por llamada; el único contexto es el plano de tecnologías, nunca datos de estudiantes. */
public interface ExamAuthoringPort {
	List<ExamQuestion> generate(Blueprint blueprint, String label);
	String model();
	String promptVersion();
}
