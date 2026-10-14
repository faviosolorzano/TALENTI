package com.talenti.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** 006-01: la migración V5 crea las tres tablas con sus restricciones, y la cascada y el catálogo se comportan. */
class ProfileSchemaTests extends ProfileIntegrationTest {

	@Test
	@DisplayName("006-01: Flyway aplicó V6 y existen student_profile, student_skill y student_project con los tipos de la spec")
	void t006_01_tablesAndColumns() {
		assertThat(count("SELECT count(*) FROM flyway_schema_history WHERE version = '6' AND success")).isEqualTo(1);
		Map<String, String> profileColumns = columns("student_profile");
		assertThat(profileColumns).containsEntry("first_names", "character varying").containsEntry("last_names", "character varying").containsEntry("contact_email", "character varying").containsEntry("phone", "character varying")
				.containsEntry("about", "character varying").containsEntry("user_id", "uuid").containsEntry("career", "character varying")
				.containsEntry("institute", "character varying").containsEntry("cycle", "smallint")
				.containsEntry("hours_per_week", "smallint").containsEntry("accepted_modalities", "ARRAY")
				.containsEntry("location", "character varying").containsEntry("created_at", "timestamp with time zone")
				.containsEntry("updated_at", "timestamp with time zone");
		assertThat(columns("student_skill")).containsEntry("user_id", "uuid").containsEntry("skill_id", "uuid")
				.containsEntry("level", "smallint").containsEntry("evidence", "character varying")
				.containsEntry("updated_at", "timestamp with time zone");
		assertThat(columns("student_project")).containsEntry("id", "uuid").containsEntry("user_id", "uuid")
				.containsEntry("name", "character varying").containsEntry("description", "character varying")
				.containsEntry("technologies", "ARRAY").containsEntry("created_at", "timestamp with time zone")
				.containsEntry("updated_at", "timestamp with time zone");
		assertThat(jdbc.queryForObject("""
				SELECT character_maximum_length FROM information_schema.columns
				WHERE table_name = 'student_project' AND column_name = 'description'""", Integer.class)).isEqualTo(500);
		assertThat(jdbc.queryForObject("""
				SELECT indexdef FROM pg_indexes WHERE tablename = 'student_project' AND indexname = 'ix_student_project_user'""",
				String.class)).contains("(user_id)");
	}

	@Test
	@DisplayName("006-01: las claves foráneas: los tres a app_user en cascada; student_skill a skill con RESTRICT")
	void t006_01_foreignKeys() {
		assertThat(foreignKey("student_profile", "app_user")).isEqualTo("c");
		assertThat(foreignKey("student_skill", "app_user")).isEqualTo("c");
		assertThat(foreignKey("student_project", "app_user")).isEqualTo("c");
		assertThat(foreignKey("student_skill", "skill")).isEqualTo("r");
	}

	@Test
	@DisplayName("006-01: las restricciones de la base rechazan ciclo, horas, modalidad, nivel, evidencia y tecnologías inválidos")
	void t006_01_checkConstraints() {
		UUID user = registerStudent().id();
		UUID skill = jdbc.queryForObject("SELECT id FROM skill WHERE slug = 'java'", UUID.class);
		jdbc.update("INSERT INTO student_profile (user_id) VALUES (?)", user);

		assertRejected("UPDATE student_profile SET cycle = 11 WHERE user_id = ?", user);
		assertRejected("UPDATE student_profile SET cycle = 0 WHERE user_id = ?", user);
		assertRejected("UPDATE student_profile SET hours_per_week = 41 WHERE user_id = ?", user);
		assertRejected("UPDATE student_profile SET hours_per_week = 0 WHERE user_id = ?", user);
		assertRejected("UPDATE student_profile SET accepted_modalities = ARRAY['NOCTURNO']::varchar[] WHERE user_id = ?", user);
		assertRejected("UPDATE student_profile SET career = '   ' WHERE user_id = ?", user);
		assertRejected("UPDATE student_profile SET first_names = '  ' WHERE user_id = ?", user);
		assertRejected("UPDATE student_profile SET about = repeat('a', 501) WHERE user_id = ?", user);
		assertRejected("INSERT INTO student_skill (user_id, skill_id, level) VALUES (?, ?, 4)", user, skill);
		assertRejected("INSERT INTO student_skill (user_id, skill_id, level) VALUES (?, ?, 0)", user, skill);
		assertRejected("INSERT INTO student_skill (user_id, skill_id, level, evidence) VALUES (?, ?, 1, 'INVENTADA')", user, skill);
		assertRejected("INSERT INTO student_project (user_id, name, description, technologies) VALUES (?, 'ab', 'una descripcion', ARRAY[]::varchar[])", user);
		assertRejected("INSERT INTO student_project (user_id, name, description, technologies) VALUES (?, 'ab', 'una descripcion', ARRAY['1','2','3','4','5','6','7','8','9']::varchar[])", user);
		// Y los valores válidos sí entran; la evidencia por defecto es DECLARED.
		jdbc.update("INSERT INTO student_skill (user_id, skill_id, level) VALUES (?, ?, 3)", user, skill);
		assertThat(jdbc.queryForObject("SELECT evidence FROM student_skill WHERE user_id = ?", String.class, user))
				.isEqualTo("DECLARED");
		// La clave (user_id, skill_id) no admite la misma habilidad dos veces.
		assertRejected("INSERT INTO student_skill (user_id, skill_id, level) VALUES (?, ?, 1)", user, skill);
	}

