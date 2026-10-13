package com.talenti.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.talenti.shared.web.ApiException;
import com.talenti.shared.web.ValidationFailedException;
import com.talenti.support.ApiClient;

/**
 * Interfaces públicas del módulo: {@link ProfileReader} (006-13, para compatibilidad y postulación) y
 * {@link ProfileEditor} (para el lector de CV de la spec 009).
 */
class ProfileReaderEditorTests extends ProfileIntegrationTest {

	@Autowired
	private ProfileReader reader;

	@Autowired
	private ProfileEditor editor;

	// ------------------------------------------------------------------------------------------ ProfileReader

	@Test
	@DisplayName("006-13: ProfileReader entrega habilidades con nivel, disponibilidad, ubicación y proyectos")
	void t006_13_snapshotHasWhatTheFormulaNeeds() {
		RegisteredStudent student = registerStudent();
		fillEverything(student.api());

		ProfileSnapshot snapshot = reader.snapshot(student.id());

		assertThat(snapshot.skills()).extracting(ProfileSnapshot.Skill::slug).containsExactlyInAnyOrder("java", "sql", "git");
		assertThat(snapshot.skills()).filteredOn(skill -> skill.slug().equals("git")).extracting(ProfileSnapshot.Skill::level)
				.containsExactly(3);
		assertThat(snapshot.hoursPerWeek()).isEqualTo(20);
		assertThat(snapshot.acceptedModalities()).containsExactlyInAnyOrder(WorkModality.REMOTE, WorkModality.HYBRID);
		assertThat(snapshot.location()).isEqualTo("Lima");
		assertThat(snapshot.projects()).hasSize(1);
		assertThat(snapshot.projects().get(0).name()).isEqualTo("Tienda web");
		assertThat(snapshot.projects().get(0).technologies()).containsExactly("Java", "PostgreSQL");
		assertThat(reader.completeness(student.id()).percent()).isEqualTo(100);
	}

	@Test
	@DisplayName("006-13: un usuario sin perfil guardado recibe un snapshot vacío y 0 %, y leer no crea filas")
	void t006_13_noProfileGivesEmptySnapshot() {
		UUID someone = registerStudent().id();
		UUID nobody = UUID.randomUUID();

		for (UUID id : List.of(someone, nobody)) {
			ProfileSnapshot snapshot = reader.snapshot(id);

			assertThat(snapshot.skills()).isEmpty();
			assertThat(snapshot.projects()).isEmpty();
			assertThat(snapshot.acceptedModalities()).isEmpty();
			assertThat(snapshot.hoursPerWeek()).isNull();
			assertThat(snapshot.location()).isNull();
			assertThat(reader.completeness(id).percent()).isZero();
			assertThat(profileRows(id)).isZero();
		}
	}

	@Test
	@DisplayName("006-13: el snapshot no imprime contenido del perfil en toString")
	void t006_13_toStringHasNoContent() {
		RegisteredStudent student = registerStudent();
		fillEverything(student.api());

		assertThat(reader.snapshot(student.id()).toString()).doesNotContain("Lima").doesNotContain("Tienda")
				.doesNotContain("java");
		assertThat(editor.current(student.id()).toString()).doesNotContain("Desarrollo").doesNotContain("Demo");
	}

	// ------------------------------------------------------------------------------------------ ProfileEditor

	@Test
	@DisplayName("ProfileEditor: aplica lo confirmado (estudios sueltos, habilidades por slug, proyectos) con evidencia DECLARED y devuelve el porcentaje nuevo")
	void profileEditor_appliesConfirmedChanges() {
		RegisteredStudent student = registerStudent();
		ok(student.api().put(PROFILE + "/academic", body("career", "Redes", "institute", "Instituto Demo")));
		ok(student.api().put(PROFILE + "/skills", skills(skill("java", 1), skill("git", 2))));

		ProfileView view = editor.applyConfirmed(student.id(), new ConfirmedChanges(
				new ConfirmedChanges.Academic("Software", null, 5),
				List.of(new ConfirmedChanges.Skill("java", 3), new ConfirmedChanges.Skill("sql", 2)),
				List.of(new ConfirmedChanges.Project("Mi API", "Una API de prueba con datos inventados.", List.of("Java")))));

		assertThat(view.academic().career()).isEqualTo("Software");
		assertThat(view.academic().institute()).as("lo que no viene no cambia").isEqualTo("Instituto Demo");
		assertThat(view.academic().cycle()).isEqualTo(5);
		assertThat(view.skills()).extracting(ProfileView.SkillEntry::slug).containsExactlyInAnyOrder("java", "git", "sql");
		assertThat(view.skills()).filteredOn(skill -> skill.slug().equals("java")).extracting(ProfileView.SkillEntry::level)
				.containsExactly(3);
		assertThat(view.skills()).allMatch(skill -> skill.evidence() == SkillEvidence.DECLARED);
		assertThat(view.projects()).hasSize(1);
		assertThat(view.completeness().percent()).isEqualTo(20 + 25 + 25);
		// Lo mismo se ve por la API.
		assertThat(student.api().get(PROFILE).json().get("completeness").get("percent").asInt()).isEqualTo(70);
	}

