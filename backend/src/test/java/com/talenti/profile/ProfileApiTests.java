package com.talenti.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/** Rutas de {@code /profile} (spec 006, sección 6): 006-03, 006-05 a 006-11. Cada prueba usa su propio estudiante. */
class ProfileApiTests extends ProfileIntegrationTest {

	// ------------------------------------------------------------------------------------------ 006-03

	@Test
	@DisplayName("006-03: un estudiante sin perfil guardado recibe el perfil vacío con 0 % y la lectura no crea ninguna fila")
	void t006_03_emptyProfileAndReadCreatesNothing() {
		RegisteredStudent student = registerStudent();

		Response response = student.api().get(PROFILE);

		assertThat(response.status()).isEqualTo(200);
		JsonNode profile = response.json();
		assertThat(profile.get("academic").get("career").isNull()).isTrue();
		assertThat(profile.get("academic").get("institute").isNull()).isTrue();
		assertThat(profile.get("academic").get("cycle").isNull()).isTrue();
		assertThat(profile.get("skills")).isEmpty();
		assertThat(profile.get("projects")).isEmpty();
		assertThat(profile.get("availability").get("hoursPerWeek").isNull()).isTrue();
		assertThat(profile.get("availability").get("acceptedModalities")).isEmpty();
		assertThat(profile.get("availability").get("location").isNull()).isTrue();
		assertThat(profile.get("updatedAt").isNull()).isTrue();
		assertThat(profile.get("completeness").get("percent").asInt()).isZero();
		assertThat(profile.get("completeness").get("items")).hasSize(6);
		assertThat(profile.get("completeness").get("nextStep").asString()).isEqualTo("SKILLS");
		assertThat(profileRows(student.id())).as("GET no crea la fila").isZero();
		assertThat(count("SELECT count(*) FROM student_skill WHERE user_id = ?", student.id())).isZero();
		assertThat(count("SELECT count(*) FROM student_project WHERE user_id = ?", student.id())).isZero();
	}

	// ------------------------------------------------------------------------------------------ 006-05

	@Test
	@DisplayName("006-05: PUT /academic recorta los textos, guarda los vacíos como nulos y devuelve el perfil con el contrato")
	void t006_05_academicTrimsAndNullsBlank() {
		ApiClient api = student();

		JsonNode profile = ok(api.put(PROFILE + "/academic",
				body("career", "  Desarrollo de Software  ", "institute", "   ", "cycle", 4)));

		assertThat(profile.get("academic").get("career").asString()).isEqualTo("Desarrollo de Software");
		assertThat(profile.get("academic").get("institute").isNull()).isTrue();
		assertThat(profile.get("academic").get("cycle").asInt()).isEqualTo(4);
		assertThat(profile.get("updatedAt").asString()).isNotBlank();
		assertThat(profile.get("completeness").get("percent").asInt()).isZero();
		// Persistió: otra lectura devuelve lo mismo.
		assertThat(api.get(PROFILE).json().get("academic").get("career").asString()).isEqualTo("Desarrollo de Software");
	}

	@Test
	@DisplayName("006-05: un PUT /academic reemplaza la sección completa: lo no enviado queda nulo")
	void t006_05_academicReplacesTheWholeSection() {
		ApiClient api = student();
		ok(api.put(PROFILE + "/academic", body("career", "Redes", "institute", "Instituto Demo", "cycle", 3)));

		JsonNode profile = ok(api.put(PROFILE + "/academic", body("career", "Software")));

		assertThat(profile.get("academic").get("career").asString()).isEqualTo("Software");
		assertThat(profile.get("academic").get("institute").isNull()).isTrue();
		assertThat(profile.get("academic").get("cycle").isNull()).isTrue();
	}

