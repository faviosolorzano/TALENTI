package com.talenti.practice.service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.talenti.application.ApplicationStages;
import com.talenti.practice.InterviewResults;
import com.talenti.practice.config.DoraProperties;
import com.talenti.practice.domain.Basis;
import com.talenti.practice.domain.QuestionBank;
import com.talenti.practice.domain.QuestionPlanner;
import com.talenti.practice.domain.Rubric;
import com.talenti.practice.domain.RubricValidator;
import com.talenti.practice.domain.SessionMode;
import com.talenti.practice.domain.SessionStatus;
import com.talenti.practice.persistence.PracticeRepository;
import com.talenti.practice.persistence.PracticeRepository.FeedbackRow;
import com.talenti.practice.persistence.PracticeRepository.QuestionRow;
import com.talenti.practice.persistence.PracticeRepository.SessionRow;
import com.talenti.practice.persistence.PracticeRepository.TurnRow;
import com.talenti.practice.port.ConversationPort;
import com.talenti.practice.port.LlmPort;
import com.talenti.shared.web.ApiException;
import com.talenti.vacancy.VacancyInfo;
import com.talenti.vacancy.VacancyLookup;

/**
 * La entrevista evaluada de una postulación (012-23 a 012-28, 012-35). Usa el mismo motor que la práctica, con
 * preguntas SOLO desde los requisitos de la vacante, aviso aceptado y versionado, y hechos en {@code interview_event}.
 * No usa el cupo personal de práctica (no puede perjudicar una postulación), solo el tope global de gasto. No hay
 * recomendación ni ranking: la empresa decide (principio 1).
 */
@Service
public class InterviewService implements InterviewResults {

	private final PracticeRepository repository;

	private final ApplicationStages stages;

	private final VacancyLookup vacancies;

	private final SessionPlanning planning;

	private final SessionViews views;

	private final DoraService dora;

	private final UsageMeter usage;

	private final ConversationPort conversation;

	private final QuestionBank bank;

	private final Rubric rubric;

	private final InterviewNotice notice;

	private final DoraProperties properties;

	private final TransactionTemplate transactions;

	private final Clock clock;

	InterviewService(PracticeRepository repository, ApplicationStages stages, VacancyLookup vacancies,
			SessionPlanning planning, SessionViews views, DoraService dora, UsageMeter usage,
			ConversationPort conversation, QuestionBank bank, Rubric rubric, InterviewNotice notice,
			DoraProperties properties, TransactionTemplate transactions, Clock clock) {
		this.repository = repository;
		this.stages = stages;
		this.vacancies = vacancies;
		this.planning = planning;
		this.views = views;
		this.dora = dora;
		this.usage = usage;
		this.conversation = conversation;
		this.bank = bank;
		this.rubric = rubric;
		this.notice = notice;
		this.properties = properties;
		this.transactions = transactions;
		this.clock = clock;
	}

	// ------------------------------------------------------------------------------------------ crear

