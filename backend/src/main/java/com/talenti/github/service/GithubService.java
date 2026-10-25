package com.talenti.github.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.talenti.github.GithubShowcase;
import com.talenti.github.client.Fetched;
import com.talenti.github.client.GithubClient;
import com.talenti.github.client.GithubNotFoundException;
import com.talenti.github.client.GithubRateLimitedException;
import com.talenti.github.client.GithubUnavailableException;
import com.talenti.github.client.GithubUser;
import com.talenti.github.client.RepoData;
import com.talenti.github.config.GithubProperties;
import com.talenti.github.domain.LanguageShare;
import com.talenti.github.domain.TechnologyDetector;
import com.talenti.github.domain.TechnologyMap;
import com.talenti.github.persistence.GithubStore;
import com.talenti.github.persistence.GithubStore.LinkRow;
import com.talenti.github.persistence.GithubStore.RepoRow;
import com.talenti.profile.ProfileView;
import com.talenti.profile.SkillEvidence;
import com.talenti.profile.SkillEvidenceWriter;
import com.talenti.shared.audit.AuditEvent;
import com.talenti.shared.audit.AuditRecorder;
import com.talenti.shared.web.ApiException;
import com.talenti.shared.web.FieldViolation;
import com.talenti.shared.web.TooManyRequestsException;
import com.talenti.shared.web.ValidationFailedException;
import com.talenti.vacancy.SkillCatalog;
import com.talenti.vacancy.SkillInfo;

/**
 * Repositorios, sugerencias y desvinculación (013-05 a 013-16). El id de la persona sale siempre de la sesión. Las
 * llamadas a GitHub se hacen fuera de las transacciones; lo leído se guarda después, todo junto o nada. Sin IA: las
 * sugerencias salen de reglas ({@link TechnologyDetector}). Usa de otros módulos solo {@link SkillCatalog} y
 * {@link SkillEvidenceWriter}.
 */
@Service
public class GithubService implements GithubShowcase {

	private final GithubProperties properties;

	private final GithubStore store;

	private final GithubClient client;

	private final SkillCatalog catalog;

	private final SkillEvidenceWriter skills;

	private final TechnologyMap technologyMap;

	private final AuditRecorder audit;

	private final Clock clock;

	private final TransactionTemplate transaction;

	/** Último "actualizar" correcto de cada persona (013-09), en memoria del servidor. */
	private final Map<UUID, Instant> lastRefresh = new ConcurrentHashMap<>();

	GithubService(GithubProperties properties, GithubStore store, GithubClient client, SkillCatalog catalog,
			SkillEvidenceWriter skills, TechnologyMap technologyMap, AuditRecorder audit, Clock clock,
			PlatformTransactionManager transactionManager) {
		this.properties = properties;
		this.store = store;
		this.client = client;
		this.catalog = catalog;
		this.skills = skills;
		this.technologyMap = technologyMap;
		this.audit = audit;
		this.clock = clock;
		this.transaction = new TransactionTemplate(transactionManager);
	}

	// ------------------------------------------------------------------------------------------ vínculo

	public Optional<Views.Link> link(UUID userId) {
		return store.findLink(userId)
				.map(link -> new Views.Link(link.status(), link.login(), link.githubUserId(), link.verifiedAt()));
	}

	/** 013-15: borra todo lo de GitHub; lo confirmado queda en el perfil como declarado. Nunca se limita (T8). */
	public void unlink(UUID userId) {
		transaction.executeWithoutResult(status -> {
			boolean existed = store.deleteEverything(userId);
			skills.downgradeObserved(userId);
			if (existed) {
				audit.record(new AuditEvent("GITHUB_UNLINKED", userId, "github_link", userId, Map.of()));
			}
		});
		lastRefresh.remove(userId);
	}

	// ------------------------------------------------------------------------------------------ repositorios

	/** Lo que se puede guardar y mostrar: públicos, y sin forks salvo que se pidan (013-06, duda 2). */
	List<RepoData> visible(List<RepoData> repos) {
		return repos.stream().filter(repo -> properties.includeForks() || !repo.fork()).toList();
	}

	/**
	 * 013-07: la lista guardada; si tiene más de {@code cache-ttl}, se vuelve a pedir (con {@code ETag}). Si GitHub
	 * falla se devuelve lo guardado con {@code stale: true}; sin nada guardado, 503.
	 */
	public Views.Repositories repositories(UUID userId) {
		LinkRow link = requireLink(userId);
		boolean stale = false;
		Instant now = clock.instant();
		if (link.reposFetchedAt() == null || !now.isBefore(link.reposFetchedAt().plus(properties.cacheTtl()))) {
			try {
				syncList(link, link.login(), now);
			}
			catch (GithubRateLimitedException | GithubUnavailableException | GithubNotFoundException e) {
				if (link.reposFetchedAt() == null) {
					throw e instanceof GithubNotFoundException ? new GithubUnavailableException("404") : e;
				}
				stale = true;
			}
		}
		LinkRow current = requireLink(userId);
		return new Views.Repositories(items(userId), current.reposFetchedAt(), stale);
	}