	@Test
	@DisplayName("006-05: ciclo fuera de 1 a 10, texto fuera de 2 a 80 o ciclo no entero dan 422 con errors[{field, rule}]")
	void t006_05_academicValidation() {
		ApiClient api = student();

		Response badCycle = api.put(PROFILE + "/academic", body("career", "Software", "institute", "Demo", "cycle", 11));
		Response zeroCycle = api.put(PROFILE + "/academic", body("cycle", 0));
		Response decimalCycle = api.put(PROFILE + "/academic", body("cycle", 3.5));
		Response textCycle = api.put(PROFILE + "/academic", body("cycle", "5"));
		Response shortCareer = api.put(PROFILE + "/academic", body("career", "x"));
		Response longInstitute = api.put(PROFILE + "/academic", body("institute", repeat('i', 81)));

		assertThat(errors(badCycle)).containsExactly("cycle:out-of-range");
		assertThat(errors(zeroCycle)).containsExactly("cycle:out-of-range");
		assertThat(errors(decimalCycle)).containsExactly("cycle:invalid");
		assertThat(errors(textCycle)).containsExactly("cycle:invalid");
		assertThat(errors(shortCareer)).containsExactly("career:min-length");
		assertThat(errors(longInstitute)).containsExactly("institute:max-length");
		assertThat(badCycle.json().get("code").asString()).isEqualTo("validation-failed");
		assertThat(badCycle.json().get("errors").get(0).get("message").asString()).isNotBlank().contains("1").contains("10");
		assertThat(badCycle.header("Content-Type")).contains("application/problem+json");
	}

	@Test
	@DisplayName("006-05: un 422 no guarda nada de esa sección y no toca las demás")
	void t006_05_invalidSaveChangesNothing() {
		ApiClient api = student();
		fillEverything(api);
		JsonNode before = api.get(PROFILE).json();

		Response rejected = api.put(PROFILE + "/academic", body("career", "Otra carrera", "institute", "Otro", "cycle", 99));

		assertThat(rejected.status()).isEqualTo(422);
		JsonNode after = api.get(PROFILE).json();
		assertThat(after.get("academic")).isEqualTo(before.get("academic"));
		assertThat(after.get("skills")).isEqualTo(before.get("skills"));
		assertThat(after.get("availability")).isEqualTo(before.get("availability"));
		assertThat(after.get("projects")).isEqualTo(before.get("projects"));
	}

	@Test
	@DisplayName("006-05: un JSON mal formado da 400 y un texto con carácter de control da 422 (no 500)")
	void t006_05_malformedAndControlCharacters() {
		ApiClient api = student();

		Response nul = api.put(PROFILE + "/academic", body("career", "Soft\u0000ware"));
		Response notAnObject = api.put(PROFILE + "/academic", List.of("a"));

		assertThat(errors(nul)).containsExactly("career:invalid");
		assertThat(notAnObject.status()).isEqualTo(400);
		assertThat(notAnObject.json().get("code").asString()).isEqualTo("malformed-request");
	}

	// ------------------------------------------------------------------------------------------ 006-06

	@Test
	@DisplayName("006-06: PUT /skills reemplaza la lista completa; guarda con evidencia DECLARED y devuelve nombre y nivel")
	void t006_06_replacesTheWholeList() {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();

		JsonNode first = ok(api.put(PROFILE + "/skills", skills(skill("java", 2), skill("sql", 1))));
		JsonNode second = ok(api.put(PROFILE + "/skills", skills(skill("git", 3))));

		assertThat(first.get("skills")).hasSize(2);
		assertThat(first.get("skills").get(0).get("slug").asString()).isEqualTo("java");
		assertThat(first.get("skills").get(0).get("name").asString()).isEqualTo("Java");
		assertThat(first.get("skills").get(0).get("level").asInt()).isEqualTo(2);
		assertThat(first.get("skills").get(0).get("evidence").asString()).isEqualTo("DECLARED");
		assertThat(second.get("skills")).hasSize(1);
		assertThat(second.get("skills").get(0).get("slug").asString()).isEqualTo("git");
		assertThat(count("SELECT count(*) FROM student_skill WHERE user_id = ?", student.id())).isEqualTo(1);
		assertThat(count("SELECT count(*) FROM student_skill WHERE user_id = ? AND evidence = 'DECLARED'", student.id()))
				.isEqualTo(1);
	}

