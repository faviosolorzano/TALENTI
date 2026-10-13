package com.talenti.profile;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.talenti.support.ApiClient;
import com.talenti.support.ApiClient.Response;

import tools.jackson.databind.JsonNode;

/** Escrituras simultáneas de una misma persona (dos pestañas): se hacen una tras otra y no se burlan los límites. */
class ProfileConcurrencyTests extends ProfileIntegrationTest {

	/** Un cliente propio con la misma sesión y el mismo token, para que cada hilo no comparta estado de cookies. */
	private ApiClient copyOf(ApiClient original) {
		ApiClient copy = new ApiClient(port);
		original.cookies().forEach(copy::setCookie);
		copy.origin(original.origin());
		return copy;
	}

	private <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<T>> futures = new ArrayList<>();
			for (Callable<T> task : tasks) {
				futures.add(pool.submit(() -> {
					start.await();
					return task.call();
				}));
			}
			start.countDown();
			List<T> results = new ArrayList<>();
			for (Future<T> future : futures) {
				results.add(future.get(60, TimeUnit.SECONDS));
			}
			return results;
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	@DisplayName("006-08: ocho solicitudes simultáneas para agregar proyectos con 6 existentes: entran solo 4 y nunca hay más de 10")
	void t006_08_parallelCreatesCannotBypassTheLimit() throws Exception {
		RegisteredStudent student = registerStudent();
		for (int i = 1; i <= 6; i++) {
			ok(student.api().post(PROFILE + "/projects", project("Proyecto " + i, description(), "Java")));
		}
		List<Callable<Integer>> tasks = new ArrayList<>();
		for (int i = 1; i <= 8; i++) {
			ApiClient own = copyOf(student.api());
			String name = "Paralelo " + i;
			tasks.add(() -> own.post(PROFILE + "/projects", project(name, description(), "Java")).status());
		}

		List<Integer> statuses = runTogether(tasks);

		assertThat(statuses.stream().filter(status -> status == 201).count()).isEqualTo(4);
		assertThat(statuses.stream().filter(status -> status == 422).count()).isEqualTo(4);
		assertThat(count("SELECT count(*) FROM student_project WHERE user_id = ?", student.id())).isEqualTo(10);
	}

	@Test
	@DisplayName("006-06: dos pestañas reemplazando las habilidades a la vez: gana la última y nunca queda una mezcla")
	void t006_06_parallelReplacesNeverMixLists() throws Exception {
		RegisteredStudent student = registerStudent();
		Set<String> listA = new TreeSet<>(List.of("java", "sql", "git"));
		Set<String> listB = new TreeSet<>(List.of("react", "css", "html", "docker"));
		List<Callable<Integer>> tasks = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			ApiClient own = copyOf(student.api());
			boolean useA = i % 2 == 0;
			tasks.add(() -> own.put(PROFILE + "/skills", useA ? skills(skill("java", 1), skill("sql", 2), skill("git", 3))
					: skills(skill("react", 1), skill("css", 1), skill("html", 1), skill("docker", 1))).status());
		}

		List<Integer> statuses = runTogether(tasks);

		assertThat(statuses).allMatch(status -> status == 200);
		JsonNode skills = student.api().get(PROFILE).json().get("skills");
		Set<String> finalSlugs = new TreeSet<>();
		skills.forEach(item -> finalSlugs.add(item.get("slug").asString()));
		assertThat(finalSlugs).isIn(listA, listB);
		assertThat(count("SELECT count(*) FROM student_skill WHERE user_id = ?", student.id())).isEqualTo(finalSlugs.size());
	}

	@Test
	@DisplayName("006-11: las primeras escrituras simultáneas de una persona sin perfil crean una sola fila y todas responden bien")
	void t006_11_firstWritesRaceCreatesOneRow() throws Exception {
		RegisteredStudent student = registerStudent();
		List<Callable<Response>> tasks = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			ApiClient own = copyOf(student.api());
			int cycle = i + 1;
			tasks.add(() -> own.put(PROFILE + "/academic", body("career", "Software", "cycle", cycle)));
		}

		List<Response> responses = runTogether(tasks);

		assertThat(responses).extracting(Response::status).allMatch(status -> status == 200);
		assertThat(profileRows(student.id())).isEqualTo(1);
	}

}
