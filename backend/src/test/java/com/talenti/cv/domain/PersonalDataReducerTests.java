package com.talenti.cv.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 009-09: la reducción de datos personales es la misma para todos los adaptadores y tiene su propia prueba. */
class PersonalDataReducerTests {

	private final PersonalDataReducer reducer = new PersonalDataReducer();

	@Test
	@DisplayName("009-09: quita correos, teléfonos y documentos, también dentro de una línea con otro texto")
	void t009_09_removesContactData() {
		String reduced = reducer.reduce("""
				Valeria Quispe Mendoza
				Escribeme a valeria.demo@ejemplo.test o al +51 987 654 321 para coordinar.
				Contacto (01) 456-7890
				Documento 12345678 vigente
				Habilidades: Java y SQL
				""");

		assertThat(reduced).doesNotContain("@").doesNotContain("987").doesNotContain("456-7890")
				.doesNotContain("12345678").contains("Habilidades: Java y SQL");
		assertThat(reduced).contains("Escribeme a").contains("para coordinar");
	}

	@Test
	@DisplayName("009-09: quita las líneas de DNI, dirección, estado civil, fecha de nacimiento y otros datos personales")
	void t009_09_removesPersonalLines() {
		String reduced = reducer.reduce("""
				Cuerpo del CV
				DNI: 87654321
				Dirección: Jr. Las Flores 456, Miraflores
				Domicilio: Calle Sol 12
				Estado civil: soltero
				Fecha de nacimiento: 3 de mayo de 2001
				Nacimiento: 03/05/2001
				Nacionalidad: peruana
				Edad: 23 años
				Vivo en Av. Los Olivos 345 desde hace años
				Java y Spring
				""");

		assertThat(reduced).isEqualTo("Cuerpo del CV\nJava y Spring");
	}

	@Test
	@DisplayName("009-09: una fecha de nacimiento suelta se quita, pero los años de estudio no se confunden con teléfonos")
	void t009_09_keepsYearRanges() {
		String reduced = reducer.reduce("""
				Perfil de prueba
				Nací el 12/05/2002 en la ciudad
				Estudios 2021 - 2024 en el instituto
				Proyecto entre 2022-2023
				""");

		assertThat(reduced).doesNotContain("12/05/2002");
		assertThat(reduced).contains("2021 - 2024").contains("2022-2023");
	}

	@Test
	@DisplayName("009-09: conserva los enlaces a proyectos y quita los de redes sociales")
	void t009_09_keepsProjectLinks() {
		String reduced = reducer.reduce("""
				Perfil de prueba
				Repositorio https://github.com/valeria-demo/tienda-web-12345678 listo
				Mi perfil https://www.linkedin.com/in/valeria-demo-12345 y https://instagram.com/valeria.demo
				Demo en https://valeria-demo.ejemplo.test/proyecto
				""");

		assertThat(reduced).contains("https://github.com/valeria-demo/tienda-web-12345678")
				.contains("https://valeria-demo.ejemplo.test/proyecto").doesNotContain("linkedin")
				.doesNotContain("instagram");
	}

	@Test
	@DisplayName("009-09: quita el nombre al mejor esfuerzo: la primera línea si parece un nombre y el campo Nombre")
	void t009_09_removesNameBestEffort() {
		String reduced = reducer.reduce("""
				Valeria Quispe Mendoza
				Resumen
				Soy Valeria Quispe Mendoza y me gusta programar en Java.
				Nombre: Carlos Huaman Rojas
				El proyecto de Carlos Huaman Rojas usa SQL.
				""");

		assertThat(reduced).doesNotContain("Valeria").doesNotContain("Quispe").doesNotContain("Carlos")
				.doesNotContain("Huaman").contains("programar en Java").contains("usa SQL");
	}

	@Test
	@DisplayName("009-09: una primera línea que es un título o un encabezado no se toma por un nombre")
	void t009_09_aTitleIsNotAName() {
		String reduced = reducer.reduce("""
				Desarrollador Backend Junior
				Habilidades
				Java y SQL
				""");

		assertThat(reduced).startsWith("Desarrollador Backend Junior").contains("Java y SQL");
		assertThat(reducer.reduce("Datos Personales\nJava y SQL")).startsWith("Datos Personales");
	}

	@Test
	@DisplayName("009-09: es determinista y tolera texto vacío o nulo")
	void t009_09_deterministicAndNullSafe() {
		String text = "Java\nSQL\nDocker";

		assertThat(reducer.reduce(text)).isEqualTo(reducer.reduce(text)).isEqualTo(text);
		assertThat(reducer.reduce(null)).isEmpty();
		assertThat(reducer.reduce("   \n  ")).isEmpty();
	}

}