	@Test
	@DisplayName("006-06: las habilidades salen ordenadas por nombre")
	void t006_06_skillsAreSortedByName() {
		ApiClient api = student();

		JsonNode profile = ok(api.put(PROFILE + "/skills", skills(skill("sql", 1), skill("react", 2), skill("java", 3))));

		List<String> names = new ArrayList<>();
		profile.get("skills").forEach(item -> names.add(item.get("name").asString()));
		assertThat(names).containsExactly("Java", "React", "SQL");
	}

	@Test
	@DisplayName("006-06: una lista vacía es válida y borra todas las habilidades")
	void t006_06_emptyListIsValid() {
		ApiClient api = student();
		ok(api.put(PROFILE + "/skills", skills(skill("java", 2))));

		JsonNode profile = ok(api.put(PROFILE + "/skills", skills()));

		assertThat(profile.get("skills")).isEmpty();
	}

	@Test
	@DisplayName("006-06: slug inexistente, repetido o nivel inválido dan 422 y se conserva la lista anterior (reemplazo atómico)")
	void t006_06_invalidListKeepsThePreviousOne() {
		ApiClient api = student();
		ok(api.put(PROFILE + "/skills", skills(skill("java", 2), skill("sql", 1))));

		Response unknown = api.put(PROFILE + "/skills", skills(skill("git", 1), skill("cobol", 2)));
		Response duplicate = api.put(PROFILE + "/skills", skills(skill("git", 1), skill("git", 2)));
		Response lowLevel = api.put(PROFILE + "/skills", skills(skill("git", 0)));
		Response highLevel = api.put(PROFILE + "/skills", skills(skill("git", 4)));
		Response noLevel = api.put(PROFILE + "/skills", skills(skill("git", null)));
		Response textLevel = api.put(PROFILE + "/skills", skills(skill("git", "2")));
		Response synonym = api.put(PROFILE + "/skills", skills(skill("JS", 2)));
		Response noList = api.put(PROFILE + "/skills", body());

		assertThat(errors(unknown)).containsExactly("skills[1].slug:unknown-skill");
		assertThat(errors(duplicate)).containsExactly("skills[1].slug:duplicate");
		assertThat(errors(lowLevel)).containsExactly("skills[0].level:invalid-level");
		assertThat(errors(highLevel)).containsExactly("skills[0].level:invalid-level");
		assertThat(errors(noLevel)).containsExactly("skills[0].level:invalid-level");
		assertThat(errors(textLevel)).containsExactly("skills[0].level:invalid-level");
		assertThat(errors(synonym)).as("la API acepta solo slug").containsExactly("skills[0].slug:unknown-skill");
		assertThat(errors(noList)).containsExactly("skills:required");
		JsonNode after = api.get(PROFILE).json();
		assertThat(after.get("skills")).hasSize(2);
		assertThat(after.get("skills").get(0).get("slug").asString()).isEqualTo("java");
	}

	// ------------------------------------------------------------------------------------------ 006-07

