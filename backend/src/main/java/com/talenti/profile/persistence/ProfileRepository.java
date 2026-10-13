package com.talenti.profile.persistence;

import java.sql.Array;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.talenti.profile.SkillEvidence;
import com.talenti.profile.WorkModality;
import com.talenti.profile.domain.ProfileRules;

/**
 * Acceso a las tres tablas del módulo (student_profile, student_skill, student_project). Es lo único que las toca
 * (006-13). Todas las consultas van parametrizadas y filtran siempre por el dueño ({@code user_id}): ninguna
 * recibe un identificador sin su dueño (RNF-03).
 *
 * <p>Las escrituras de una persona empiezan asegurando su fila de {@code student_profile} ({@link #touch}), que
 * además la bloquea hasta el final de la transacción: dos escrituras de la misma persona se hacen una tras otra
 * (gana la última, y el límite de proyectos no se burla con dos solicitudes a la vez).
 */
@Repository
public class ProfileRepository {

	private final JdbcClient jdbc;

	ProfileRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	// ------------------------------------------------------------------------------------------ lectura

	public record ProfileRow(String career, String institute, Integer cycle, Integer hoursPerWeek,
			List<WorkModality> acceptedModalities, String location, OffsetDateTime updatedAt) {
	}

	public record SkillRow(UUID skillId, int level, SkillEvidence evidence) {
	}

	public record ProjectRow(UUID id, String name, String description, List<String> technologies) {
	}

	public Optional<ProfileRow> findProfile(UUID userId) {
		return jdbc.sql("""
				SELECT career, institute, cycle, hours_per_week, accepted_modalities, location, updated_at
				FROM student_profile WHERE user_id = :userId""")
				.param("userId", userId)
				.query((rs, row) -> new ProfileRow(rs.getString("career"), rs.getString("institute"),
						(Integer) rs.getObject("cycle"), (Integer) rs.getObject("hours_per_week"),
						modalities(rs.getArray("accepted_modalities")), rs.getString("location"),
						rs.getObject("updated_at", OffsetDateTime.class)))
				.optional();
	}

	public List<SkillRow> findSkills(UUID userId) {
		return jdbc.sql("SELECT skill_id, level, evidence FROM student_skill WHERE user_id = :userId")
				.param("userId", userId)
				.query((rs, row) -> new SkillRow(rs.getObject("skill_id", UUID.class), rs.getInt("level"),
						SkillEvidence.valueOf(rs.getString("evidence"))))
				.list();
	}

	public List<ProjectRow> findProjects(UUID userId) {
		return jdbc.sql("""
				SELECT id, name, description, technologies FROM student_project
				WHERE user_id = :userId ORDER BY created_at, id""")
				.param("userId", userId)
				.query((rs, row) -> new ProjectRow(rs.getObject("id", UUID.class), rs.getString("name"),
						rs.getString("description"), strings(rs.getArray("technologies"))))
				.list();
	}

	public int countProjects(UUID userId) {
		Integer count = jdbc.sql("SELECT count(*) FROM student_project WHERE user_id = :userId")
				.param("userId", userId).query(Integer.class).single();
		return count == null ? 0 : count;
	}

	// ------------------------------------------------------------------------------------------ escritura

	/**
	 * Crea la fila del perfil si no existe y marca la modificación. Bloquea la fila hasta el final de la
	 * transacción. Lanza una violación de integridad si el usuario ya no existe.
	 */
	public void touch(UUID userId) {
		jdbc.sql("""
				INSERT INTO student_profile (user_id) VALUES (:userId)
				ON CONFLICT (user_id) DO UPDATE SET updated_at = now()""")
				.param("userId", userId).update();
	}

	/** Reemplaza los tres datos académicos (006-05). Antes hay que llamar a {@link #touch}. */
	public void saveAcademic(UUID userId, ProfileRules.AcademicData data) {
		jdbc.sql("""
				UPDATE student_profile SET career = :career, institute = :institute, cycle = :cycle
				WHERE user_id = :userId""")
				.param("career", data.career()).param("institute", data.institute())
				.param("cycle", data.cycle(), java.sql.Types.SMALLINT).param("userId", userId).update();
	}

