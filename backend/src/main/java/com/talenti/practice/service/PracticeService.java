package com.talenti.practice.service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import com.talenti.practice.config.DoraProperties;
import com.talenti.practice.domain.Basis;
import com.talenti.practice.domain.DoraTexts;
import com.talenti.practice.domain.QuestionBank;
import com.talenti.practice.domain.QuestionPlanner;
import com.talenti.practice.domain.Rubric;
import com.talenti.practice.domain.SessionMode;
import com.talenti.practice.persistence.PracticeRepository;
import com.talenti.practice.persistence.PracticeRepository.SessionRow;
import com.talenti.practice.port.ConversationPort;
import com.talenti.practice.port.LlmPort;
import com.talenti.profile.ProfileReader;
import com.talenti.profile.ProfileSnapshot;
import com.talenti.shared.ai.AiFunction;
import com.talenti.shared.ai.AiUsageQuota;
import com.talenti.shared.ai.QuotaStatus;
import com.talenti.vacancy.SkillCatalog;
import com.talenti.vacancy.SkillInfo;
import com.talenti.vacancy.VacancyInfo;
import com.talenti.vacancy.VacancyLookup;

/**
 * La sesión de práctica libre (012-01, 012-02, 012-21): opciones, crear, historial y borrado. Es PRIVADA: ningún otro
 * módulo la lee y nunca cambia una postulación (T5, 012-22). Las preguntas salen solo de lo confirmado en el perfil o de
 * los requisitos de una vacante publicada (RN-08): nunca nombre ni contacto.
 */
@Service
public class PracticeService {

	public static final int DEFAULT_PAGE_SIZE = 10;

	public static final int MAX_PAGE_SIZE = 24;

	/** Lo que pide la persona al crear una práctica (ya leído del cuerpo). */
	public record CreateCommand(Basis basis, UUID vacancyId, String topic, int questionCount, boolean voice) {
	}

	private final PracticeRepository repository;

	private final ProfileReader profiles;

	private final VacancyLookup vacancies;

	private final SkillCatalog catalog;

	private final AiUsageQuota quota;

	private final SessionPlanning planning;

	private final SessionViews views;

	private final DoraService dora;

	private final UsageMeter usage;

	private final ConversationPort conversation;

	private final QuestionBank bank;

	private final Rubric rubric;

	private final InterviewNotice notice;

	private final DoraProperties properties;

	private final Clock clock;

	private final RealtimeService realtime;

	private final RealtimeCalls calls;

	PracticeService(PracticeRepository repository, ProfileReader profiles, VacancyLookup vacancies, SkillCatalog catalog,
			AiUsageQuota quota, SessionPlanning planning, SessionViews views, DoraService dora, UsageMeter usage,
			ConversationPort conversation, QuestionBank bank, Rubric rubric, InterviewNotice notice,
			DoraProperties properties, Clock clock, RealtimeService realtime, RealtimeCalls calls) {
		this.realtime = realtime;
		this.calls = calls;
		this.repository = repository;
		this.profiles = profiles;
		this.vacancies = vacancies;
		this.catalog = catalog;
		this.quota = quota;
		this.planning = planning;
		this.views = views;
		this.dora = dora;
		this.usage = usage;
		this.conversation = conversation;
		this.bank = bank;
		this.rubric = rubric;
		this.notice = notice;
		this.properties = properties;
		this.clock = clock;
	}

	// ------------------------------------------------------------------------------------------ opciones

	/** 012-01: no llama a la IA ni anota consumo. */
	public Views.Options options(UUID userId) {
		List<Views.Topic> profileTopics = profileTopics(profiles.snapshot(userId));
		boolean profileAvailable = !profileTopics.isEmpty() || !profiles.snapshot(userId).projects().isEmpty();
		List<Views.VacancyBasis> open = new ArrayList<>();
		for (VacancyInfo vacancy : vacancies.listOpenForMatching()) {
			open.add(new Views.VacancyBasis(vacancy.id(), vacancy.title(), vacancy.company().name(),
					vacancy.requirements().stream().map(r -> new Views.Topic(r.slug(), r.name())).toList()));
		}
		List<Views.BasisOption> bases = List.of(new Views.BasisOption(Basis.PROFILE.name(), profileAvailable, List.of()),
				new Views.BasisOption(Basis.VACANCY.name(), !open.isEmpty(), open));
		QuotaStatus status = quota.check(userId, AiFunction.PRACTICE_SESSION);
		return new Views.Options(bases, profileTopics, properties.maxQuestions(),
				new Views.Quota(status.used(), status.limit(), status.personalResets()), !usage.spendReached(),
				new Views.Notice(notice.version(), notice.text()), realtime.availability());
	}

	private List<Views.Topic> profileTopics(ProfileSnapshot snapshot) {
		List<Views.Topic> topics = new ArrayList<>();
		for (ProfileSnapshot.Skill skill : snapshot.skills()) {
			topics.add(new Views.Topic(skill.slug(), catalog.findBySlug(skill.slug()).map(SkillInfo::name)
					.orElse(skill.slug())));
		}
		return topics;
	}

	// ------------------------------------------------------------------------------------------ crear