	@Test
	@DisplayName("006-07: PUT /availability guarda horas, modalidades y ubicación (recortada) y las devuelve")
	void t006_07_savesAvailability() {
		ApiClient api = student();

		JsonNode profile = ok(api.put(PROFILE + "/availability",
				body("hoursPerWeek", 20, "acceptedModalities", List.of("REMOTE", "ONSITE"), "location", " Lima ")));

		assertThat(profile.get("availability").get("hoursPerWeek").asInt()).isEqualTo(20);
		assertThat(profile.get("availability").get("acceptedModalities").get(0).asString()).isEqualTo("REMOTE");
		assertThat(profile.get("availability").get("acceptedModalities").get(1).asString()).isEqualTo("ONSITE");
		assertThat(profile.get("availability").get("location").asString()).isEqualTo("Lima");
		// Todo nulo y sin modalidades también es válido.
		JsonNode cleared = ok(api.put(PROFILE + "/availability",
				body("hoursPerWeek", null, "acceptedModalities", List.of(), "location", null)));
		assertThat(cleared.get("availability").get("hoursPerWeek").isNull()).isTrue();
		assertThat(cleared.get("availability").get("acceptedModalities")).isEmpty();
		assertThat(cleared.get("availability").get("location").isNull()).isTrue();
	}

	@Test
	@DisplayName("006-07: horas fuera de 1 a 40, modalidad desconocida o repetida y ubicación fuera de 2 a 60 dan 422 y no guardan")
	void t006_07_availabilityValidation() {
		ApiClient api = student();
		ok(api.put(PROFILE + "/availability", body("hoursPerWeek", 10, "acceptedModalities", List.of("HYBRID"), "location", "Cusco")));

		Response zeroHours = api.put(PROFILE + "/availability", body("hoursPerWeek", 0));
		Response tooManyHours = api.put(PROFILE + "/availability", body("hoursPerWeek", 41));
		Response decimalHours = api.put(PROFILE + "/availability", body("hoursPerWeek", 10.5));
		Response unknownModality = api.put(PROFILE + "/availability", body("acceptedModalities", List.of("REMOTE", "NOCTURNO")));
		Response repeatedModality = api.put(PROFILE + "/availability", body("acceptedModalities", List.of("HYBRID", "HYBRID")));
		Response shortLocation = api.put(PROFILE + "/availability", body("location", "L"));
		Response longLocation = api.put(PROFILE + "/availability", body("location", repeat('c', 61)));

		assertThat(errors(zeroHours)).containsExactly("hoursPerWeek:out-of-range");
		assertThat(errors(tooManyHours)).containsExactly("hoursPerWeek:out-of-range");
		assertThat(errors(decimalHours)).containsExactly("hoursPerWeek:invalid");
		assertThat(errors(unknownModality)).containsExactly("acceptedModalities[1]:invalid");
		assertThat(errors(repeatedModality)).containsExactly("acceptedModalities[1]:duplicate");
		assertThat(errors(shortLocation)).containsExactly("location:min-length");
		assertThat(errors(longLocation)).containsExactly("location:max-length");
		JsonNode availability = api.get(PROFILE).json().get("availability");
		assertThat(availability.get("hoursPerWeek").asInt()).isEqualTo(10);
		assertThat(availability.get("location").asString()).isEqualTo("Cusco");
	}

	// ------------------------------------------------------------------------------------------ 006-08

	@Test
	@DisplayName("006-08: POST responde 201 con el perfil y el proyecto con id; PUT y DELETE responden 200")
	void t006_08_createUpdateDelete() {
		ApiClient api = student();

		Response created = api.post(PROFILE + "/projects", project("  Tienda web ", description(), "Java", "PostgreSQL"));

		assertThat(created.status()).isEqualTo(201);
		JsonNode projects = created.json().get("projects");
		assertThat(projects).hasSize(1);
		String id = projects.get(0).get("id").asString();
		assertThat(UUID.fromString(id)).isNotNull();
		assertThat(projects.get(0).get("name").asString()).isEqualTo("Tienda web");
		assertThat(projects.get(0).get("technologies")).hasSize(2);

		Response updated = api.put(PROFILE + "/projects/" + id, project("Tienda web v2", description() + " Con mejoras.", "Java"));
		assertThat(updated.status()).isEqualTo(200);
		assertThat(updated.json().get("projects")).hasSize(1);
		assertThat(updated.json().get("projects").get(0).get("id").asString()).isEqualTo(id);
		assertThat(updated.json().get("projects").get(0).get("name").asString()).isEqualTo("Tienda web v2");
		assertThat(updated.json().get("projects").get(0).get("technologies")).hasSize(1);

		Response deleted = api.delete(PROFILE + "/projects/" + id);
		assertThat(deleted.status()).isEqualTo(200);
		assertThat(deleted.json().get("projects")).isEmpty();
	}

