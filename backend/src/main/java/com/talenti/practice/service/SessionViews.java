package com.talenti.practice.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.talenti.practice.domain.Rubric;
import com.talenti.practice.domain.RubricValidator;
import com.talenti.practice.domain.SessionStatus;
import com.talenti.practice.persistence.PracticeRepository;
import com.talenti.practice.persistence.PracticeRepository.FeedbackRow;
import com.talenti.practice.persistence.PracticeRepository.QuestionRow;
import com.talenti.practice.persistence.PracticeRepository.SessionRow;
import com.talenti.practice.persistence.PracticeRepository.TurnRow;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Arma las vistas de una sesión a partir de sus filas. Solo lectura. */
@Component
public class SessionViews {

	private static final TypeReference<List<RubricValidator.ValidCriterion>> CRITERIA = new TypeReference<>() {
	};

	private final PracticeRepository repository;

	private final JsonMapper mapper;

	private final Map<String, String> criterionNames = new HashMap<>();

	SessionViews(PracticeRepository repository, JsonMapper mapper, Rubric rubric) {
		this.repository = repository;
		this.mapper = mapper;
		rubric.criteria().forEach(criterion -> criterionNames.put(criterion.code(), criterion.name()));
	}

	/** La respuesta vigente de cada pregunta: su último intento (012-13). */
	public static Map<Integer, TurnRow> latestByPosition(List<TurnRow> turns) {
		Map<Integer, TurnRow> latest = new TreeMap<>();
		for (TurnRow turn : turns) {
			TurnRow current = latest.get(turn.position());
			if (current == null || turn.attempt() > current.attempt()) {
				latest.put(turn.position(), turn);
			}
		}
		return latest;
	}

	/** La primera pregunta sin responder, o nula si ya se respondieron todas. */
	public static Integer nextPosition(SessionRow session, List<TurnRow> turns) {
		Map<Integer, TurnRow> answered = latestByPosition(turns);
		for (int position = 1; position <= session.questionCount(); position++) {
			if (!answered.containsKey(position)) {
				return position;
			}
		}
		return null;
	}

	/**
	 * La sesión con las preguntas que ya se hicieron (las siguientes no se muestran antes de tiempo) y sus respuestas.
	 *
	 * @param withFeedback falso en la entrevista: el estudiante no ve sus niveles (duda 7)
	 */
	public Views.Session session(SessionRow s, boolean withFeedback) {
		List<QuestionRow> questions = repository.questions(s.id());
		List<TurnRow> turns = repository.turns(s.id());
		Map<Integer, TurnRow> latest = latestByPosition(turns);
		Integer next = nextPosition(s, turns);
		int asked = s.status() == SessionStatus.CREATED && turns.isEmpty() ? 0
				: next == null ? s.questionCount() : s.status().isOpen() ? next : latest.size();
		Map<UUID, FeedbackRow> feedback = new HashMap<>();
		if (withFeedback) {
			for (FeedbackRow row : repository.feedbackFor(latest.values().stream().map(TurnRow::id).toList())) {
				feedback.put(row.turnId(), row);
			}
		}
		List<Views.Question> items = new ArrayList<>();
		for (QuestionRow question : questions) {
			if (question.position() > asked) {
				continue;
			}
			TurnRow turn = latest.get(question.position());
			Views.Answer answer = turn == null ? null
					: new Views.Answer(turn.id(), turn.attempt(), turn.inputMode().name(), turn.transcriptRaw(),
							turn.transcriptFinal(), turn.correctedAt() != null, turn.createdAt(),
							withFeedback ? feedback(feedback.get(turn.id())) : null);
			items.add(new Views.Question(question.position(), question.text(), question.source().name(),
					question.source().name(), question.basisRef(), answer));
		}
		return new Views.Session(s.id(), s.mode().name(), s.basis().name(), s.vacancyId(), s.applicationId(), s.topic(),
				s.questionCount(), s.voiceEnabled(), s.status().name(), s.attempt(), s.degradedReasons(), s.createdAt(),
				s.startedAt(), s.endedAt(), s.status().isOpen() ? next : null, latest.size(), items, versions(s));
	}

	public static Views.Versions versions(SessionRow s) {
		return new Views.Versions(s.llmAdapter(), s.llmModel(), s.sttModel(), s.ttsModel(), s.ttsVoice(),
				s.promptVersion(), s.rubricVersion(), s.bankVersion());
	}

	/** Retroalimentación; nula si aún no se pidió. */
	public Views.Feedback feedback(FeedbackRow row) {
		if (row == null) {
			return null;
		}
		List<Views.Criterion> criteria = new ArrayList<>();
		for (RubricValidator.ValidCriterion criterion : criteria(row.criteriaJson())) {
			criteria.add(new Views.Criterion(criterion.criterion(), criterionNames.get(criterion.criterion()),
					criterion.level(), criterion.comment(), criterion.quotes()));
		}
		return new Views.Feedback(row.status(), criteria, row.total(), "AI", row.model(), row.promptVersion(),
				row.rubricVersion(), "UNAVAILABLE".equals(row.status()));
	}

	public List<RubricValidator.ValidCriterion> criteria(String json) {
		if (json == null || json.isBlank()) {
			return List.of();
		}
		return mapper.readValue(json, CRITERIA);
	}

	public String json(List<RubricValidator.ValidCriterion> criteria) {
		return mapper.writeValueAsString(criteria);
	}

}
