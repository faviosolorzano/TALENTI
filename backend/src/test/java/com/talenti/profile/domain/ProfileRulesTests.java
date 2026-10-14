package com.talenti.profile.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.profile.WorkModality;
import com.talenti.shared.web.FieldViolation;
import com.talenti.shared.web.ValidationFailedException;

/** Validadores de cada sección (006-05 a 006-08): límites, vacíos, duplicados y tecnologías repetidas. */
class ProfileRulesTests {

	private final ProfileRules rules = new ProfileRules(10, 10);

	private static final UUID JAVA_ID = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");

	private static final UUID SQL_ID = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000002");

	private static final Map<String, UUID> CATALOG = Map.of("java", JAVA_ID, "sql", SQL_ID);

	private static String repeat(char c, int times) {
		char[] chars = new char[times];
		Arrays.fill(chars, c);
		return new String(chars);
	}

	/** Los errores (campo:regla) que lanza la validación, para comparar sin depender del orden de los argumentos. */
	private static List<String> violations(Runnable validation) {
		try {
			validation.run();
		}
		catch (ValidationFailedException e) {
			return e.getViolations().stream().map(v -> v.field() + ":" + v.rule()).toList();
		}
		return List.of();
	}

	private static ValidationFailedException failure(Runnable validation) {
		return (ValidationFailedException) catchThrowable(validation);
	}