	@Test
	@DisplayName("006-08: los proyectos salen en el orden en que se crearon")
	void t006_08_projectsKeepCreationOrder() {
		ApiClient api = student();
		for (int i = 1; i <= 4; i++) {
			ok(api.post(PROFILE + "/projects", project("Proyecto " + i, description(), "Java")));
		}

		List<String> names = new ArrayList<>();
		api.get(PROFILE).json().get("projects").forEach(item -> names.add(item.get("name").asString()));

		assertThat(names).containsExactly("Proyecto 1", "Proyecto 2", "Proyecto 3", "Proyecto 4");
	}

	@Test
	@DisplayName("006-08: nombre, descripción y tecnologías se validan (límites, vacíos y tecnologías repetidas sin importar mayúsculas)")
	void t006_08_projectValidation() {
		ApiClient api = student();

		assertThat(errors(api.post(PROFILE + "/projects", project("a", description(), "Java")))).containsExactly("name:min-length");
		assertThat(errors(api.post(PROFILE + "/projects", project("Ok", "corta", "Java")))).containsExactly("description:min-length");
		assertThat(errors(api.post(PROFILE + "/projects", project("Ok", repeat('d', 501), "Java"))))
				.containsExactly("description:max-length");
		assertThat(errors(api.post(PROFILE + "/projects", project("Ok", description())))).containsExactly("technologies:required");
		assertThat(errors(api.post(PROFILE + "/projects", project("Ok", description(), "a", "b", "c", "d", "e", "f", "g", "h", "i"))))
				.containsExactly("technologies:max-items");
		assertThat(errors(api.post(PROFILE + "/projects", project("Ok", description(), "Java", "java"))))
				.containsExactly("technologies[1]:duplicate");
		assertThat(errors(api.post(PROFILE + "/projects", project("Ok", description(), repeat('t', 31)))))
				.containsExactly("technologies[0]:max-length");
		assertThat(errors(api.post(PROFILE + "/projects", body("name", "  ", "description", null, "technologies", null))))
				.containsExactly("name:required", "description:required", "technologies:required");
		assertThat(api.get(PROFILE).json().get("projects")).isEmpty();
	}

	@Test
	@DisplayName("006-08: se permiten hasta 10 proyectos; el 11.º da 422 limit y no se guarda; al borrar uno se puede agregar otro")
	void t006_08_projectLimit() {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();
		String lastId = null;
		for (int i = 1; i <= 10; i++) {
			JsonNode profile = ok(api.post(PROFILE + "/projects", project("Proyecto " + i, description(), "Java")));
			lastId = profile.get("projects").get(profile.get("projects").size() - 1).get("id").asString();
		}

		Response eleventh = api.post(PROFILE + "/projects", project("Proyecto 11", description(), "Java"));

		assertThat(errors(eleventh)).containsExactly("projects:limit");
		assertThat(eleventh.json().get("errors").get(0).get("message").asString()).contains("10");
		assertThat(count("SELECT count(*) FROM student_project WHERE user_id = ?", student.id())).isEqualTo(10);
		assertThat(api.delete(PROFILE + "/projects/" + lastId).status()).isEqualTo(200);
		assertThat(api.post(PROFILE + "/projects", project("Proyecto 11", description(), "Java")).status()).isEqualTo(201);
	}

