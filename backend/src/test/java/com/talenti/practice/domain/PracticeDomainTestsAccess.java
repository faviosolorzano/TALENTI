package com.talenti.practice.domain;

/** Datos de prueba del dominio (banco y rúbrica inventados) para las pruebas de otros paquetes. */
public final class PracticeDomainTestsAccess {

	private PracticeDomainTestsAccess() {
	}

	public static QuestionBank bank() {
		return PracticeDomainTests.bank();
	}

	public static Rubric rubric() {
		return PracticeDomainTests.rubric();
	}

}