	private static Throwable catchThrowable(Runnable validation) {
		try {
			validation.run();
		}
		catch (Throwable t) {
			return t;
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------ 006-05

	@Test
	@DisplayName("006-05: los textos se recortan y los vacíos o con solo espacios se guardan como nulos")
	void t006_05_trimsAndBlankBecomesNull() {
		ProfileRules.AcademicData data = rules.academic("", "  Desarrollo de Software  ", "   ", IntInput.absent());

		assertThat(data.career()).isEqualTo("Desarrollo de Software");
		assertThat(data.institute()).isNull();
		assertThat(data.cycle()).isNull();
		assertThat(rules.academic("", null, null, null)).isEqualTo(new ProfileRules.AcademicData(null, null, null));
	}

	@Test
	@DisplayName("006-05: carrera e instituto aceptan de 2 a 80 caracteres; 1 y 81 son errores")
	void t006_05_textLimits() {
		assertThat(rules.academic("", repeat('a', 2), repeat('b', 80), IntInput.absent()).career()).hasSize(2);
		assertThat(violations(() -> rules.academic("", "a", "ok", IntInput.absent()))).containsExactly("career:min-length");
		assertThat(violations(() -> rules.academic("", "ok", repeat('b', 81), IntInput.absent())))
				.containsExactly("institute:max-length");
	}

	@Test
	@DisplayName("006-05: el ciclo es un entero de 1 a 10; 0, 11 y lo que no es entero dan 422")
	void t006_05_cycleRange() {
		assertThat(rules.academic("", null, null, IntInput.of(1)).cycle()).isEqualTo(1);
		assertThat(rules.academic("", null, null, IntInput.of(10)).cycle()).isEqualTo(10);
		assertThat(violations(() -> rules.academic("", null, null, IntInput.of(0)))).containsExactly("cycle:out-of-range");
		assertThat(violations(() -> rules.academic("", null, null, IntInput.of(11)))).containsExactly("cycle:out-of-range");
		assertThat(violations(() -> rules.academic("", null, null, IntInput.of(-3)))).containsExactly("cycle:out-of-range");
		assertThat(violations(() -> rules.academic("", null, null, IntInput.notAnInteger())))
				.containsExactly("cycle:invalid");
	}

	@Test
	@DisplayName("006-05: el tope del ciclo sale de la configuración")
	void t006_05_maxCycleIsConfigurable() {
		ProfileRules eight = new ProfileRules(8, 10);

		assertThat(violations(() -> eight.academic("", null, null, IntInput.of(9)))).containsExactly("cycle:out-of-range");
		assertThat(eight.academic("", null, null, IntInput.of(8)).cycle()).isEqualTo(8);
	}

	@Test
	@DisplayName("006-05: junta todos los errores de la sección y no repite el valor recibido")
	void t006_05_collectsAllViolationsWithoutEchoingValues() {
		ValidationFailedException failure = failure(
				() -> rules.academic("", "x", repeat('z', 90), IntInput.of(99)));

		assertThat(failure.getViolations()).extracting(FieldViolation::field).containsExactly("career", "institute",
				"cycle");
		assertThat(failure.getViolations().toString()).doesNotContain("zzzz");
	}

	@Test
	@DisplayName("006-05: un carácter de control (NUL) se rechaza en vez de llegar a la base de datos")
	void t006_05_controlCharactersAreRejected() {
		assertThat(violations(() -> rules.academic("", "Desarrollo\u0000", null, null))).containsExactly("career:invalid");
		assertThat(violations(() -> rules.academic("", "Linea1\nLinea2", null, null))).containsExactly("career:invalid");
	}

	@Test
	@DisplayName("006-05: el prefijo de la ruta se antepone a los campos (lo usa ProfileEditor)")
	void t006_05_prefixIsPrependedToFieldPaths() {
		assertThat(violations(() -> rules.academic("academic.", "a", null, null))).containsExactly("academic.career:min-length");
	}

	// ------------------------------------------------------------------------------------------ 006-06

	private List<ProfileRules.SkillData> skills(ProfileRules.SkillInput... inputs) {
		return rules.skills("", Arrays.asList(inputs), CATALOG::get);
	}

	private static ProfileRules.SkillInput skill(String slug, Integer level) {
		return new ProfileRules.SkillInput(slug, IntInput.of(level));
	}

	@Test
	@DisplayName("006-06: habilidades del catálogo con nivel 1 a 3 son válidas; una lista vacía también")
	void t006_06_validSkillsAndEmptyList() {
		List<ProfileRules.SkillData> result = skills(skill("java", 3), skill("sql", 1));

		assertThat(result).extracting(ProfileRules.SkillData::skillId).containsExactly(JAVA_ID, SQL_ID);
		assertThat(result).extracting(ProfileRules.SkillData::level).containsExactly(3, 1);
		assertThat(skills()).isEmpty();
	}

	@Test
	@DisplayName("006-06: un slug que no está en el catálogo da unknown-skill, con la ruta del elemento")
	void t006_06_unknownSkill() {
		assertThat(violations(() -> skills(skill("java", 1), skill("cobol", 2))))
				.containsExactly("skills[1].slug:unknown-skill");
	}

	@Test
	@DisplayName("006-06: una habilidad repetida da duplicate")
	void t006_06_duplicateSkill() {
		assertThat(violations(() -> skills(skill("java", 1), skill("java", 2)))).containsExactly("skills[1].slug:duplicate");
	}

	@Test
	@DisplayName("006-06: un nivel fuera de 1 a 3, ausente o que no es entero da invalid-level")
	void t006_06_invalidLevel() {
		assertThat(violations(() -> skills(skill("java", 0)))).containsExactly("skills[0].level:invalid-level");
		assertThat(violations(() -> skills(skill("java", 4)))).containsExactly("skills[0].level:invalid-level");
		assertThat(violations(() -> skills(skill("java", null)))).containsExactly("skills[0].level:invalid-level");
		assertThat(violations(() -> skills(new ProfileRules.SkillInput("java", IntInput.notAnInteger()))))
				.containsExactly("skills[0].level:invalid-level");
	}

	@Test
	@DisplayName("006-06: sin la lista, un slug vacío o un elemento nulo dan required; no se resuelven sinónimos")
	void t006_06_missingPieces() {
		assertThat(violations(() -> rules.skills("", null, CATALOG::get))).containsExactly("skills:required");
		assertThat(violations(() -> skills(skill("  ", 1)))).containsExactly("skills[0].slug:required");
		assertThat(violations(() -> skills((ProfileRules.SkillInput) null)))
				.containsExactly("skills[0].slug:required", "skills[0].level:invalid-level");
		// La API acepta solo slug: "JS" o "Java" (con mayúscula) no son slugs.
		assertThat(violations(() -> skills(skill("Java", 1)))).containsExactly("skills[0].slug:unknown-skill");
	}

	@Test
	@DisplayName("006-06: una lista desmedida se rechaza sin procesarla")
	void t006_06_hugeListIsRejected() {
		List<ProfileRules.SkillInput> many = new ArrayList<>();
		for (int i = 0; i < ProfileRules.SKILLS_PER_REQUEST_MAX + 1; i++) {
			many.add(skill("java", 1));
		}

		assertThat(violations(() -> rules.skills("", many, CATALOG::get))).containsExactly("skills:max-items");
	}

	// ------------------------------------------------------------------------------------------ 006-07

	@Test
	@DisplayName("006-07: horas nulas o de 1 a 40, modalidades REMOTE/HYBRID/ONSITE sin repetir (o ninguna) y ubicación de 2 a 60")
	void t006_07_validAvailability() {
		ProfileRules.AvailabilityData data = rules.availability("", IntInput.of(20), List.of("REMOTE", "ONSITE"),
				"  Lima ");

		assertThat(data.hoursPerWeek()).isEqualTo(20);
		assertThat(data.acceptedModalities()).containsExactly(WorkModality.REMOTE, WorkModality.ONSITE);
		assertThat(data.location()).isEqualTo("Lima");
		ProfileRules.AvailabilityData empty = rules.availability("", IntInput.absent(), null, " ");
		assertThat(empty.hoursPerWeek()).isNull();
		assertThat(empty.acceptedModalities()).isEmpty();
		assertThat(empty.location()).isNull();
		assertThat(rules.availability("", IntInput.of(1), List.of(), null).hoursPerWeek()).isEqualTo(1);
		assertThat(rules.availability("", IntInput.of(40), List.of(), null).hoursPerWeek()).isEqualTo(40);
	}

	@Test
	@DisplayName("006-07: horas 0 o 41, modalidad desconocida o repetida y ubicación corta o larga dan 422")
	void t006_07_invalidAvailability() {
		assertThat(violations(() -> rules.availability("", IntInput.of(0), List.of(), null)))
				.containsExactly("hoursPerWeek:out-of-range");
		assertThat(violations(() -> rules.availability("", IntInput.of(41), List.of(), null)))
				.containsExactly("hoursPerWeek:out-of-range");
		assertThat(violations(() -> rules.availability("", IntInput.notAnInteger(), List.of(), null)))
				.containsExactly("hoursPerWeek:invalid");
		assertThat(violations(() -> rules.availability("", IntInput.absent(), List.of("REMOTE", "NOCTURNO"), null)))
				.containsExactly("acceptedModalities[1]:invalid");
		assertThat(violations(() -> rules.availability("", IntInput.absent(), List.of("remote"), null)))
				.containsExactly("acceptedModalities[0]:invalid");
		assertThat(violations(() -> rules.availability("", IntInput.absent(), List.of("HYBRID", "HYBRID"), null)))
				.containsExactly("acceptedModalities[1]:duplicate");
		assertThat(violations(() -> rules.availability("", IntInput.absent(), List.of(), "L")))
				.containsExactly("location:min-length");
		assertThat(violations(() -> rules.availability("", IntInput.absent(), List.of(), repeat('c', 61))))
				.containsExactly("location:max-length");
		assertThat(rules.availability("", IntInput.absent(), List.of(), repeat('c', 60)).location()).hasSize(60);
	}

	// ------------------------------------------------------------------------------------------ 006-08

	private ProfileRules.ProjectData project(String name, String description, String... technologies) {
		return rules.project("", name, description, Arrays.asList(technologies));
	}

	@Test
	@DisplayName("006-08: un proyecto válido se recorta y conserva sus tecnologías")
	void t006_08_validProject() {
		ProfileRules.ProjectData data = project("  Tienda web ", " Una tienda en línea con carrito. ", "Java ", "SQL");

		assertThat(data.name()).isEqualTo("Tienda web");
		assertThat(data.description()).isEqualTo("Una tienda en línea con carrito.");
		assertThat(data.technologies()).containsExactly("Java", "SQL");
	}

	@Test
	@DisplayName("006-08: nombre de 2 a 80 y descripción de 10 a 500 caracteres (con saltos de línea permitidos)")
	void t006_08_textLimits() {
		assertThat(violations(() -> project("a", repeat('d', 10), "Java"))).containsExactly("name:min-length");
		assertThat(violations(() -> project(repeat('n', 81), repeat('d', 10), "Java"))).containsExactly("name:max-length");
		assertThat(violations(() -> project("ab", repeat('d', 9), "Java"))).containsExactly("description:min-length");
		assertThat(violations(() -> project("ab", repeat('d', 501), "Java"))).containsExactly("description:max-length");
		assertThat(violations(() -> project("  ", "   ", "Java"))).containsExactly("name:required", "description:required");
		assertThatCode(() -> project(repeat('n', 80), repeat('d', 500), "Java")).doesNotThrowAnyException();
		assertThat(project("ab", "Primera línea.\nSegunda línea.", "Java").description()).contains("\n");
		assertThat(violations(() -> project("ab\ncd", repeat('d', 10), "Java"))).containsExactly("name:invalid");
	}

	@Test
	@DisplayName("006-08: de 1 a 8 tecnologías de 1 a 30 caracteres")
	void t006_08_technologyLimits() {
		assertThat(violations(() -> rules.project("", "ab", repeat('d', 10), List.of()))).containsExactly("technologies:required");
		assertThat(violations(() -> rules.project("", "ab", repeat('d', 10), null))).containsExactly("technologies:required");
		assertThat(project("ab", repeat('d', 10), "a", "b", "c", "d", "e", "f", "g", "h").technologies()).hasSize(8);
		assertThat(violations(() -> project("ab", repeat('d', 10), "a", "b", "c", "d", "e", "f", "g", "h", "i")))
				.containsExactly("technologies:max-items");
		assertThat(violations(() -> project("ab", repeat('d', 10), repeat('t', 31))))
				.containsExactly("technologies[0]:max-length");
		assertThat(violations(() -> project("ab", repeat('d', 10), "Java", "  ")))
				.containsExactly("technologies[1]:required");
		assertThat(project("ab", repeat('d', 10), repeat('t', 30)).technologies().get(0)).hasSize(30);
	}

	@Test
	@DisplayName("006-08: las tecnologías no se repiten, ignorando mayúsculas")
	void t006_08_technologiesAreCaseInsensitivelyUnique() {
		assertThat(violations(() -> project("ab", repeat('d', 10), "Java", "JAVA")))
				.containsExactly("technologies[1]:duplicate");
		assertThat(violations(() -> project("ab", repeat('d', 10), "Java", "SQL", " java ")))
				.containsExactly("technologies[2]:duplicate");
	}

	@Test
	@DisplayName("006-08: el límite de proyectos sale de la configuración: el 11.º no entra")
	void t006_08_projectLimit() {
		assertThatCode(() -> rules.checkProjectLimit("projects", 9, 1)).doesNotThrowAnyException();
		assertThatThrownBy(() -> rules.checkProjectLimit("projects", 10, 1)).isInstanceOf(ValidationFailedException.class)
				.satisfies(e -> assertThat(((ValidationFailedException) e).getViolations())
						.extracting(v -> v.field() + ":" + v.rule()).containsExactly("projects:limit"));
		assertThat(violations(() -> rules.checkProjectLimit("projects", 8, 3))).containsExactly("projects:limit");
		assertThat(violations(() -> new ProfileRules(10, 2).checkProjectLimit("projects", 2, 1)))
				.containsExactly("projects:limit");
	}

	// ------------------------------------------------------------------------------------------ 006-23

	@Test
	@DisplayName("006-23: presentación de 10 a 500; recortada, y los vacíos pasan a nulo")
	void t006_23_aboutLimitsAndTrim() {
		ProfileRules.AboutData data = rules.about("", "  Ana  ", " Pérez ", null, null, "  Una presentación válida.  ");

		assertThat(data.firstNames()).isEqualTo("Ana");
		assertThat(data.lastNames()).isEqualTo("Pérez");
		assertThat(data.about()).isEqualTo("Una presentación válida.");
		assertThat(rules.about("", " ", "", " ", "  ", null)).isEqualTo(new ProfileRules.AboutData(null, null, null, null, null));
		assertThat(violations(() -> rules.about("", null, null, null, null, "corta"))).containsExactly("about:min-length");
		assertThat(violations(() -> rules.about("", null, null, null, null, repeat('a', 501)))).containsExactly("about:max-length");
		assertThat(rules.about("", null, null, null, null, repeat('a', 500)).about()).hasSize(500);
		assertThat(rules.about("", null, null, null, null, "Línea uno.\nLínea dos.").about()).contains("\n");
	}

	@Test
	@DisplayName("006-27: nombres y apellidos de 2 a 60; solo letras con tildes y ñ, espacios, guion, apóstrofo y punto")
	void t006_27_namesRules() {
		assertThat(rules.about("", "María José", "Ñandú-O'Brien Jr.", null, null, null).lastNames()).isEqualTo("Ñandú-O'Brien Jr.");
		assertThat(rules.about("", repeat('n', 60), "Lo", null, null, null).firstNames()).hasSize(60);
		assertThat(violations(() -> rules.about("", "A", null, null, null, null))).containsExactly("firstNames:min-length");
		assertThat(violations(() -> rules.about("", null, repeat('n', 61), null, null, null))).containsExactly("lastNames:max-length");
		for (String bad : new String[] { "Ana2", "Ana_", "Ana@", "Ana#Pérez", "1234", "-Ana", "Ana\nPérez", "Ana \uD83D\uDC69" }) {
			assertThat(violations(() -> rules.about("", bad, null, null, null, null))).as("nombres %s", bad.length())
					.containsExactly("firstNames:invalid");
			assertThat(violations(() -> rules.about("", null, bad, null, null, null))).containsExactly("lastNames:invalid");
		}
	}

	@Test
	@DisplayName("006-23: invisibles, espacios duros como único contenido, marcas bidi y separadores de línea se rechazan")
	void t006_23_invisibleCharactersAreRejected() {
		for (String bad : new String[] { "\u200B\u200B", "\u00A0\u00A0\u00A0", "\u200E\u200F\u200E", "Ana\u202Ebuf",
				"Ana\u200Bmaria", "Ana\u2066x\u2069", "Ana\u2028Maria", "Ana\u2029Maria", "\uFEFFAna",
				"\u2003\u00A0\u2007\u202F" }) {
			assertThat(violations(() -> rules.about("", bad, null, null, null, null))).as("nombre %s", bad.length())
					.containsExactly("firstNames:invalid");
		}
		assertThat(violations(() -> rules.about("", null, null, "a\u200Bb@correo.pe", "\u200B912345678", null)))
				.containsExactly("contactEmail:invalid", "phone:invalid");
		assertThat(rules.about("", "María Ñandú", null, null, null, null).firstNames()).isEqualTo("María Ñandú");
	}

	@Test
	@DisplayName("006-30: el correo de contacto es opcional, se pasa a minúsculas y rechaza formatos inválidos y más de 254")
	void t006_30_contactEmail() {
		assertThat(rules.about("", null, null, "  Ana.Perez@Correo.PE ", null, null).contactEmail()).isEqualTo("ana.perez@correo.pe");
		for (String bad : new String[] { "sin-arroba", "@correo.pe", "ana@", "ana@correo", "ana pe@correo.pe", "ana@@correo.pe",
				"ana@correo..pe" }) {
			assertThat(violations(() -> rules.about("", null, null, bad, null, null))).as(bad)
					.containsExactly("contactEmail:invalid");
		}
		String longEmail = repeat('a', 60) + "@" + repeat('b', 195) + ".pe";
		assertThat(violations(() -> rules.about("", null, null, longEmail, null, null))).containsExactly("contactEmail:max-length");
	}

	@Test
	@DisplayName("006-30: el celular acepta +51 y espacios y se guarda con solo los 9 dígitos; rechaza lo que no es celular peruano")
	void t006_30_phoneNormalization() {
		for (String ok : new String[] { "912345678", "+51 912 345 678", "+51912345678", " 912 345 678 ", "+51 912345678" }) {
			assertThat(rules.about("", null, null, null, ok, null).phone()).as(ok).isEqualTo("912345678");
		}
		for (String bad : new String[] { "812345678", "91234567", "9123456789", "91234567a", "(01) 2345678", "+52912345678",
				"+51 +51 912345678", "912-345-678" }) {
			assertThat(violations(() -> rules.about("", null, null, null, bad, null))).as(bad)
					.containsExactly("phone:invalid");
		}
	}

	@Test
	@DisplayName("006-12: el toString de los resultados de validación no imprime contenido")
	void t006_12_toStringIsMasked() {
		assertThat(rules.about("", "Ana Secreta", null, "secreto@correo.pe", "912345678", "Una presentación secreta.").toString())
				.doesNotContain("Secreta").doesNotContain("secreto").doesNotContain("912345678");
		assertThat(rules.academic("", "Carrera Secreta", null, null).toString()).doesNotContain("Secreta");
		assertThat(project("Proyecto Secreto", repeat('d', 10), "Java").toString()).doesNotContain("Secreto");
	}

	@Test
	@DisplayName("006-08: Unicode vacío como único contenido de un campo obligatorio o de una tecnología se rechaza")
	void t006_08_blankLikeUnicodeIsRejectedEverywhere() {
		assertThat(violations(() -> project("\u00A0\u00A0", repeat('d', 10), "Java"))).containsExactly("name:invalid");
		assertThat(violations(() -> project("ab", repeat('d', 10), "\u200B"))).containsExactly("technologies[0]:invalid");
		assertThat(violations(() -> rules.academic("", "\u00A0\u00A0", null, null))).containsExactly("career:invalid");
		assertThat(violations(() -> rules.availability("", null, null, "\u200B\u200B"))).containsExactly("location:invalid");
	}

	@Test
	@DisplayName("006-08: las tecnologías se comparan en NFC: é compuesta y descompuesta son la misma")
	void t006_08_technologiesAreComparedInNfc() {
		assertThat(violations(() -> project("ab", repeat('d', 10), "Caf\u00E9", "CAFE\u0301")))
				.containsExactly("technologies[1]:duplicate");
		// Se guarda en NFC.
		assertThat(project("ab", repeat('d', 10), "Cafe\u0301").technologies().get(0)).isEqualTo("Caf\u00E9");
	}

	@Test
	@DisplayName("006-05: un max-cycle mayor que 10 (lo que admite la base de datos) impide arrancar")
	void t006_05_maxCycleCannotExceedTheDatabaseLimit() {
		assertThatThrownBy(() -> new ProfileRules(11, 10)).isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("max-cycle");
		assertThatCode(() -> new ProfileRules(10, 10)).doesNotThrowAnyException();
	}

	@Test
	@DisplayName("006-05: los parámetros de configuración inválidos impiden arrancar")
	void t006_05_invalidConfigurationFailsFast() {
		assertThatThrownBy(() -> new ProfileRules(0, 10)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new ProfileRules(10, 0)).isInstanceOf(IllegalArgumentException.class);
	}

}