	@Test
	@DisplayName("006-08: un proyecto inexistente o con id mal formado da 404 project-not-found, igual en PUT y DELETE")
	void t006_08_unknownProjectIs404() {
		ApiClient api = student();
		String missing = UUID.randomUUID().toString();

		for (String id : List.of(missing, "no-es-un-uuid", "123")) {
			Response put = api.put(PROFILE + "/projects/" + id, project("Nombre", description(), "Java"));
			Response delete = api.delete(PROFILE + "/projects/" + id);

			assertThat(put.status()).as("PUT " + id).isEqualTo(404);
			assertThat(delete.status()).as("DELETE " + id).isEqualTo(404);
			assertThat(put.json().get("code").asString()).isEqualTo("project-not-found");
			assertThat(delete.json().get("code").asString()).isEqualTo("project-not-found");
			assertThat(put.json().get("detail").asString()).isNotBlank();
		}
		// Un 404 no deja una fila de perfil creada a medias.
		assertThat(api.get(PROFILE).json().get("updatedAt").isNull()).isTrue();
	}

	// ------------------------------------------------------------------------------------------ 006-09, 10, 11

	@Test
	@DisplayName("006-11: cada escritura devuelve el perfil completo con el porcentaje nuevo (la tercera habilidad suma 25)")
	void t006_11_everyWriteReturnsTheNewPercentage() {
		ApiClient api = student();

		JsonNode academic = ok(api.put(PROFILE + "/academic", body("career", "Software", "institute", "Demo", "cycle", 3)));
		assertThat(academic.get("completeness").get("percent").asInt()).isEqualTo(15);

		JsonNode twoSkills = ok(api.put(PROFILE + "/skills", skills(skill("java", 1), skill("sql", 1))));
		assertThat(twoSkills.get("completeness").get("percent").asInt()).as("2 habilidades no suman").isEqualTo(15);
		assertThat(twoSkills.get("academic").get("career").asString()).as("el perfil viene completo").isEqualTo("Software");

		JsonNode threeSkills = ok(api.put(PROFILE + "/skills", skills(skill("java", 1), skill("sql", 1), skill("git", 2))));
		assertThat(threeSkills.get("completeness").get("percent").asInt()).isEqualTo(40);

		JsonNode availability = ok(api.put(PROFILE + "/availability",
				body("hoursPerWeek", 15, "acceptedModalities", List.of("REMOTE"), "location", null)));
		assertThat(availability.get("completeness").get("percent").asInt()).isEqualTo(55);

		JsonNode project = ok(api.post(PROFILE + "/projects", project("Tienda web", description(), "Java")));
		assertThat(project.get("completeness").get("percent").asInt()).isEqualTo(80);
		assertThat(project.get("completeness").get("nextStep").asString()).as("LOCATION y ABOUT pesan 10: gana el primero")
				.isEqualTo("LOCATION");

		JsonNode location = ok(api.put(PROFILE + "/availability",
				body("hoursPerWeek", 15, "acceptedModalities", List.of("REMOTE"), "location", "Lima")));
		assertThat(location.get("completeness").get("percent").asInt()).isEqualTo(90);
		assertThat(location.get("completeness").get("nextStep").asString()).isEqualTo("ABOUT");

		JsonNode about = ok(api.put(PROFILE + "/about", body("displayName", "Ana", "about", "Estudiante de software con ganas de aprender.")));
		assertThat(about.get("completeness").get("percent").asInt()).isEqualTo(100);
		assertThat(about.get("completeness").get("nextStep").isNull()).isTrue();
		assertThat(api.get(PROFILE).json().get("completeness").get("percent").asInt()).isEqualTo(100);
	}

