package com.talenti.exam.port;

import java.time.Duration;
import java.util.List;
import com.talenti.exam.domain.ExamQuestion.Case;
import com.talenti.exam.domain.ExamQuestion.Language;
import com.talenti.exam.domain.ExamQuestion.SqlTable;

/** El dominio pide ejecución; nunca conoce procesos, Docker ni credenciales. */
public interface CodeExecutionPort {
	List<Result> run(Language language, String code, List<Case> cases, Limits limits);
	record Limits(Duration caseTimeout, Duration compileTimeout, int memoryMb, int outputBytes, int codeBytes,
			int processes) { }
	enum Error { NONE, COMPILE, SYNTAX, RUNTIME, TIMEOUT, OUTPUT_LIMIT, MEMORY_LIMIT }
	record Result(boolean completed, String output, Error error, long ms, SqlTable table) {
		@Override public String toString() { return "Result[completed=" + completed + ", error=" + error + "]"; }
	}
	/** Indisponibilidad de infraestructura, nunca una respuesta incorrecta del estudiante. */
	class Unavailable extends RuntimeException {
		public Unavailable() { super("La ejecución no está disponible", null, false, false); }
	}
}