	/** 013-08: marca la selección y lee los lenguajes de los elegidos que no los tienen vigentes. */
	public Views.Selection select(UUID userId, List<String> rawIds) {
		LinkRow link = requireLink(userId);
		if (rawIds == null) {
			throw new ValidationFailedException(List.of(FieldViolation.of("repoIds", "required")));
		}
		if (rawIds.size() > properties.maxSelected()) {
			throw new ValidationFailedException(
					List.of(FieldViolation.of("repoIds", "max-items", properties.maxSelected())));
		}
		Map<UUID, RepoRow> own = new HashMap<>();
		for (RepoRow row : store.findRepos(userId)) {
			own.put(row.id(), row);
		}
		List<FieldViolation> violations = new ArrayList<>();
		Set<UUID> chosen = new LinkedHashSet<>();
		for (int i = 0; i < rawIds.size(); i++) {
			UUID id = parse(rawIds.get(i));
			if (id == null || !own.containsKey(id)) {
				violations.add(FieldViolation.of("repoIds[" + i + "]", "unknown-repository"));
			}
			else if (!chosen.add(id)) {
				violations.add(FieldViolation.of("repoIds[" + i + "]", "duplicate"));
			}
		}
		if (!violations.isEmpty()) {
			throw new ValidationFailedException(violations);
		}
		Instant now = clock.instant();
		Map<UUID, Fetched<Map<String, Long>>> fetched = new HashMap<>();
		for (UUID id : chosen) {
			RepoRow row = own.get(id);
			if (!languagesCurrent(row, now)) {
				fetched.put(id, fetchLanguages(link.login(), row));
			}
		}
		transaction.executeWithoutResult(status -> {
			store.setSelection(userId, chosen);
			fetched.forEach((id, result) -> saveLanguages(userId, id, result, now));
		});
		return new Views.Selection(items(userId));
	}

	/**
	 * 013-09 y 013-05: vuelve a pedir la cuenta (por su id numérico: manda sobre el nombre), la lista y los lenguajes
	 * de los elegidos con {@code If-None-Match}. Como máximo una vez cada {@code refresh-interval} (429).
	 */
	public Views.Refresh refresh(UUID userId) {
		LinkRow link = requireLink(userId);
		Instant now = clock.instant();
		Instant last = lastRefresh.get(userId);
		if (last != null && now.isBefore(last.plus(properties.refreshInterval()))) {
			throw new TooManyRequestsException(Duration.between(now, last.plus(properties.refreshInterval())).toSeconds() + 1);
		}
		String login = link.login();
		try {
			GithubUser current = client.userById(link.githubUserId());
			login = current.login();
		}
		catch (GithubNotFoundException e) {
			// Sin cuenta visible: se sigue con el nombre guardado (la lista dirá si queda algo).
		}
		List<RepoRow> before = store.findRepos(userId);
		Fetched<List<RepoData>> list = fetchList(login, link.reposEtag());
		Set<Long> present = new HashSet<>();
		if (!list.notModified()) {
			visible(list.value()).forEach(repo -> present.add(repo.id()));
		}
		List<Views.Removed> removed = new ArrayList<>();
		Map<UUID, Fetched<Map<String, Long>>> languages = new HashMap<>();
		List<UUID> gone = new ArrayList<>();
		for (RepoRow row : before) {
			if (!row.selected()) {
				continue;
			}
			if (!list.notModified() && !present.contains(row.githubRepoId())) {
				removed.add(new Views.Removed(row.id(), row.name()));
				continue;
			}
			try {
				languages.put(row.id(), fetchLanguages(login, row));
			}
			catch (GithubNotFoundException e) {
				removed.add(new Views.Removed(row.id(), row.name()));
				gone.add(row.id());
			}
		}
		String newLogin = login;
		transaction.executeWithoutResult(status -> {
			if (!newLogin.equals(link.login())) {
				store.updateLogin(userId, newLogin);
			}
			saveList(userId, list, link.reposEtag(), now);
			languages.forEach((id, result) -> saveLanguages(userId, id, result, now));
			gone.forEach(id -> store.deleteRepo(userId, id));
		});
		lastRefresh.put(userId, now);
		return new Views.Refresh(items(userId), removed, now);
	}