	@Test
	@DisplayName("006-09: completeness trae percent, items[{key, weight, done}] en el orden de la spec y nextStep")
	void t006_09_completenessShape() {
		ApiClient api = student();
		ok(api.put(PROFILE + "/skills", skills(skill("java", 1), skill("sql", 1), skill("git", 1))));

		JsonNode completeness = api.get(PROFILE).json().get("completeness");

		assertThat(completeness.get("percent").asInt()).isEqualTo(25);
		List<String> shape = new ArrayList<>();
		completeness.get("items").forEach(item -> shape.add(item.get("key").asString() + ":" + item.get("weight").asInt()
				+ ":" + item.get("done").asBoolean()));
		assertThat(shape).containsExactly("ACADEMIC:15:false", "SKILLS:25:true", "AVAILABILITY:15:false",
				"PROJECTS:25:false", "LOCATION:10:false", "ABOUT:10:false");
		assertThat(completeness.get("nextStep").asString()).isEqualTo("PROJECTS");
	}

	@Test
	@DisplayName("006-09: borrar el único proyecto o una habilidad hace bajar el porcentaje")
	void t006_09_percentageGoesDownWhenSomethingIsRemoved() {
		ApiClient api = student();
		JsonNode full = fillEverything(api);
		assertThat(full.get("completeness").get("percent").asInt()).isEqualTo(100);
		String projectId = full.get("projects").get(0).get("id").asString();

		JsonNode withoutProject = ok(api.delete(PROFILE + "/projects/" + projectId));
		assertThat(withoutProject.get("completeness").get("percent").asInt()).isEqualTo(75);
		assertThat(withoutProject.get("completeness").get("nextStep").asString()).isEqualTo("PROJECTS");

		JsonNode twoSkills = ok(api.put(PROFILE + "/skills", skills(skill("java", 1), skill("sql", 1))));
		assertThat(twoSkills.get("completeness").get("percent").asInt()).isEqualTo(50);
		assertThat(twoSkills.get("completeness").get("nextStep").asString()).isEqualTo("SKILLS");
	}

	@Test
	@DisplayName("006-11: guardar dos veces lo mismo da el mismo perfil (las escrituras PUT son idempotentes)")
	void t006_11_putIsIdempotent() {
		ApiClient api = student();
		JsonNode first = ok(api.put(PROFILE + "/skills", skills(skill("java", 2), skill("sql", 3))));
		JsonNode second = ok(api.put(PROFILE + "/skills", skills(skill("java", 2), skill("sql", 3))));

		assertThat(second.get("skills")).isEqualTo(first.get("skills"));
		assertThat(second.get("completeness")).isEqualTo(first.get("completeness"));
	}

	// ------------------------------------------------------------------------------------------ 006-23 a 006-25

	@Test
	@DisplayName("006-23: PUT /about recorta, guarda y devuelve about {displayName, about}; los vacíos quedan nulos; el nombre no cuenta para el avance")
	void t006_23_aboutSaves() {
		ApiClient api = student();
		assertThat(api.get(PROFILE).json().get("about").get("displayName").isNull()).isTrue();

		JsonNode onlyName = ok(api.put(PROFILE + "/about", body("displayName", "  Ana  ", "about", "   ")));
		assertThat(onlyName.get("about").get("displayName").asString()).isEqualTo("Ana");
		assertThat(onlyName.get("about").get("about").isNull()).isTrue();
		assertThat(onlyName.get("completeness").get("percent").asInt()).as("el nombre no suma").isZero();

		JsonNode both = ok(api.put(PROFILE + "/about", body("about", "Me gusta crear aplicaciones web.\nBusco practicar.")));
		assertThat(both.get("about").get("displayName").isNull()).as("reemplaza la sección").isTrue();
		assertThat(both.get("completeness").get("percent").asInt()).isEqualTo(10);
		assertThat(api.get(PROFILE).json().get("about").get("about").asString()).contains("\n");
	}

