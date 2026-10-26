package com.talenti.exam.adapter;

import java.util.List;
import java.util.function.BiFunction;
import com.talenti.exam.domain.ExamQuestion;
import com.talenti.exam.port.CodeExecutionPort;

/** Solo para pruebas explícitas con runner.mode=simulated; no ejecuta procesos. */
public final class SimulatedCodeExecution implements CodeExecutionPort {
	private volatile boolean unavailable;
	private volatile BiFunction<String,List<ExamQuestion.Case>,List<Result>> behavior;
	public void unavailable(boolean value) { unavailable=value; }
	public void behavior(BiFunction<String,List<ExamQuestion.Case>,List<Result>> value) { behavior=value; }
	public void reset() { unavailable=false; behavior=null; }
	@Override public List<Result> run(ExamQuestion.Language language,String code,List<ExamQuestion.Case> cases,Limits limits) {
		if(unavailable) throw new Unavailable();
		var configured=behavior;
		if(configured!=null) return configured.apply(code,cases);
		boolean initial=code!=null&&code.contains("EXAM_INITIAL");
		return cases.stream().map(c->new Result(!initial,initial?"":c.expectedOutput(),initial?Error.COMPILE:Error.NONE,1,
				initial?null:c.expectedTable())).toList();
	}
}
