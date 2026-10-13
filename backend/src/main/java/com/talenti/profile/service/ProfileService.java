package com.talenti.profile.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.talenti.profile.ConfirmedChanges;
import com.talenti.profile.ProfileCompleteness;
import com.talenti.profile.ProfileEditor;
import com.talenti.profile.ProfileReader;
import com.talenti.profile.ProfileSnapshot;
import com.talenti.profile.ProfileView;
import com.talenti.profile.WorkModality;
import com.talenti.profile.domain.CompletenessRule;
import com.talenti.profile.domain.IntInput;
import com.talenti.profile.domain.ProfileFacts;
import com.talenti.profile.domain.ProfileRules;
import com.talenti.profile.persistence.ProfileRepository;
import com.talenti.shared.web.ApiException;
import com.talenti.shared.web.FieldViolation;
import com.talenti.shared.web.ValidationFailedException;
import com.talenti.vacancy.SkillCatalog;
import com.talenti.vacancy.SkillInfo;

/**
 * Casos de uso del perfil del estudiante (spec 006). Toda operación recibe el id de la persona que salió de la
 * sesión; ninguna acepta el id de otra (006-04). Las escrituras validan primero, guardan dentro de una transacción
 * y devuelven el perfil completo con el porcentaje de avance ya recalculado (006-11).
 *
 * <p>No llama a ninguna IA ni cuenta en ningún cupo, y no escribe en registros nada del contenido del perfil
 * (006-12). Las habilidades se resuelven solo por {@link SkillCatalog}, nunca leyendo la tabla del catálogo.
 */
@Service
public class ProfileService implements ProfileReader, ProfileEditor {

	private final ProfileRepository repository;

	private final SkillCatalog catalog;

	private final CompletenessRule completenessRule;

	private final ProfileRules rules;

	ProfileService(ProfileRepository repository, SkillCatalog catalog, CompletenessRule completenessRule,
			ProfileRules rules) {
		this.repository = repository;
		this.catalog = catalog;
		this.completenessRule = completenessRule;
		this.rules = rules;
	}

	// ------------------------------------------------------------------------------------------ lectura

	/** Perfil de la persona; si no guardó nada, uno vacío (no crea ninguna fila, 006-03). */
	@Override
	@Transactional(readOnly = true)
	public ProfileView current(UUID userId) {
		return load(userId);
	}

	@Override
	@Transactional(readOnly = true)
	public ProfileSnapshot snapshot(UUID userId) {
		ProfileView view = load(userId);
		Set<WorkModality> modalities = EnumSet.noneOf(WorkModality.class);
		modalities.addAll(view.availability().acceptedModalities());
		return new ProfileSnapshot(
				view.skills().stream().map(skill -> new ProfileSnapshot.Skill(skill.slug(), skill.level())).toList(),
				view.availability().hoursPerWeek(), modalities, view.availability().location(),
				view.projects().stream().map(project -> new ProfileSnapshot.Project(project.name(),
						project.description(), project.technologies())).toList());
	}

	@Override
	@Transactional(readOnly = true)
	public ProfileCompleteness completeness(UUID userId) {
		return load(userId).completeness();
	}

	// ------------------------------------------------------------------------------------------ escritura

	/** 006-23: nombre y presentación. */
	@Transactional
	public ProfileView saveAbout(UUID userId, String displayName, String about) {
		ProfileRules.AboutData data = rules.about("", displayName, about);
		touch(userId);
		repository.saveAbout(userId, data);
		return load(userId);
	}

	/** 006-05. */
	@Transactional
	public ProfileView saveAcademic(UUID userId, String career, String institute, IntInput cycle) {
		ProfileRules.AcademicData data = rules.academic("", career, institute, cycle);
		touch(userId);
		repository.saveAcademic(userId, data);
		return load(userId);
	}

	/** 006-06: reemplaza la lista completa, o no cambia nada. */
	@Transactional
	public ProfileView replaceSkills(UUID userId, List<ProfileRules.SkillInput> skills) {
		List<ProfileRules.SkillData> data = rules.skills("", skills, slugResolver());
		touch(userId);
		repository.replaceSkills(userId, data);
		return load(userId);
	}

	/** 006-07. */
	@Transactional
	public ProfileView saveAvailability(UUID userId, IntInput hoursPerWeek, List<String> modalities,
			String location) {
		ProfileRules.AvailabilityData data = rules.availability("", hoursPerWeek, modalities, location);
		touch(userId);
		repository.saveAvailability(userId, data);
		return load(userId);
	}

	/** 006-08. */
	@Transactional
	public ProfileView addProject(UUID userId, String name, String description, List<String> technologies) {
		ProfileRules.ProjectData data = rules.project("", name, description, technologies);
		touch(userId);
		rules.checkProjectLimit("projects", repository.countProjects(userId), 1);
		repository.insertProject(userId, data);
		return load(userId);
	}

	/** 006-08. Un proyecto ajeno, inexistente o con id mal formado da 404 sin distinguirlos (006-04). */
	@Transactional
	public ProfileView updateProject(UUID userId, String projectId, String name, String description,
			List<String> technologies) {
		ProfileRules.ProjectData data = rules.project("", name, description, technologies);
		UUID id = parseProjectId(projectId);
		touch(userId);
		if (!repository.updateProject(userId, id, data)) {
			throw new ProjectNotFoundException();
		}
		return load(userId);
	}

