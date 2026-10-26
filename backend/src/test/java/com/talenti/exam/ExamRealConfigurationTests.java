package com.talenti.exam;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import com.talenti.exam.port.*;
import com.talenti.exam.service.*;
import com.talenti.matching.MatchingIntegrationTest;

/** Enciende el cableado real con credencial inventada y destino local cerrado: no llama a OpenAI. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
		"talenti.demo-data.enabled=true","talenti.exam.runner.mode=simulated",
		"talenti.ai.exam.enabled=true","talenti.ai.exam.model=test-model",
		"talenti.ai.exam.input-per-million-tokens=0.15","talenti.ai.exam.output-per-million-tokens=0.60",
		"talenti.ai.openai.api-key=solo-prueba-sin-acceso","talenti.ai.openai.base-url=http://127.0.0.1:1"})
class ExamRealConfigurationTests extends MatchingIntegrationTest {
	@Autowired ExamAuthoringService authoring;
	@Autowired ExamGradingService grading;
	@Autowired @Qualifier("examAuthor") ExamAuthoringPort author;
	@Autowired @Qualifier("examOpenGrader") OpenAnswerGraderPort grader;
	@Test void t014_33_realAdaptersWireBothServicesWithoutAmbiguousPorts() {
		assertThat(authoring).isNotNull();assertThat(grading).isNotNull();
		assertThat(author.model()).isEqualTo("test-model");assertThat(grader.model()).isEqualTo("test-model");
		assertThat(client().get("/actuator/health").status()).isEqualTo(200);
	}
}