	@Test
	@DisplayName("ProfileEditor: un valor inválido da 422 con la ruta del campo y no se aplica nada de lo demás")
	void profileEditor_isAllOrNothingOnValidation() {
		RegisteredStudent student = registerStudent();

		assertThatThrownBy(() -> editor.applyConfirmed(student.id(), new ConfirmedChanges(
				new ConfirmedChanges.Academic("Software", null, 99),
				List.of(new ConfirmedChanges.Skill("java", 2), new ConfirmedChanges.Skill("cobol", 7)),
				List.of(new ConfirmedChanges.Project("Ok", "Una descripcion suficiente.", List.of("Java")),
						new ConfirmedChanges.Project("x", "Una descripcion suficiente.", List.of("Java"))))))
				.isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.getViolations())
						.extracting(v -> v.field() + ":" + v.rule())
						.containsExactlyInAnyOrder("academic.cycle:out-of-range", "skills[1].slug:unknown-skill",
								"skills[1].level:invalid-level", "projects[1].name:min-length"));

		assertThat(profileRows(student.id())).isZero();
		assertThat(count("SELECT count(*) FROM student_skill WHERE user_id = ?", student.id())).isZero();
		assertThat(count("SELECT count(*) FROM student_project WHERE user_id = ?", student.id())).isZero();
	}

	@Test
	@DisplayName("ProfileEditor: si los proyectos pasan de 10 da 422 limit y se deshace todo, también lo ya escrito")
	void profileEditor_limitRollsEverythingBack() {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		for (int i = 1; i <= 9; i++) {
			ok(api.post(PROFILE + "/projects", project("Proyecto " + i, description(), "Java")));
		}

		assertThatThrownBy(() -> editor.applyConfirmed(student.id(), new ConfirmedChanges(
				new ConfirmedChanges.Academic("Software", "Instituto Demo", 3),
				List.of(new ConfirmedChanges.Skill("java", 2)),
				List.of(new ConfirmedChanges.Project("Nuevo uno", "Una descripcion suficiente.", List.of("Java")),
						new ConfirmedChanges.Project("Nuevo dos", "Una descripcion suficiente.", List.of("Java"))))))
				.isInstanceOfSatisfying(ValidationFailedException.class, e -> assertThat(e.getViolations())
						.extracting(v -> v.field() + ":" + v.rule()).containsExactly("projects:limit"));

		assertThat(count("SELECT count(*) FROM student_project WHERE user_id = ?", student.id())).isEqualTo(9);
		assertThat(count("SELECT count(*) FROM student_skill WHERE user_id = ?", student.id())).isZero();
		JsonNodeAssert.assertAcademicEmpty(api.get(PROFILE).json());
	}

	@Test
	@DisplayName("ProfileEditor: sin cambios no falla; con un usuario que no existe responde 401 y no deja filas")
	void profileEditor_emptyChangesAndUnknownUser() {
		RegisteredStudent student = registerStudent();

		ProfileView untouched = editor.applyConfirmed(student.id(), new ConfirmedChanges(null, null, null));
		assertThat(untouched.completeness().percent()).isZero();

		UUID ghost = UUID.randomUUID();
		assertThatThrownBy(() -> editor.applyConfirmed(ghost, new ConfirmedChanges(null,
				List.of(new ConfirmedChanges.Skill("java", 1)), List.of())))
				.isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(401));
		assertThat(profileRows(ghost)).isZero();
	}

	/** Comprobaciones pequeñas sobre JSON para no repetir código. */
	private static final class JsonNodeAssert {

		static void assertAcademicEmpty(tools.jackson.databind.JsonNode profile) {
			assertThat(profile.get("academic").get("career").isNull()).as("lo académico no quedó a medias").isTrue();
		}

	}

}