	/** Cambia solo los datos académicos que vienen (nulo = no cambia). Antes hay que llamar a {@link #touch}. */
	public void mergeAcademic(UUID userId, ProfileRules.AcademicData data) {
		jdbc.sql("""
				UPDATE student_profile SET career = COALESCE(:career, career), institute = COALESCE(:institute, institute),
				       cycle = COALESCE(:cycle, cycle)
				WHERE user_id = :userId""")
				.param("career", data.career()).param("institute", data.institute())
				.param("cycle", data.cycle(), java.sql.Types.SMALLINT).param("userId", userId).update();
	}

	/** Reemplaza horas, modalidades y ubicación (006-07). Antes hay que llamar a {@link #touch}. */
	public void saveAvailability(UUID userId, ProfileRules.AvailabilityData data) {
		String[] modalities = data.acceptedModalities().stream().map(Enum::name).toArray(String[]::new);
		jdbc.sql("""
				UPDATE student_profile SET hours_per_week = :hours, accepted_modalities = :modalities, location = :location
				WHERE user_id = :userId""")
				.param("hours", data.hoursPerWeek(), java.sql.Types.SMALLINT)
				.param("modalities", modalities).param("location", data.location()).param("userId", userId).update();
	}

	/** Reemplaza TODAS las habilidades de la persona (006-06), siempre como declaradas. */
	public void replaceSkills(UUID userId, List<ProfileRules.SkillData> skills) {
		jdbc.sql("DELETE FROM student_skill WHERE user_id = :userId").param("userId", userId).update();
		upsertSkills(userId, skills);
	}

	/** Agrega las habilidades que faltan y actualiza el nivel de las que ya están (siempre como declaradas). */
	public void upsertSkills(UUID userId, List<ProfileRules.SkillData> skills) {
		for (ProfileRules.SkillData skill : skills) {
			jdbc.sql("""
					INSERT INTO student_skill (user_id, skill_id, level, evidence)
					VALUES (:userId, :skillId, :level, 'DECLARED')
					ON CONFLICT (user_id, skill_id)
					DO UPDATE SET level = EXCLUDED.level, evidence = 'DECLARED', updated_at = now()""")
					.param("userId", userId).param("skillId", skill.skillId()).param("level", skill.level(),
							java.sql.Types.SMALLINT)
					.update();
		}
	}

	public UUID insertProject(UUID userId, ProfileRules.ProjectData project) {
		return jdbc.sql("""
				INSERT INTO student_project (user_id, name, description, technologies, created_at, updated_at)
				VALUES (:userId, :name, :description, :technologies, clock_timestamp(), clock_timestamp())
				RETURNING id""")
				.param("userId", userId).param("name", project.name()).param("description", project.description())
				.param("technologies", project.technologies().toArray(String[]::new))
				.query(UUID.class).single();
	}

	/** @return {@code false} si el proyecto no existe o no es de esa persona */
	public boolean updateProject(UUID userId, UUID projectId, ProfileRules.ProjectData project) {
		return jdbc.sql("""
				UPDATE student_project SET name = :name, description = :description, technologies = :technologies,
				       updated_at = now()
				WHERE id = :id AND user_id = :userId""")
				.param("name", project.name()).param("description", project.description())
				.param("technologies", project.technologies().toArray(String[]::new))
				.param("id", projectId).param("userId", userId).update() > 0;
	}

	/** @return {@code false} si el proyecto no existe o no es de esa persona */
	public boolean deleteProject(UUID userId, UUID projectId) {
		return jdbc.sql("DELETE FROM student_project WHERE id = :id AND user_id = :userId")
				.param("id", projectId).param("userId", userId).update() > 0;
	}

	// ------------------------------------------------------------------------------------------ arreglos

	private static List<String> strings(Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		try {
			return List.of((String[]) array.getArray());
		}
		finally {
			free(array);
		}
	}

	private static List<WorkModality> modalities(Array array) throws SQLException {
		List<WorkModality> result = new ArrayList<>();
		for (String value : strings(array)) {
			result.add(WorkModality.valueOf(value));
		}
		return result;
	}

	private static void free(Array array) {
		try {
			array.free();
		}
		catch (SQLException ignored) {
			// Liberar es opcional: el valor ya se leyó.
		}
	}

}