	@Test
	@DisplayName("006-01: al borrar la cuenta se borra en cascada el perfil, las habilidades y los proyectos (RN-12)")
	void t006_01_deletingTheAccountCascades() {
		RegisteredStudent student = registerStudent();
		fillEverything(student.api());
		RegisteredStudent other = registerStudent();
		fillEverything(other.api());
		assertThat(count("SELECT count(*) FROM student_skill WHERE user_id = ?", student.id())).isEqualTo(3);

		jdbc.update("DELETE FROM app_user WHERE id = ?", student.id());

		assertThat(profileRows(student.id())).isZero();
		assertThat(count("SELECT count(*) FROM student_skill WHERE user_id = ?", student.id())).isZero();
		assertThat(count("SELECT count(*) FROM student_project WHERE user_id = ?", student.id())).isZero();
		assertThat(profileRows(other.id())).as("la cuenta ajena no se toca").isEqualTo(1);
		assertThat(count("SELECT count(*) FROM student_skill WHERE user_id = ?", other.id())).isEqualTo(3);
	}

	@Test
	@DisplayName("006-01: una habilidad del catálogo que alguien usa no se puede borrar (RESTRICT)")
	void t006_01_usedCatalogSkillCannotBeDeleted() {
		fillEverything(student());

		assertThatThrownBy(() -> jdbc.update("DELETE FROM skill WHERE slug = 'java'"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThat(count("SELECT count(*) FROM skill WHERE slug = 'java'")).isEqualTo(1);
	}

	@Test
	@DisplayName("006-01: un usuario que no existe no puede tener perfil (la clave foránea lo impide)")
	void t006_01_profileNeedsAnExistingUser() {
		assertRejected("INSERT INTO student_profile (user_id) VALUES (?)", UUID.randomUUID());
	}

	private Map<String, String> columns(String table) {
		Map<String, String> result = new java.util.HashMap<>();
		jdbc.query("SELECT column_name, data_type FROM information_schema.columns WHERE table_name = ?",
				rs -> {
					result.put(rs.getString("column_name"), rs.getString("data_type"));
				}, table);
		assertThat(result).as("la tabla %s existe", table).isNotEmpty();
		return result;
	}

	/** El código de borrado ({@code c} = cascada, {@code r} = restringir) de la clave foránea de una tabla a otra. */
	private String foreignKey(String from, String to) {
		List<String> actions = jdbc.queryForList("""
				SELECT confdeltype::text FROM pg_constraint
				WHERE contype = 'f' AND conrelid = ?::regclass AND confrelid = ?::regclass""", String.class, from, to);
		assertThat(actions).as("clave foránea de %s a %s", from, to).hasSize(1);
		return actions.get(0);
	}

	private void assertRejected(String sql, Object... args) {
		assertThatThrownBy(() -> jdbc.update(sql, args)).as(sql).isInstanceOf(DataIntegrityViolationException.class);
	}

}
