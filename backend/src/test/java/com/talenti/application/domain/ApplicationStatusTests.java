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
	@DisplayName("008-06: solo una postulación enviada se puede retirar y lleva a WITHDRAWN")
	void t008_06_onlySubmittedCanBeWithdrawn() {
		assertThat(ApplicationStatus.SUBMITTED.canWithdraw()).isTrue();
		assertThat(ApplicationStatus.SUBMITTED.withdraw()).isEqualTo(ApplicationStatus.WITHDRAWN);
		for (ApplicationStatus status : ApplicationStatus.values()) {
			if (status != ApplicationStatus.SUBMITTED) {
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

}