	/** 012-02 a 012-04, 012-29: crea la práctica con sus preguntas ya planificadas. */
	public Views.Session create(UUID userId, CreateCommand command) {
		if (command.questionCount() < 1 || command.questionCount() > properties.maxQuestions()) {
			throw DoraErrors.invalid("questionCount", "out-of-range", 1, properties.maxQuestions());
		}
		List<QuestionPlanner.BasisRef> basis = basis(userId, command);
		Optional<SessionRow> open = repository.findOpen(userId);
		if (open.isPresent()) {
			throw DoraErrors.sessionOpen(open.get());
		}
		QuotaStatus status = quota.check(userId, AiFunction.PRACTICE_SESSION);
		if (!status.allowed()) {
			throw DoraErrors.quotaReached(status.personalResets(), status.blockedBy().name());
		}
		QuestionPlanner.Plan plan = planning.plan(userId, LlmPort.Purpose.PRACTICE, basis, command.questionCount());
		UUID id = UUID.randomUUID();
		ConversationPort.Info info = conversation.info();
		try {
			repository.insertSession(new PracticeRepository.NewSession(id, userId, SessionMode.PRACTICE,
					command.basis(), command.vacancyId(), null, command.topic(), command.questionCount(),
					command.voice(), 1, null, null, info.adapter(), info.llmModel(), info.sttModel(), info.ttsModel(),
					info.ttsVoice(), info.promptVersion(), rubric.version(), bank.version()),
					plan.questions().stream().map(q -> new PracticeRepository.NewQuestion(q.position(), q.text(),
							q.source(), q.basisRef())).toList(),
					clock.instant());
		}
		catch (DuplicateKeyException race) {
			throw DoraErrors.sessionOpen(repository.findOpen(userId).orElse(null));
		}
		if (plan.degraded()) {
			repository.addDegraded(id, "QUESTION_BANK");
		}
		SessionRow session = repository.findById(id).orElseThrow();
		dora.prefetchOpening(session);
		dora.prefetchQuestion(session, repository.questions(id), 1);
		return views.session(session, true);
	}

	/** Lo que se le manda a la IA para planificar (RN-08): solo habilidades, proyectos o requisitos. */
	private List<QuestionPlanner.BasisRef> basis(UUID userId, CreateCommand command) {
		List<QuestionPlanner.BasisRef> refs = new ArrayList<>();
		if (command.basis() == Basis.PROFILE) {
			ProfileSnapshot snapshot = profiles.snapshot(userId);
			List<Views.Topic> topics = profileTopics(snapshot);
			if (topics.isEmpty() && snapshot.projects().isEmpty()) {
				throw DoraErrors.invalid("basis", "no-confirmed-data");
			}
			checkTopic(command.topic(), topics);
			for (Views.Topic topic : topics) {
				if (command.topic() == null || topic.slug().equals(command.topic())) {
					refs.add(new QuestionPlanner.BasisRef("skill:" + topic.slug(), topic.name()));
				}
			}
			if (command.topic() == null) {
				for (int i = 0; i < snapshot.projects().size(); i++) {
					refs.add(new QuestionPlanner.BasisRef("project:" + (i + 1), snapshot.projects().get(i).name()));
				}
			}
		}
		else {
			if (command.vacancyId() == null) {
				throw DoraErrors.invalid("vacancyId", "required");
			}
			VacancyInfo vacancy = vacancies.findOpenForMatching(command.vacancyId())
					.orElseThrow(() -> DoraErrors.notFound("vacancy-not-found"));
			List<Views.Topic> topics = vacancy.requirements().stream().map(r -> new Views.Topic(r.slug(), r.name()))
					.toList();
			checkTopic(command.topic(), topics);
			for (Views.Topic topic : topics) {
				if (command.topic() == null || topic.slug().equals(command.topic())) {
					refs.add(new QuestionPlanner.BasisRef("requirement:" + topic.slug(), topic.name()));
				}
			}
		}
		return refs;
	}

	private static void checkTopic(String topic, List<Views.Topic> allowed) {
		if (topic != null && allowed.stream().noneMatch(t -> t.slug().equals(topic))) {
			throw DoraErrors.invalid("topic", "not-allowed");
		}
	}

	// ------------------------------------------------------------------------------------------ historial

	/** 012-21: solo las prácticas de la persona (las entrevistas se ven en su postulación). */
	public Views.SessionPage list(UUID userId, int page, int size) {
		int total = repository.countPractice(userId);
		List<Views.SessionSummary> items = new ArrayList<>();
		for (SessionRow row : repository.listPractice(userId, size, (page - 1) * size)) {
			int answered = SessionViews.latestByPosition(repository.turns(row.id())).size();
			items.add(new Views.SessionSummary(row.id(), row.createdAt(), row.basis().name(), row.vacancyId(),
					row.topic(), row.status().name(), row.questionCount(), answered));
		}
		return new Views.SessionPage(items, page, size, total, (total + size - 1) / size);
	}

	public Views.Session detail(UUID userId, UUID id) {
		SessionRow row = practice(userId, id);
		return views.session(row, true);
	}

	/** 012-21: borra la práctica con todo lo asociado. Nunca limitado por el cupo (T8). */
	public void delete(UUID userId, UUID id) {
		SessionRow session = practice(userId, id);
		// Una llamada en tiempo real abierta se cierra (y se cuelga sin bloquear) antes de borrar todo.
		calls.closeOpenCall(session, "DELETED", false);
		repository.deletePractice(id, userId);
		dora.forget(id);
	}

	/** 012-54: la sesión abierta de la persona (práctica o entrevista), si hay. */
	public Optional<Views.OpenSession> openSession(UUID userId) {
		return repository.findOpen(userId).map(SessionViews::open);
	}

	private SessionRow practice(UUID userId, UUID id) {
		Optional<SessionRow> row = repository.findOwned(id, userId).filter(s -> s.mode() == SessionMode.PRACTICE);
		return row.orElseThrow(DoraErrors::sessionNotFound);
	}

}
