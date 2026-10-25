package com.talenti.application.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Máquina de estados de la postulación (008-06, 008-09, 008-13). */
class ApplicationStatusTests {

	@Test
	@DisplayName("008-13: el modelo admite los ocho estados de RN-06")
	void t008_13_eightStates() {
		assertThat(Arrays.stream(ApplicationStatus.values()).map(Enum::name)).containsExactlyInAnyOrder("SUBMITTED",
				"WITHDRAWN", "EXAM_PENDING", "EXAM_DONE", "INTERVIEW_PENDING", "INTERVIEW_DONE", "EXPIRED",
				"UNDER_REVIEW");
	}

	@Test
	@DisplayName("008-06 y 012-36: se retira desde SUBMITTED, EXAM_PENDING, EXAM_DONE, INTERVIEW_PENDING e INTERVIEW_DONE")
	void t008_06_onlySubmittedOrInterviewCanBeWithdrawn() {
		java.util.Set<ApplicationStatus> withdrawable = java.util.EnumSet.of(ApplicationStatus.SUBMITTED,
				ApplicationStatus.EXAM_PENDING, ApplicationStatus.EXAM_DONE, ApplicationStatus.INTERVIEW_PENDING,
				ApplicationStatus.INTERVIEW_DONE);
		for (ApplicationStatus status : withdrawable) {
			assertThat(status.canWithdraw()).as(status.name()).isTrue();
			assertThat(status.withdraw()).isEqualTo(ApplicationStatus.WITHDRAWN);
		}
		for (ApplicationStatus status : ApplicationStatus.values()) {
			if (!withdrawable.contains(status)) {
				assertThat(status.canWithdraw()).as(status.name()).isFalse();
				assertThatThrownBy(status::withdraw).as(status.name()).isInstanceOf(IllegalStateException.class);
			}
		}
	}

	@Test
	@DisplayName("008-06: una retirada no se reabre: no hay transición que salga de WITHDRAWN")
	void t008_06_withdrawnIsFinal() {
		assertThat(ApplicationStatus.WITHDRAWN.canWithdraw()).isFalse();
		assertThatThrownBy(ApplicationStatus.WITHDRAWN::withdraw).isInstanceOf(IllegalStateException.class);
	}

	@Test
	@DisplayName("008-09: activa es toda postulación que no está retirada ni vencida")
	void t008_09_activeDefinition() {
		for (ApplicationStatus status : ApplicationStatus.values()) {
			boolean closed = status == ApplicationStatus.WITHDRAWN || status == ApplicationStatus.EXPIRED;
			assertThat(status.isActive()).as(status.name()).isEqualTo(!closed);
		}
	}

	@Test
	@DisplayName("012-35 y 012-37: la entrevista empieza desde SUBMITTED o EXAM_DONE; nunca desde EXAM_PENDING ni otro estado")
	void t012_35_interviewStartsFromSubmittedOrExamDone() {
		for (ApplicationStatus status : ApplicationStatus.values()) {
			boolean allowed = status == ApplicationStatus.SUBMITTED || status == ApplicationStatus.EXAM_DONE;
			assertThat(status.canMoveTo(ApplicationStatus.INTERVIEW_PENDING)).as(status.name()).isEqualTo(allowed);
			assertThat(status.canMoveTo(ApplicationStatus.INTERVIEW_DONE)).as(status.name())
					.isEqualTo(status == ApplicationStatus.INTERVIEW_PENDING);
		}
		// Preparado para la spec 014 (012-37): las transiciones del examen ya están en la misma tabla.
		assertThat(ApplicationStatus.SUBMITTED.canMoveTo(ApplicationStatus.EXAM_PENDING)).isTrue();
		assertThat(ApplicationStatus.EXAM_PENDING.canMoveTo(ApplicationStatus.EXAM_DONE)).isTrue();
	}

}
