package com.talenti.exam.service;

import java.time.Clock;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.talenti.exam.ExamSets;
import com.talenti.exam.adapter.ExamBank;
import com.talenti.exam.config.ExamProperties;
import com.talenti.exam.domain.*;
import com.talenti.exam.persistence.ExamStore;
import com.talenti.exam.port.*;
import com.talenti.shared.ai.AiFunction;
import com.talenti.vacancy.*;

@Service
public class ExamAuthoringService implements ExamSets {
	private final ExamStore store;
	private final VacancyLookup vacancies;
	private final ExamAuthoringPort author;
	private final ExamBank bank;
	private final ExamVerifier verifier;
	private final ExamProperties properties;
	private final ExamUsage usage;
	private final ExecutorService executor;
	private final TransactionTemplate tx;
	private final Clock clock;
	private final java.util.Set<UUID> queued=ConcurrentHashMap.newKeySet();
	public ExamAuthoringService(ExamStore store,VacancyLookup vacancies,ExamAuthoringPort author,ExamBank bank,
			CodeExecutionPort execution,ExamProperties properties,ExamUsage usage,
			@Qualifier("examExecutor") ExecutorService executor,PlatformTransactionManager manager,Clock clock) {
		this.store=store; this.vacancies=vacancies; this.author=author; this.bank=bank; this.properties=properties;
		this.usage=usage; this.executor=executor; this.tx=new TransactionTemplate(manager); this.clock=clock;
		this.verifier=new ExamVerifier(execution,properties.runner().limits(),properties.visibleCases(),properties.hiddenCases());
	}
	public ExamStore.SetRow ensure(UUID vacancyId) {
		if(!properties.enabled()) throw ExamErrors.conflict("exam-disabled");
		var existing=store.activeSet(vacancyId);
		if(existing.isPresent()) { if(!existing.get().status().equals("READY")) enqueue(existing.get().id()); return existing.get(); }
		VacancyInfo vacancy=vacancies.findCards(List.of(vacancyId)).stream().findFirst().orElseThrow(ExamErrors::missing);
		Blueprint blueprint=Blueprint.create(vacancy.requirements().stream().map(r->new Blueprint.Technology(r.slug(),r.name(),
				r.kind()==VacancyInfo.RequirementKind.REQUIRED,r.minLevel())).toList(),properties.languageMap(),
				properties.weights().choice(),properties.weights().open(),properties.weights().code());
		if(blueprint.slots().isEmpty()) throw ExamErrors.conflict("exam-not-supported");
		UUID id=tx.execute(status->store.insertSet(vacancyId,blueprint,clock.instant())
				.orElseGet(()->store.activeSet(vacancyId).orElseThrow(()->ExamErrors.conflict("exam-set-busy")).id()));
		enqueue(id);
		return store.findSet(id).orElseThrow();
	}
	private void enqueue(UUID id) {
		if(queued.add(id)) executor.execute(()->{try { generate(id); } finally { queued.remove(id); }});
	}
	@EventListener(ApplicationReadyEvent.class)
	public void resume() { if(properties.enabled()) store.unfinishedSets().forEach(s->enqueue(s.id())); }
	private List<ExamQuestion> generateVersion(Blueprint blueprint,String label) {
		List<ExamQuestion> candidate=null; ExamVerifier.Report report=null;
		for(int attempt=0;attempt<=properties.maxRegenerations()&&usage.available();attempt++) {
			try {
				Future<List<ExamQuestion>> call=executor.submit(()->usage.call(null,AiFunction.EXAM_GENERATION,author.model(),blueprint,()->author.generate(blueprint,label)));
				try { candidate=call.get(properties.gradingTimeout().toMillis(),TimeUnit.MILLISECONDS); }
				finally { if(!call.isDone()) call.cancel(true); }
				candidate=fresh(candidate);
				report=verifier.verify(blueprint,candidate);
				if(report.accepted()) return candidate;
			}
			catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new CodeExecutionPort.Unavailable(); }
			catch(CodeExecutionPort.Unavailable e) { throw e; }
			catch(ExecutionException|TimeoutException|RuntimeException e) { report=null; }
		}
		List<ExamQuestion> fallback=bank.version(blueprint,label);
		if(candidate!=null && report!=null && !report.failures().containsKey(0)) {
			Map<Integer,ExamQuestion> bySlot=new HashMap<>(); candidate.forEach(q->{if(q!=null) bySlot.put(q.slot(),q);});
			var failures=report.failures();
			fallback=fallback.stream().map(q->failures.containsKey(q.slot())?q:bySlot.getOrDefault(q.slot(),q)).toList();
		}
		fallback=fresh(fallback);
		if(!verifier.verify(blueprint,fallback).accepted()) throw new IllegalStateException("BANK_VERIFICATION_FAILED");
		return fallback;
	}
	private static List<ExamQuestion> fresh(List<ExamQuestion> questions) {
		return questions==null?List.of():questions.stream().map(q->q==null?null:q.withId(UUID.randomUUID()).opaqueChoices()).toList();
	}
	private void generate(UUID id) {
		var set=store.findSet(id).orElseThrow();
		if(!java.util.Set.of("GENERATING","VERIFYING").contains(set.status())) return;
		try {
			store.setStatus(id,"VERIFYING",null);
			List<List<ExamQuestion>> versions=new ArrayList<>();
			for(String label:List.of("A","B","C")) versions.add(generateVersion(set.blueprint(),label));
			boolean ai=versions.stream().flatMap(List::stream).anyMatch(q->q.source()==ExamQuestion.Source.AI);
			boolean base=versions.stream().flatMap(List::stream).anyMatch(q->q.source()==ExamQuestion.Source.BANK);
			tx.executeWithoutResult(status->{
				for(int i=0;i<3;i++) store.insertVersion(UUID.randomUUID(),id,List.of("A","B","C").get(i),versions.get(i),
						new ExamVerifier.Report(true,Map.of(),ExamVerifier.VERSION));
				store.ready(id,ai?(base?"MIXED":"AI"):"BANK",author.model(),author.promptVersion(),bank.version(),clock.instant());
			});
		}
		catch(CodeExecutionPort.Unavailable e) { store.setStatus(id,"FAILED","EXECUTION_UNAVAILABLE"); }
		catch(RuntimeException e) { store.setStatus(id,"FAILED","VERIFICATION_FAILED"); }
	}
	@Override public Optional<ExamSets.Set> forVacancy(UUID vacancyId) {
		return store.latestSet(vacancyId).map(s->new ExamSets.Set(s.id(),s.vacancyId(),s.revision(),s.status(),s.source(),
				store.versions(s.id()).stream().map(v->new ExamSets.Version(v.id(),v.label(),v.locked(),v.verificationJson())).toList(),s.readyAt()));
	}
}