	/** 012-23, 012-26, 012-28, 012-35: crea el intento de entrevista y lleva la postulación a INTERVIEW_PENDING. */
	public Views.Session create(UUID userId, UUID applicationId, Boolean noticeAccepted, String noticeVersion) {
		ApplicationStages.Owned application = stages.findOwned(applicationId, userId)
				.orElseThrow(() -> DoraErrors.notFound("application-not-found"));
		if (!properties.interview().enabled()) {
			throw DoraErrors.conflict("interview-disabled");
		}
		if (!Boolean.TRUE.equals(noticeAccepted)) {
			throw DoraErrors.invalid("noticeAccepted", "must-be-accepted");
		}
		if (noticeVersion == null || noticeVersion.isBlank()) {
			throw DoraErrors.invalid("noticeVersion", "required");
		}
		if (!noticeVersion.strip().equals(notice.version())) {
			throw DoraErrors.conflict("notice-version-outdated");
		}
		List<SessionRow> attempts = repository.interviews(applicationId);
		if (attempts.stream().anyMatch(s -> s.status().isOpen())) {
			throw DoraErrors.conflict("session-open");
		}
		boolean firstAttempt = attempts.isEmpty();
		boolean canStart = switch (application.stage()) {
			case SUBMITTED, EXAM_DONE -> firstAttempt;
			case INTERVIEW_PENDING -> canRetry(attempts);
			default -> false;
		};
		if (!canStart) {
			throw DoraErrors.conflict(application.stage() == ApplicationStages.Stage.INTERVIEW_PENDING
					? "interview-attempts-exhausted" : "interview-not-allowed");
		}
		if (repository.findOpen(userId).isPresent()) {
			throw DoraErrors.conflict("session-open");
		}
		if (usage.spendReached()) {
			throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "interview-unavailable");
		}
		List<QuestionPlanner.BasisRef> basis = new ArrayList<>();
		for (VacancyInfo vacancy : vacancies.findCards(List.of(application.vacancyId()))) {
			for (VacancyInfo.Requirement requirement : vacancy.requirements()) {
				basis.add(new QuestionPlanner.BasisRef("requirement:" + requirement.slug(), requirement.name()));
			}
		}
		QuestionPlanner.Plan plan = planning.plan(userId, LlmPort.Purpose.INTERVIEW, basis,
				properties.interview().questionCount());
		UUID id = UUID.randomUUID();
		Instant now = clock.instant();
		ConversationPort.Info info = conversation.info();
		int attempt = attempts.size() + 1;
		try {
			transactions.executeWithoutResult(tx -> {
				repository.insertSession(new PracticeRepository.NewSession(id, userId, SessionMode.INTERVIEW,
						Basis.VACANCY, application.vacancyId(), applicationId, null, plan.questions().size(), true,
						attempt, notice.version(), now, info.adapter(), info.llmModel(), info.sttModel(),
						info.ttsModel(), info.ttsVoice(), info.promptVersion(), rubric.version(), bank.version()),
						plan.questions().stream().map(q -> new PracticeRepository.NewQuestion(q.position(), q.text(),
								q.source(), q.basisRef())).toList(),
						now);
				repository.addEvent(id, "NOTICE_ACCEPTED", "{\"noticeVersion\":\"" + notice.version() + "\",\"attempt\":"
						+ attempt + "}", now);
				if (firstAttempt && !stages.startInterview(applicationId, userId)) {
					throw DoraErrors.conflict("interview-not-allowed");
				}
			});
		}
		catch (org.springframework.dao.DuplicateKeyException open) {
			throw DoraErrors.conflict("session-open");
		}
		if (plan.degraded()) {
			repository.addDegraded(id, "QUESTION_BANK");
		}
		SessionRow session = repository.findById(id).orElseThrow();
		dora.prefetchOpening(session);
		dora.prefetchQuestion(session, repository.questions(id), 1);
		return views.session(session, false);
	}

	/** 012-26: un nuevo intento solo si el anterior terminó por desconexión o falla, hasta el máximo. */
	private boolean canRetry(List<SessionRow> attempts) {
		if (attempts.isEmpty() || attempts.size() >= properties.interview().maxAttempts()) {
			return false;
		}
		SessionStatus last = attempts.get(attempts.size() - 1).status();
		return last == SessionStatus.ABANDONED || last == SessionStatus.FAILED;
	}

	// ------------------------------------------------------------------------------------------ ver (estudiante)

	/** 012-27 (duda 7): estado, preguntas y transcripciones del último intento; sin niveles. Vacío si no hubo. */
	public Optional<Views.InterviewView> view(UUID userId, UUID applicationId) {
		ApplicationStages.Owned application = stages.findOwned(applicationId, userId)
				.orElseThrow(() -> DoraErrors.notFound("application-not-found"));
		List<SessionRow> attempts = repository.interviews(applicationId);
		if (attempts.isEmpty()) {
			return Optional.empty();
		}
		SessionRow last = attempts.get(attempts.size() - 1);
		Views.Session session = views.session(last, false);
		List<Views.InterviewQuestion> questions = session.questions().stream()
				.map(q -> new Views.InterviewQuestion(q.position(), q.text(), q.generatedBy(),
						q.answer() == null ? null : q.answer().transcriptRaw(),
						q.answer() == null ? null : q.answer().transcriptFinal()))
				.toList();
		boolean canRetry = application.stage() == ApplicationStages.Stage.INTERVIEW_PENDING && canRetry(attempts);
		return Optional.of(new Views.InterviewView(applicationId, last.id(), last.status().name(), last.attempt(),
				properties.interview().maxAttempts(), canRetry, last.noticeVersion(), last.noticeAcceptedAt(),
				session.nextPosition(), questions));
	}

	// ------------------------------------------------------------------------------------------ resultados (empresa)

	@Override
	public List<Attempt> forApplication(UUID applicationId) {
		List<Attempt> result = new ArrayList<>();
		for (SessionRow session : repository.interviews(applicationId)) {
			if (session.mode() != SessionMode.INTERVIEW) {
				continue; // nunca una práctica (T5)
			}
			Map<Integer, TurnRow> latest = SessionViews.latestByPosition(repository.turns(session.id()));
			Map<UUID, FeedbackRow> feedback = new HashMap<>();
			repository.feedbackFor(latest.values().stream().map(TurnRow::id).toList())
					.forEach(row -> feedback.put(row.turnId(), row));
			Map<String, List<Integer>> levels = new LinkedHashMap<>();
			rubric.criteria().forEach(criterion -> levels.put(criterion.code(), new ArrayList<>()));
			List<AnswerResult> answers = new ArrayList<>();
			for (QuestionRow question : repository.questions(session.id())) {
				TurnRow turn = latest.get(question.position());
				if (turn == null) {
					continue;
				}
				FeedbackRow evaluation = feedback.get(turn.id());
				List<CriterionResult> criteria = new ArrayList<>();
				if (evaluation != null && "READY".equals(evaluation.status())) {
					for (RubricValidator.ValidCriterion criterion : views.criteria(evaluation.criteriaJson())) {
						criteria.add(new CriterionResult(criterion.criterion(), criterion.level(), criterion.comment(),
								criterion.quotes()));
						levels.computeIfAbsent(criterion.criterion(), k -> new ArrayList<>()).add(criterion.level());
					}
				}
				answers.add(new AnswerResult(question.position(), question.text(), question.source().name(),
						turn.transcriptRaw(), turn.transcriptFinal(), turn.correctedAt() != null,
						turn.inputMode().name(), evaluation == null ? "PENDING" : evaluation.status(), criteria));
			}
			List<CriterionSummary> summary = new ArrayList<>();
			levels.forEach((criterion, values) -> summary.add(new CriterionSummary(criterion,
					values.isEmpty() ? null
							: Math.round(values.stream().mapToInt(Integer::intValue).average().orElse(0) * 10) / 10.0,
					values.size())));
			List<Event> events = repository.events(session.id()).stream()
					.map(event -> new Event(event.kind(), event.occurredAt())).toList();
			result.add(new Attempt(session.id(), session.attempt(), session.status().name(), session.noticeVersion(),
					session.noticeAcceptedAt(), session.startedAt(), session.endedAt(), answers,
					session.questionCount() - answers.size(), summary, "AI",
					new Versions(session.llmAdapter(), session.llmModel(), session.sttModel(), session.ttsModel(),
							session.promptVersion(), session.rubricVersion(), session.bankVersion()),
					events));
		}
		return result;
	}

}
