package com.talenti.matching;

import static org.assertj.core.api.Assertions.*;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import com.talenti.identity.Role;
import com.talenti.matching.adapter.SimulatedCompatibilityExplainer;
import com.talenti.matching.domain.ExplanationDraft;
import com.talenti.support.ApiClient;

/** HTTP, PostgreSQL y consumo reales; adaptador de IA simulado, sin red externa. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
    "talenti.demo-data.enabled=true", "talenti.ai.explanation.simulated=true",
    "talenti.ai.explanation.timeout=2s", "talenti.ai.limits.per-user-daily.explanation=2"})
class DoraExplanationApiTests extends MatchingIntegrationTest {
    @Autowired SimulatedCompatibilityExplainer explainer;
    private static final String ROUTE=COMPATIBILITY+"/vacancies/"+BACKEND_JAVA+"/explanation";
    @AfterEach void reset() { explainer.reset(); }
    private void forget(UUID user) { jdbc.update("DELETE FROM compatibility_explanation WHERE user_id=?",user); }
    private int usage(UUID user) { return count("SELECT count(*) FROM ai_usage_event WHERE user_id=? AND function='EXPLANATION'",user); }

    @Test void t015_01_rolesAndMissingVacancy() {
        assertThat(client().post(ROUTE,null).status()).isEqualTo(401);
        for(Role role:List.of(Role.COMPANY,Role.ADMIN)) {
            var u=createUser(role); var api=client(); api.post("/api/v1/auth/login",loginBody(u.email(),u.password()));
            assertThat(api.post(ROUTE,null).status()).isEqualTo(403);
        }
        var api=exampleStudent();
        for(String invalid:List.of("bad",UUID.randomUUID().toString()))
            assertThat(api.post(COMPATIBILITY+"/vacancies/"+invalid+"/explanation",null).status()).isEqualTo(404);
    }
    @Test void t015_02_readsNeverGenerateOrConsume() {
        var s=exampleStudentRegistered(); int before=explainer.calls();
        var detail=s.api().get(COMPATIBILITY+"/vacancies/"+BACKEND_JAVA);
        assertThat(detail.json().get("doraExplanation").get("available").asBoolean()).isTrue();
        assertThat(s.api().get(COMPATIBILITY+"/recommended").status()).isEqualTo(200);
        assertThat(s.api().get(COMPATIBILITY+"/summaries?vacancyId="+BACKEND_JAVA).status()).isEqualTo(200);
        assertThat(explainer.calls()).isEqualTo(before); assertThat(usage(s.id())).isZero();
    }
    @Test void t015_03_insufficientDoesNotConsume() {
        var s=registerStudent(); int before=explainer.calls();
        var r=s.api().post(ROUTE,null);
        assertThat(r.status()).isEqualTo(409);
        assertThat(r.json().get("code").asString()).isEqualTo("compatibility-insufficient");
        assertThat(explainer.calls()).isEqualTo(before); assertThat(usage(s.id())).isZero();
    }
    @Test void t015_04_11_12_13_14_privatePayloadStableScoreCacheAndVersionedConsumption() {
        var s=exampleStudentRegistered(); int before=explainer.calls();
        var first=s.api().post(ROUTE,null); assertThat(first.status()).isEqualTo(200); assertPrivate(first);
        var b=first.json(); assertThat(b.get("source").asString()).isEqualTo("AI");
        assertThat(b.get("percent").asInt()).isEqualTo(81); assertThat(b.get("level").asString()).isEqualTo("HIGH");
        assertThat(b.get("cached").asBoolean()).isFalse();
        assertThat(b.get("practice")).hasSize(2);
        assertThat(b.get("practice").get(0).get("skill").get("slug").asString()).isEqualTo("sql");
        assertThat(b.get("versions").get("model").asString()).isEqualTo(SimulatedCompatibilityExplainer.MODEL);
        var second=s.api().post(ROUTE,null);
        assertThat(second.json().get("cached").asBoolean()).isTrue();
        assertThat(explainer.calls()).isEqualTo(before+1); assertThat(usage(s.id())).isEqualTo(1);
        String sent=explainer.journal().getLast();
        assertThat(sent).doesNotContain(s.email(),s.id().toString(),"percent","81","Lima");
        assertThat(sent).contains("Java","básico","intermedio");
        assertThat(count("SELECT count(*) FROM ai_usage_event WHERE user_id=? AND function='EXPLANATION' AND outcome='SUCCESS' AND estimated_cost_micros>0",s.id())).isEqualTo(1);
        assertThat(s.api().put(PROFILE+"/skills",skills(skill("java",2),skill("spring-boot",1),skill("sql",2),skill("git",1))).status()).isEqualTo(200);
        var changed=s.api().post(ROUTE,null);
        assertThat(changed.json().get("cached").asBoolean()).isFalse();
        assertThat(explainer.calls()).isEqualTo(before+2);
    }
    @Test void t015_08_invalidOutputNeverChangesPercentOrConsumesPersonalQuota() {
        var s=exampleStudentRegistered();
        explainer.respond(req -> new ExplanationDraft("Tienes 100 % de compatibilidad.",null,null,List.of()));
        var r=s.api().post(ROUTE,null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().get("source").asString()).isEqualTo("TEMPLATE");
        assertThat(r.json().get("fallbackReason").asString()).isEqualTo("INVALID_OUTPUT");
        assertThat(r.json().get("percent").asInt()).isEqualTo(81);
        assertThat(count("SELECT count(*) FROM ai_usage_event WHERE user_id=? AND outcome='FAILED_PLATFORM'",s.id())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM compatibility_explanation WHERE user_id=?",s.id())).isZero();
    }
    @Test void t015_10_failureAndTimeoutReturnTemplate() {
        for(var mode:List.of(SimulatedCompatibilityExplainer.Mode.FAIL,SimulatedCompatibilityExplainer.Mode.HANG)) {
            explainer.mode(mode); var s=exampleStudentRegistered(); var r=s.api().post(ROUTE,null);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.json().get("fallbackReason").asString()).isEqualTo(mode==SimulatedCompatibilityExplainer.Mode.FAIL?"AI_ERROR":"TIMEOUT");
            assertThat(count("SELECT count(*) FROM ai_usage_event WHERE user_id=? AND outcome='FAILED_PLATFORM'",s.id())).isEqualTo(1);
        }
    }
    @Test void t015_15_limitPreservesCache() {
        var s=exampleStudentRegistered();
        assertThat(s.api().post(ROUTE,null).json().get("source").asString()).isEqualTo("AI");
        forget(s.id());
        assertThat(s.api().post(ROUTE,null).json().get("source").asString()).isEqualTo("AI");
        assertThat(s.api().post(ROUTE,null).json().get("cached").asBoolean()).isTrue();
        forget(s.id()); int before=explainer.calls();
        assertThat(s.api().post(ROUTE,null).json().get("fallbackReason").asString()).isEqualTo("PERSONAL_LIMIT");
        assertThat(explainer.calls()).isEqualTo(before); assertThat(usage(s.id())).isEqualTo(2);
    }
    @Test void t015_17_concurrentRequestsShareOneCall() {
        var s=exampleStudentRegistered(); int before=explainer.calls(); explainer.delay(Duration.ofMillis(250));
        var a=CompletableFuture.supplyAsync(()->s.api().post(ROUTE,null));
        var b=CompletableFuture.supplyAsync(()->s.api().post(ROUTE,null));
        assertThat(a.join().status()).isEqualTo(200); assertThat(b.join().status()).isEqualTo(200);
        assertThat(explainer.calls()).isEqualTo(before+1); assertThat(usage(s.id())).isEqualTo(1);
    }
    @Test void t015_19_userAndVacancyCascade() {
        var s=exampleStudentRegistered(); s.api().post(ROUTE,null);
        assertThat(count("SELECT count(*) FROM compatibility_explanation WHERE user_id=?",s.id())).isEqualTo(1);
        jdbc.update("DELETE FROM app_user WHERE id=?",s.id());
        assertThat(count("SELECT count(*) FROM compatibility_explanation WHERE user_id=?",s.id())).isZero();
        UUID vacancy=insertOpenWithJava("Vacante de explicación"), user=exampleStudentRegistered().id();
        jdbc.update("INSERT INTO compatibility_explanation(id,user_id,vacancy_id,input_hash,formula_version,prompt_version,template_version,model,adapter,content,generated_at) VALUES (?,?,?,?,'1','1','1','simulated','simulated','{}'::jsonb,now())",UUID.randomUUID(),user,vacancy,"a".repeat(64));
        deleteVacancy(vacancy);
        assertThat(count("SELECT count(*) FROM compatibility_explanation WHERE vacancy_id=?",vacancy)).isZero();
    }
}
