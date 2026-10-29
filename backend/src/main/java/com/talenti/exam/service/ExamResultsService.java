package com.talenti.exam.service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import com.talenti.exam.ExamResults;
import com.talenti.exam.domain.ProctoringRules;
import com.talenti.exam.domain.ProctoringSummary;
import com.talenti.exam.persistence.ExamStore;
import com.talenti.exam.persistence.ExamStore.AttemptRow;
import com.talenti.exam.snapshot.SnapshotStore;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link ExamResults} para la futura empresa (014-27 y 014-57): el resultado calificado más la supervisión como hechos.
 * Separado de la calificación a propósito: quien usa la IA nunca ve las fichas de las fotos (014-55).
 */
@Service
public class ExamResultsService implements ExamResults {
	private final ExamGradingService grading;
	private final ExamStore store;
	private final SnapshotStore snapshots;
	private final JsonMapper json;
	public ExamResultsService(ExamGradingService grading,ExamStore store,SnapshotStore snapshots,JsonMapper json) {
		this.grading=grading; this.store=store; this.snapshots=snapshots; this.json=json;
	}
	@Override public Optional<Result> forApplication(UUID applicationId) {
		return grading.graded(applicationId).map(result->store.forApplication(applicationId).map(a->result.withProctoring(proctoring(a))).orElse(result));
	}
	@SuppressWarnings("unchecked")
	private Proctoring proctoring(AttemptRow a) {
		var facts=store.events(a.id()).stream().map(e->new ProctoringSummary.Fact(e.kind(),e.clientEventId(),e.occurredAt(),
				e.dataJson()==null?Map.of():(Map<String,Object>)json.readValue(e.dataJson(),Map.class))).toList();
		var photos=snapshots.forAttempt(a.id()).stream().map(s->new ProctoringSummary.Photo(s.id(),s.createdAt(),s.reason(),s.eventClientId())).toList();
		var built=ProctoringSummary.build(facts,photos);
		String thresholds=null;
		if(a.proctoringConfig()!=null) thresholds=json.writeValueAsString(((Map<String,Object>)json.readValue(a.proctoringConfig(),Map.class)).get("thresholds"));
		return new Proctoring(a.noticeVersion(),a.proctoringVersion()!=null,a.proctoringVersion(),a.detectionStatus(),a.calibration(),thresholds,
				built.totals(),built.episodes(),built.snapshots(),ProctoringRules.COMPANY_LABEL);
	}
}
