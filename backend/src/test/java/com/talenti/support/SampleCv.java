package com.talenti.support;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.talenti.vacancy.SkillInfo;

/**
 * Un CV INVENTADO para las pruebas: nombre, correo, teléfono, DNI y dirección falsos, y una marca única para comprobar
 * que su contenido nunca llega a los registros ni a los errores.
 */
public final class SampleCv {

	public static final String NAME = "Valeria Quispe Mendoza";

	public static final String EMAIL = "valeria.demo@ejemplo.test";

	public static final String PHONE = "987 654 321";

	public static final String DNI = "12345678";

	private SampleCv() {
	}

	/** Un marcador único por prueba, que aparece en una línea de habilidades y en un proyecto. */
	public static String uniqueMark() {
		return "MARCA" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();
	}

	public static List<String> lines(String mark) {
		List<String> lines = new ArrayList<>();
		lines.add(NAME);
		lines.add("Correo: " + EMAIL);
		lines.add("Telefono: " + PHONE);
		lines.add("DNI: " + DNI);
		lines.add("Direccion: Av. Los Pinos 123, Lima");
		lines.add("Estado civil: soltera");
		lines.add("Fecha de nacimiento: 12/05/2002");
		lines.add("https://github.com/valeria-demo/tienda-web");
		lines.add("Educacion");
		lines.add("Instituto Tecsup - Diseno y Desarrollo de Software");
		lines.add("5to ciclo (2023 - 2026)");
		lines.add("Habilidades");
		lines.add("Java, Spring Boot, SQL, PostgreSQL, Docker");
		lines.add("JavaScript y React " + mark);
		lines.add("Conocimientos de Digital Marketing y de la rama principal del equipo");
		lines.add("Proyectos");
		lines.add("Tienda Web");
		lines.add("Aplicacion de comercio electronico hecha con Java y Spring Boot " + mark + ".");
		lines.add("- Base de datos PostgreSQL con Docker");
		lines.add("Sistema de Notas: API REST para registrar notas usando Node y MongoDB.");
		return lines;
	}

	public static byte[] pdf(String mark) {
		return TestPdfs.of(lines(mark));
	}

	/** El catálogo mínimo para las pruebas unitarias (en la base de datos real viene sembrado). */
	public static List<SkillInfo> catalog() {
		return List.of(skill("java", "Java", "Java SE"), skill("spring-boot", "Spring Boot", "Spring", "SpringBoot"),
				skill("sql", "SQL"), skill("postgresql", "PostgreSQL", "Postgres"),
				skill("git", "Git", "GitHub", "GitLab"), skill("docker", "Docker", "Contenedores"),
				skill("javascript", "JavaScript", "JS"), skill("react", "React", "ReactJS"),
				skill("node-js", "Node.js", "NodeJS", "Node"), skill("mongodb", "MongoDB", "Mongo"),
				skill("testing", "Testing", "Pruebas"), skill("rest-apis", "REST APIs", "API REST", "REST"),
				skill("csharp", "C#", "CSharp"), skill("dotnet", ".NET", "ASP.NET"));
	}

	private static SkillInfo skill(String slug, String name, String... aliases) {
		return new SkillInfo(UUID.randomUUID(), slug, name, List.of(aliases));
	}

}
