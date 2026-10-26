package com.talenti.exam.adapter;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import com.talenti.exam.port.OpenAnswerGraderPort;

public final class SimulatedOpenAnswerGrader implements OpenAnswerGraderPort {
	private volatile boolean fail, hostile;
	private volatile Duration delay=Duration.ZERO;
	private final List<Request> requests=new CopyOnWriteArrayList<>();
	public void fail(boolean value) { fail=value; }
	public void hostile(boolean value) { hostile=value; }
	public void delay(Duration value) { delay=value; }
	public List<Request> requests() { return List.copyOf(requests); }
	public void reset() { fail=false; hostile=false; delay=Duration.ZERO; requests.clear(); }
	@Override public List<Proposed> grade(Request request) {
		requests.add(request);
		try { Thread.sleep(delay); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("simulated-interrupted"); }
		if(fail) throw new IllegalStateException("simulated-grading-failure");
		String quote=request.answer().substring(0,Math.min(100,request.answer().length()));
		return request.rubric().stream().map(c->new Proposed(c.id(),hostile?99:3,
				hostile?"Tu acento es malo":"La respuesta aborda el criterio técnico.",List.of(hostile?"cita inventada":quote))).toList();
	}
	@Override public String model() { return "simulated-exam"; }
	@Override public String promptVersion() { return "exam-grading-v1"; }
}