	// ------------------------------------------------------------------------------------------ sugerencias

	/** 013-10 y 013-11: por reglas, solo de los elegidos; sin las ya {@code OBSERVED} ni las rechazadas. */
	public Views.Suggestions suggestions(UUID userId) {
		LinkRow link = requireLink(userId);
		TechnologyDetector.Detection detection = detect(userId);
		Map<String, SkillEvidenceWriter.CurrentSkill> current = currentSkills(userId);
		List<Views.Suggestion> suggestions = new ArrayList<>();
		for (TechnologyDetector.Suggestion suggestion : open(detection, current, link)) {
			SkillEvidenceWriter.CurrentSkill skill = current.get(suggestion.slug());
			suggestions.add(new Views.Suggestion(suggestion.slug(), suggestion.name(), evidence(suggestion.evidence()),
					skill == null ? null : skill.level(), skill == null ? null : skill.evidence().name()));
		}
		List<Views.Unmatched> unmatched = detection.unmatched().stream()
				.map(item -> new Views.Unmatched(item.name(), evidence(item.evidence()))).toList();
		return new Views.Suggestions(suggestions, unmatched, TechnologyDetector.SOURCE);
	}

	/** 013-12: lo aceptado entra al perfil como {@code OBSERVED}; lo rechazado se recuerda. Todo junto o nada. */
	public ProfileView decide(UUID userId, List<Views.Decision> items) {
		LinkRow link = requireLink(userId);
		if (items == null || items.isEmpty()) {
			throw new ValidationFailedException(List.of(FieldViolation.of("items", "required")));
		}
		if (items.size() > 100) {
			throw new ValidationFailedException(List.of(FieldViolation.of("items", "max-items", 100)));
		}
		Map<String, SkillEvidenceWriter.CurrentSkill> current = currentSkills(userId);
		Set<String> open = new HashSet<>();
		open(detect(userId), current, link).forEach(suggestion -> open.add(suggestion.slug()));
		List<FieldViolation> violations = new ArrayList<>();
		List<SkillEvidenceWriter.ObservedSkill> accepted = new ArrayList<>();
		List<String> rejected = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int i = 0; i < items.size(); i++) {
			String base = "items[" + i + "]";
			Views.Decision item = items.get(i);
			String slug = item == null || item.slug() == null ? "" : item.slug().strip();
			boolean slugOk = false;
			if (slug.isEmpty()) {
				violations.add(FieldViolation.of(base + ".slug", "required"));
			}
			else if (!open.contains(slug)) {
				violations.add(FieldViolation.of(base + ".slug", "not-suggested"));
			}
			else if (!seen.add(slug)) {
				violations.add(FieldViolation.of(base + ".slug", "duplicate"));
			}
			else {
				slugOk = true;
			}
			String decision = item == null || item.decision() == null ? "" : item.decision().strip();
			if (!"ACCEPT".equals(decision) && !"REJECT".equals(decision)) {
				violations.add(FieldViolation.of(base + ".decision", "invalid-decision"));
				continue;
			}
			if ("REJECT".equals(decision)) {
				if (slugOk) {
					rejected.add(slug);
				}
				continue;
			}
			Integer level = item.level();
			boolean levelBad = item.levelMalformed() || (level != null && (level < 1 || level > 3))
					|| (level == null && slugOk && !current.containsKey(slug));
			if (levelBad) {
				violations.add(FieldViolation.of(base + ".level", "invalid-level"));
			}
			else if (slugOk) {
				accepted.add(new SkillEvidenceWriter.ObservedSkill(slug, level));
			}
		}
		if (!violations.isEmpty()) {
			throw new ValidationFailedException(violations);
		}
		return transaction.execute(status -> {
			store.addDismissed(userId, rejected);
			ProfileView view = skills.confirmObserved(userId, accepted);
			audit.record(new AuditEvent("GITHUB_SKILLS_DECIDED", userId, "github_link", userId,
					Map.of("accepted", accepted.size(), "rejected", rejected.size())));
			return view;
		});
	}

	// ------------------------------------------------------------------------------------------ vitrina (013-16)

	@Override
	public Optional<Showcase> forUser(UUID userId) {
		return store.findLink(userId).filter(link -> "VERIFIED".equals(link.status())).map(link -> new Showcase(
				link.login(),
				store.findRepos(userId).stream().filter(RepoRow::selected)
						.map(row -> new GithubShowcase.Repository(row.name(), row.description(), row.htmlUrl(),
								row.languages() == null ? List.of()
										: row.languages().stream()
												.map(l -> new GithubShowcase.Language(l.name(), l.percent())).toList(),
								row.topics()))
						.toList()));
	}

	// ------------------------------------------------------------------------------------------ piezas

	private LinkRow requireLink(UUID userId) {
		return store.findLink(userId).orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "not-verified"));
	}

	private List<Views.Repository> items(UUID userId) {
		return store.findRepos(userId).stream().map(Views.Repository::of).toList();
	}

	private Fetched<List<RepoData>> fetchList(String login, String etag) {
		try {
			return client.repositories(login, null, etag);
		}
		catch (GithubNotFoundException e) {
			// Una cuenta que no se encuentra no borra lo guardado: se trata como GitHub sin respuesta.
			throw new GithubUnavailableException("404");
		}
	}

	private void syncList(LinkRow link, String login, Instant now) {
		Fetched<List<RepoData>> list = fetchList(login, link.reposEtag());
		transaction.executeWithoutResult(status -> saveList(link.userId(), list, link.reposEtag(), now));
	}

	private void saveList(UUID userId, Fetched<List<RepoData>> list, String previousEtag, Instant now) {
		if (list.notModified()) {
			store.markReposFetched(userId, previousEtag, now);
		}
		else {
			store.syncRepos(userId, visible(list.value()));
			store.markReposFetched(userId, list.etag(), now);
		}
	}

	private boolean languagesCurrent(RepoRow row, Instant now) {
		return row.languages() != null && row.languagesFetchedAt() != null
				&& now.isBefore(row.languagesFetchedAt().plus(properties.cacheTtl()));
	}

	private Fetched<Map<String, Long>> fetchLanguages(String login, RepoRow row) {
		return client.languages(login, row.name(), row.languages() == null ? null : row.languagesEtag());
	}

	private void saveLanguages(UUID userId, UUID repoId, Fetched<Map<String, Long>> result, Instant now) {
		if (result.notModified()) {
			store.touchLanguages(userId, repoId, now);
		}
		else {
			store.saveLanguages(userId, repoId, LanguageShare.of(result.value()), result.etag(), now);
		}
	}

	private TechnologyDetector.Detection detect(UUID userId) {
		List<TechnologyDetector.SelectedRepo> selected = store.findRepos(userId).stream().filter(RepoRow::selected)
				.map(row -> new TechnologyDetector.SelectedRepo(row.name(), row.languages(), row.topics())).toList();
		Map<String, SkillInfo> bySlug = new HashMap<>();
		for (SkillInfo skill : catalog.all()) {
			bySlug.put(skill.slug(), skill);
		}
		TechnologyDetector detector = new TechnologyDetector(properties.minLanguageShare(), name -> {
			SkillInfo skill = technologyMap.slugFor(name).map(bySlug::get)
					.or(() -> Optional.ofNullable(bySlug.get(name.toLowerCase(Locale.ROOT))))
					.or(() -> catalog.resolve(name))
					.or(() -> name.contains("-") ? catalog.resolve(name.replace('-', ' ')) : Optional.empty())
					.orElse(null);
			return skill == null ? Optional.empty()
					: Optional.of(new TechnologyDetector.CatalogSkill(skill.slug(), skill.name()));
		});
		return detector.detect(selected);
	}

	private Map<String, SkillEvidenceWriter.CurrentSkill> currentSkills(UUID userId) {
		Map<String, SkillEvidenceWriter.CurrentSkill> result = new HashMap<>();
		for (SkillEvidenceWriter.CurrentSkill skill : skills.currentSkills(userId)) {
			result.put(skill.slug(), skill);
		}
		return result;
	}

	private static List<TechnologyDetector.Suggestion> open(TechnologyDetector.Detection detection,
			Map<String, SkillEvidenceWriter.CurrentSkill> current, LinkRow link) {
		Set<String> dismissed = new HashSet<>(link.dismissedSlugs());
		return detection.suggestions().stream()
				.filter(suggestion -> !dismissed.contains(suggestion.slug()))
				.filter(suggestion -> {
					SkillEvidenceWriter.CurrentSkill skill = current.get(suggestion.slug());
					return skill == null || skill.evidence() != SkillEvidence.OBSERVED;
				})
				.toList();
	}

	private static List<Views.Evidence> evidence(List<TechnologyDetector.Evidence> evidence) {
		return evidence.stream().map(e -> new Views.Evidence(e.repo(), e.source().name(), e.share())).toList();
	}

	private static UUID parse(String raw) {
		try {
			return raw == null ? null : UUID.fromString(raw.strip());
		}
		catch (IllegalArgumentException e) {
			return null;
		}
	}

}
