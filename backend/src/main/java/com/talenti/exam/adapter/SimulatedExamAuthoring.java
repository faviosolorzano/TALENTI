package com.talenti.exam.adapter;

import java.time.Duration;
import java.util.List;
import com.talenti.exam.domain.*;
import com.talenti.exam.port.ExamAuthoringPort;

public final class SimulatedExamAuthoring implements ExamAuthoringPort {
	private final ExamBank bank;
	private volatile boolean fail;
	private volatile Duration delay=Duration.ZERO;
	public SimulatedExamAuthoring(ExamBank bank) { this.bank=bank; }
	public void fail(boolean value) { fail=value; }
	public void delay(Duration value) { delay=value; }
	public void reset() { fail=false; delay=Duration.ZERO; }
	@Override public List<ExamQuestion> generate(Blueprint blueprint,String label) {
		try { Thread.sleep(delay); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("simulated-interrupted"); }
		if(fail) throw new IllegalStateException("simulated-authoring-failure");
		return bank.version(blueprint,label);
	}
	@Override public String model() { return "simulated-exam"; }
	@Override public String promptVersion() { return "exam-authoring-v1"; }
}