	@Test
	@DisplayName("006-23: nombre fuera de 2 a 40, presentación fuera de 10 a 500 e invisibles dan 422 y no guardan")
	void t006_23_aboutValidation() {
		ApiClient api = student();
		ok(api.put(PROFILE + "/about", body("displayName", "Ana", "about", "Una presentación válida.")));

		assertThat(errors(api.put(PROFILE + "/about", body("displayName", "A")))).containsExactly("displayName:min-length");
		assertThat(errors(api.put(PROFILE + "/about", body("displayName", repeat('n', 41))))).containsExactly("displayName:max-length");
		assertThat(errors(api.put(PROFILE + "/about", body("about", "corta")))).containsExactly("about:min-length");
		assertThat(errors(api.put(PROFILE + "/about", body("about", repeat('a', 501))))).containsExactly("about:max-length");
		assertThat(errors(api.put(PROFILE + "/about", body("displayName", "\u200B\u200B")))).containsExactly("displayName:invalid");
		assertThat(errors(api.put(PROFILE + "/about", body("displayName", "\u00A0\u00A0\u00A0")))).containsExactly("displayName:invalid");
		assertThat(errors(api.put(PROFILE + "/about", body("displayName", "An\u202Ea")))).containsExactly("displayName:invalid");
		assertThat(errors(api.put(PROFILE + "/about", body("about", "Texto con separador\u2028de línea.")))).containsExactly("about:invalid");
		assertThat(errors(api.put(PROFILE + "/about", body("displayName", "An\u0000a")))).containsExactly("displayName:invalid");
		JsonNode after = api.get(PROFILE).json().get("about");
		assertThat(after.get("displayName").asString()).isEqualTo("Ana");
		assertThat(after.get("about").asString()).isEqualTo("Una presentación válida.");
	}

	@Test
	@DisplayName("006-23: un JSON con el invisible escrito como secuencia \u200b también se rechaza")
	void t006_23_escapedInvisibleInJsonIsRejected() {
		ApiClient api = student();
		byte[] raw = "{\"displayName\":\"\\u200b\\u200b\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8);

		Response response = api.writeChunked("PUT", PROFILE + "/about", raw);

		assertThat(errors(response)).containsExactly("displayName:invalid");
	}

	@Test
	@DisplayName("006-08: dos formas Unicode de la misma tecnología (é compuesta y descompuesta) cuentan como repetidas")
	void t006_08_technologiesCompareInNfc() {
		ApiClient api = student();

		Response response = api.post(PROFILE + "/projects", project("Ok", description(), "Caf\u00E9", "CAFE\u0301"));

		assertThat(errors(response)).containsExactly("technologies[1]:duplicate");
	}

	// ------------------------------------------------------------------------------------------ 006-12

	@Test
	@DisplayName("006-12: ninguna respuesta del perfil incluye el correo ni el id del usuario")
	void t006_12_responsesDoNotIncludeEmail() {
		RegisteredStudent student = registerStudent();
		ApiClient api = student.api();

		Response response = api.put(PROFILE + "/academic", body("career", "Software", "institute", "Demo", "cycle", 3));
		String all = response.body() + api.get(PROFILE).body();

		assertThat(all).doesNotContain(student.email()).doesNotContain("@").doesNotContain(student.id().toString());
	}

	@Test
	@DisplayName("006-12: no se registra auditoría de las ediciones del perfil")
	void t006_12_editsAreNotAudited() {
		RegisteredStudent student = registerStudent();
		int before = count("SELECT count(*) FROM audit_event WHERE actor_user_id = ?", student.id());

		fillEverything(student.api());

		assertThat(count("SELECT count(*) FROM audit_event WHERE actor_user_id = ?", student.id())).isEqualTo(before);
	}

	@Test
	@DisplayName("006-11: el campo role u otros desconocidos en el cuerpo se ignoran")
	void t006_11_unknownFieldsAreIgnored() {
		ApiClient api = student();

		JsonNode profile = ok(api.put(PROFILE + "/academic",
				body("career", "Software", "userId", UUID.randomUUID().toString(), "role", "ADMIN")));

		assertThat(profile.get("academic").get("career").asString()).isEqualTo("Software");
	}

}