	/** 006-08. */
	@Transactional
	public ProfileView deleteProject(UUID userId, String projectId) {
		UUID id = parseProjectId(projectId);
		touch(userId);
		if (!repository.deleteProject(userId, id)) {
			throw new ProjectNotFoundException();
		}
		return load(userId);
	}

	/** Lo confirmado del lector de CV (spec 009): todo se aplica junto o no se aplica nada. */
	@Override
	@Transactional
	public ProfileView applyConfirmed(UUID userId, ConfirmedChanges changes) {
		List<FieldViolation> violations = new ArrayList<>();
		ProfileRules.AcademicData academic = null;
		if (changes.academic() != null) {
			academic = collect(violations, () -> rules.academic("academic.", changes.academic().career(),
					changes.academic().institute(), IntInput.of(changes.academic().cycle())));
		}
		List<ProfileRules.SkillData> skills = List.of();
		if (!changes.skills().isEmpty()) {
			List<ProfileRules.SkillInput> inputs = changes.skills().stream()
					.map(skill -> new ProfileRules.SkillInput(skill.slug(), IntInput.of(skill.level()))).toList();
			skills = collect(violations, () -> rules.skills("", inputs, slugResolver()));
		}
		List<ProfileRules.ProjectData> projects = new ArrayList<>();
		for (int i = 0; i < changes.projects().size(); i++) {
			ConfirmedChanges.Project project = changes.projects().get(i);
			String prefix = "projects[" + i + "].";
			ProfileRules.ProjectData data = collect(violations,
					() -> rules.project(prefix, project.name(), project.description(), project.technologies()));
			if (data != null) {
				projects.add(data);
			}
		}
		if (!violations.isEmpty()) {
			throw new ValidationFailedException(violations);
		}
		touch(userId);
		if (academic != null) {
			repository.mergeAcademic(userId, academic);
		}
		repository.upsertSkills(userId, skills);
		rules.checkProjectLimit("projects", repository.countProjects(userId), projects.size());
		projects.forEach(project -> repository.insertProject(userId, project));
		return load(userId);
	}

	// ------------------------------------------------------------------------------------------ piezas

	/** Ejecuta una validación y, si falla, junta sus errores con los de las demás secciones. */
	private static <T> T collect(List<FieldViolation> violations, Supplier<T> validation) {
		try {
			return validation.get();
		}
		catch (ValidationFailedException failed) {
			violations.addAll(failed.getViolations());
			return null;
		}
	}

	/** Asegura la fila del perfil y la bloquea hasta el final de la transacción (escrituras de una en una). */
	private void touch(UUID userId) {
		try {
			repository.touch(userId);
		}
		catch (DataIntegrityViolationException e) {
			// La persona ya no existe (cuenta eliminada con una sesión que sobrevivió): no hay a quién guardarle nada.
			throw new ApiException(HttpStatus.UNAUTHORIZED, "unauthenticated");
		}
	}

	private static UUID parseProjectId(String raw) {
		try {
			return UUID.fromString(raw);
		}
		catch (IllegalArgumentException | NullPointerException e) {
			throw new ProjectNotFoundException();
		}
	}

	private Function<String, UUID> slugResolver() {
		Map<String, UUID> ids = new HashMap<>();
		for (SkillInfo skill : catalog.all()) {
			ids.put(skill.slug(), skill.id());
		}
		return ids::get;
	}

	private ProfileView load(UUID userId) {
		Map<UUID, SkillInfo> catalogById = new HashMap<>();
		for (SkillInfo skill : catalog.all()) {
			catalogById.put(skill.id(), skill);
		}
		var profile = repository.findProfile(userId).orElse(null);
		List<ProfileView.SkillEntry> skills = repository.findSkills(userId).stream()
				.filter(row -> catalogById.containsKey(row.skillId()))
				.map(row -> {
					SkillInfo info = catalogById.get(row.skillId());
					return new ProfileView.SkillEntry(info.slug(), info.name(), row.level(), row.evidence());
				})
				.sorted(Comparator.comparing(ProfileView.SkillEntry::name))
				.toList();
		List<ProfileView.ProjectEntry> projects = repository.findProjects(userId).stream()
				.map(row -> new ProfileView.ProjectEntry(row.id(), row.name(), row.description(), row.technologies()))
				.toList();

		ProfileView.About about = profile == null ? new ProfileView.About(null, null)
				: new ProfileView.About(profile.displayName(), profile.about());
		ProfileView.Academic academic = profile == null ? new ProfileView.Academic(null, null, null)
				: new ProfileView.Academic(profile.career(), profile.institute(), profile.cycle());
		ProfileView.Availability availability = profile == null
				? new ProfileView.Availability(null, List.of(), null)
				: new ProfileView.Availability(profile.hoursPerWeek(), profile.acceptedModalities(),
						profile.location());
		ProfileFacts facts = new ProfileFacts(academic.career() != null, academic.institute() != null,
				academic.cycle() != null, skills.size(), availability.hoursPerWeek() != null,
				availability.acceptedModalities().size(), projects.size(), availability.location() != null,
				about.about() != null);
		Instant updatedAt = profile == null || profile.updatedAt() == null ? null : profile.updatedAt().toInstant();
		return new ProfileView(about, academic, skills, availability, projects, completenessRule.evaluate(facts), updatedAt);
	}

}
