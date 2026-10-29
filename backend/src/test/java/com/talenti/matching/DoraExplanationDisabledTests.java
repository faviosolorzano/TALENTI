package com.talenti.matching;

import static org.assertj.core.api.Assertions.*;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.talenti.matching.domain.*;
import com.talenti.matching.persistence.ExplanationStore;
import com.talenti.matching.port.CompatibilityExplainerPort;
import com.talenti.matching.service.CompatibilityService;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
    "talenti.demo-data.enabled=true", "talenti.ai.explanation.enabled=false",
    "talenti.ai.explanation.simulated=false", "talenti.ai.explanation.model=gpt-4.1-mini"})
class DoraExplanationDisabledTests extends MatchingIntegrationTest {
    @Autowired CompatibilityService compatibility;
    @Autowired ExplanationStore store;
    @Autowired ExplanationTemplate template;
    @Autowired JsonMapper json;
    private static final String ROUTE=COMPATIBILITY+"/vacancies/"+BACKEND_JAVA+"/explanation";
    private void seed(UUID user,String prompt,String model) {
        var evaluated=compatibility.evaluateOpen(user,BACKEND_JAVA);
        var request=ExplanationRequest.of(evaluated.vacancy().title(),evaluated.result());
        var hash=ExplanationFingerprint.of(request,CompatibilityCalculator.FORMULA_VERSION,prompt,model);
        store.save(user,UUID.fromString(BACKEND_JAVA),new ExplanationStore.Stored(hash,CompatibilityCalculator.FORMULA_VERSION,
            prompt,template.version(),model,"openai",json.writeValueAsString(template.write(request)),Instant.now()));
    }
    @Test void t015_10_disabledIsPrivateDeterministicAndFree() {
        var s=exampleStudentRegistered();
        var a=s.api().post(ROUTE,null); var b=s.api().post(ROUTE,null);
        assertThat(a.status()).isEqualTo(200); assertPrivate(a);
        assertThat(a.json().get("fallbackReason").asString()).isEqualTo("AI_DISABLED");
        assertThat(a.json().get("sections")).isEqualTo(b.json().get("sections"));
        assertThat(count("SELECT count(*) FROM ai_usage_event WHERE user_id=?",s.id())).isZero();
    }
    @Test void t015_12_currentCacheSurvivesDisablingAi() {
        var s=exampleStudentRegistered(); seed(s.id(),CompatibilityExplainerPort.OPENAI_PROMPT_VERSION,"gpt-4.1-mini");
        var r=s.api().post(ROUTE,null);
        assertThat(r.json().get("source").asString()).isEqualTo("AI");
        assertThat(r.json().get("cached").asBoolean()).isTrue();
        assertThat(count("SELECT count(*) FROM ai_usage_event WHERE user_id=?",s.id())).isZero();
    }
    @Test void t015_12_disabledDoesNotReviveOldInstructions() {
        var s=exampleStudentRegistered(); seed(s.id(),"instrucciones-anteriores","gpt-4.1-mini");
        assertThat(s.api().post(ROUTE,null).json().get("fallbackReason").asString()).isEqualTo("AI_DISABLED");
    }
    @Test void t015_12_disabledDoesNotReviveDifferentModel() {
        var s=exampleStudentRegistered(); seed(s.id(),CompatibilityExplainerPort.OPENAI_PROMPT_VERSION,"otro-modelo");
        assertThat(s.api().post(ROUTE,null).json().get("fallbackReason").asString()).isEqualTo("AI_DISABLED");
    }
}
