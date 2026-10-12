package com.talenti.vacancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.talenti.vacancy.persistence.VacancyReadRepository;

/**
 * 004-08: el total y la página del listado salen de la misma instantánea de la base. La lectura corre con
 * aislamiento {@code REPEATABLE_READ}; la prueba lo mira desde dentro de la transacción real del servicio.
 */
class VacancyIsolationTests extends VacancyIntegrationTest {

	@MockitoSpyBean
	private VacancyReadRepository repository;

	@Test
	@DisplayName("004-08: el listado, las destacadas y el detalle leen dentro de una transacción REPEATABLE READ de solo lectura")
	void t004_08_readsUseASingleSnapshot() {
		AtomicReference<String> isolation = new AtomicReference<>();
		AtomicReference<String> readOnly = new AtomicReference<>();
		doAnswer(call -> {
			isolation.set(jdbc.queryForObject("SHOW transaction_isolation", String.class));
			readOnly.set(jdbc.queryForObject("SHOW transaction_read_only", String.class));
			return call.callRealMethod();
		}).when(repository).search(any(), anyInt(), anyInt());

		list("?size=24");

		assertThat(isolation.get()).isEqualTo("repeatable read");
		assertThat(readOnly.get()).isEqualTo("on");
		assertThat(jdbc.queryForObject("SHOW transaction_isolation", String.class)).as("fuera del servicio: el de siempre")
				.isEqualTo("read committed");
	}

}
